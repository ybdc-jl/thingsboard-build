/**
 * Copyright © 2016-2024 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.housekeeper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.common.util.ThingsBoardThreadFactory;
import org.thingsboard.server.common.data.housekeeper.HousekeeperTask;
import org.thingsboard.server.common.data.housekeeper.HousekeeperTaskType;
import org.thingsboard.server.common.data.notification.rule.trigger.TaskProcessingFailureTrigger;
import org.thingsboard.server.common.msg.notification.NotificationRuleProcessor;
import org.thingsboard.server.gen.transport.TransportProtos.HousekeeperTaskProto;
import org.thingsboard.server.gen.transport.TransportProtos.ToHousekeeperServiceMsg;
import org.thingsboard.server.queue.TbQueueConsumer;
import org.thingsboard.server.queue.common.TbProtoQueueMsg;
import org.thingsboard.server.queue.housekeeper.HousekeeperConfig;
import org.thingsboard.server.queue.provider.TbCoreQueueFactory;
import org.thingsboard.server.queue.util.AfterStartUp;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.housekeeper.processor.HousekeeperTaskProcessor;
import org.thingsboard.server.service.housekeeper.stats.HousekeeperStatsService;
import org.thingsboard.server.service.queue.consumer.QueueConsumerManager;

import javax.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@TbCoreComponent
@Service
@Slf4j
public class HousekeeperService {

    private final Map<HousekeeperTaskType, HousekeeperTaskProcessor<?>> taskProcessors;

    private final HousekeeperConfig config;
    private final HousekeeperReprocessingService reprocessingService;
    private final Optional<HousekeeperStatsService> statsService;
    private final NotificationRuleProcessor notificationRuleProcessor;
    private final QueueConsumerManager<TbProtoQueueMsg<ToHousekeeperServiceMsg>> consumer;

    private final ExecutorService consumerExecutor = Executors.newSingleThreadExecutor(ThingsBoardThreadFactory.forName("housekeeper-consumer"));
    private final ExecutorService taskExecutor = Executors.newSingleThreadExecutor(ThingsBoardThreadFactory.forName("housekeeper-task-processor"));

    public HousekeeperService(HousekeeperConfig config,
                              HousekeeperReprocessingService reprocessingService,
                              TbCoreQueueFactory queueFactory,
                              Optional<HousekeeperStatsService> statsService,
                              NotificationRuleProcessor notificationRuleProcessor,
                              @Lazy List<HousekeeperTaskProcessor<?>> taskProcessors) {
        this.config = config;
        this.reprocessingService = reprocessingService;
        this.statsService = statsService;
        this.notificationRuleProcessor = notificationRuleProcessor;
        this.consumer = QueueConsumerManager.<TbProtoQueueMsg<ToHousekeeperServiceMsg>>builder()
                .name("Housekeeper")
                .msgPackProcessor(this::processMsgs)
                .pollInterval(config.getPollInterval())
                .consumerCreator(queueFactory::createHousekeeperMsgConsumer)
                .consumerExecutor(consumerExecutor)
                .build();
        this.taskProcessors = taskProcessors.stream().collect(Collectors.toMap(HousekeeperTaskProcessor::getTaskType, p -> p));
    }

    @AfterStartUp(order = AfterStartUp.REGULAR_SERVICE)
    public void afterStartUp() {
        consumer.subscribe();
        consumer.launch();
    }

    private void processMsgs(List<TbProtoQueueMsg<ToHousekeeperServiceMsg>> msgs, TbQueueConsumer<TbProtoQueueMsg<ToHousekeeperServiceMsg>> consumer) {
        for (TbProtoQueueMsg<ToHousekeeperServiceMsg> msg : msgs) {
            log.trace("Processing task: {} attempt={}", msg, msg.getValue().getTask().getAttempt());
            try {
                processTask(msg.getValue());
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                log.error("Unexpected error during message processing [{}]", msg, e);
                reprocessingService.submitForReprocessing(msg.getValue(), e);
            }
        }
        consumer.commit();
    }

    @SuppressWarnings("unchecked")
    protected <T extends HousekeeperTask> void processTask(ToHousekeeperServiceMsg msg) throws Exception {
        HousekeeperTask task = JacksonUtil.fromString(msg.getTask().getValue(), HousekeeperTask.class);
        HousekeeperTaskType taskType = task.getTaskType();
        if (config.getDisabledTaskTypes().contains(taskType)) {
            log.debug("Task type {} is disabled, ignoring {}", taskType, task);
            return;
        }
        HousekeeperTaskProcessor<T> taskProcessor = (HousekeeperTaskProcessor<T>) taskProcessors.get(taskType);
        if (taskProcessor == null) {
            throw new IllegalArgumentException("Unsupported task type " + taskType);
        }

        if (log.isDebugEnabled()) {
            log.debug("[{}] {} {}", task.getTenantId(), isNew(msg.getTask()) ? "Processing" : "Reprocessing", task.getDescription());
        }
        try {
            Future<Object> future = taskExecutor.submit(() -> {
                taskProcessor.process((T) task);
                return null;
            });
            future.get(config.getTaskProcessingTimeout(), TimeUnit.MILLISECONDS);
            statsService.ifPresent(statsService -> statsService.reportProcessed(taskType, msg));
        } catch (InterruptedException e) {
            throw e;
        } catch (Throwable e) {
            Throwable error = e;
            if (e instanceof ExecutionException) {
                error = e.getCause();
            } else if (e instanceof TimeoutException) {
                error = new TimeoutException("Timeout after " + config.getTaskProcessingTimeout() + " seconds");
            }
            log.error("[{}][{}][{}] {} task processing failed, submitting for reprocessing (attempt {}): {}",
                    task.getTenantId(), task.getEntityId().getEntityType(), task.getEntityId(),
                    taskType, msg.getTask().getAttempt(), task, error);

            if (msg.getTask().getAttempt() < config.getMaxReprocessingAttempts()) {
                reprocessingService.submitForReprocessing(msg, error);
            } else {
                log.error("Failed to process task in {} attempts: {}", msg.getTask().getAttempt(), msg);
                notificationRuleProcessor.process(TaskProcessingFailureTrigger.builder()
                        .task(task)
                        .error(error)
                        .attempt(msg.getTask().getAttempt())
                        .build());
            }
            statsService.ifPresent(statsService -> statsService.reportFailure(taskType, msg));
        }
    }

    private boolean isNew(HousekeeperTaskProto task) {
        return task.getErrorsCount() == 0;
    }

    @PreDestroy
    private void stop() throws Exception {
        consumer.stop();
        consumerExecutor.shutdownNow();
        log.info("Stopped Housekeeper service");
    }

}
