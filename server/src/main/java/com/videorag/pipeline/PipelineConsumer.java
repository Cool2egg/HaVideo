package com.videorag.pipeline;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.videorag.common.usage.UsageContext;
import com.videorag.media.entity.MediaFile;
import com.videorag.media.mapper.MediaFileMapper;
import com.videorag.pipeline.entity.ProcessTask;
import com.videorag.pipeline.handler.HandlerRegistry;
import com.videorag.pipeline.mapper.ProcessTaskMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
@RocketMQMessageListener(
        topic = "${rocketmq.topic.pipeline:videorag-pipeline}",
        consumerGroup = "${rocketmq.consumer.group:videorag-pipeline-consumer}",
        consumeTimeout = 30)
public class PipelineConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(PipelineConsumer.class);
    private static final String WORKER_ID = workerId();
    private static final ScheduledExecutorService HEARTBEAT =
            Executors.newScheduledThreadPool(1, runnable -> {
                Thread thread = new Thread(runnable, "pipeline-lease-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    private final ProcessTaskMapper taskMapper;
    private final MediaFileMapper mediaFileMapper;
    private final HandlerRegistry handlerRegistry;
    private final PipelineOrchestrator orchestrator;
    private final TaskService taskService;

    @Value("${app.pipeline.lease.heartbeat-seconds:30}")
    private long heartbeatSeconds;

    @Value("${app.pipeline.lease.timeout-seconds:90}")
    private long leaseTimeoutSeconds;

    public PipelineConsumer(ProcessTaskMapper taskMapper,
                            MediaFileMapper mediaFileMapper,
                            HandlerRegistry handlerRegistry,
                            PipelineOrchestrator orchestrator,
                            TaskService taskService) {
        this.taskMapper = taskMapper;
        this.mediaFileMapper = mediaFileMapper;
        this.handlerRegistry = handlerRegistry;
        this.orchestrator = orchestrator;
        this.taskService = taskService;
    }

    @Override
    public void onMessage(String message) {
        JSONObject payload;
        Long taskId;
        Long mediaId;
        Long leaseVersion;
        String stageName;
        try {
            payload = JSON.parseObject(message);
            taskId = payload.getLong("taskId");
            mediaId = payload.getLong("mediaId");
            stageName = payload.getString("stage");
            leaseVersion = payload.getLong("leaseVersion");
        } catch (RuntimeException error) {
            log.error("pipeline_message_malformed message={}", message, error);
            return;
        }
        if (taskId == null || mediaId == null || stageName == null || leaseVersion == null) {
            log.warn("pipeline_message_incomplete message={}", message);
            return;
        }

        LocalDateTime leasedUntil = LocalDateTime.now().plusSeconds(leaseTimeoutSeconds);
        if (!taskService.claim(taskId, leaseVersion, WORKER_ID, leasedUntil)) {
            log.info("task_claim_lost taskId={} mediaId={} stage={} leaseVersion={}",
                    taskId, mediaId, stageName, leaseVersion);
            return;
        }

        ProcessTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("task_missing_after_claim taskId={}", taskId);
            return;
        }
        MediaFile media = mediaFileMapper.selectById(task.getMediaId());
        if (media != null) {
            UsageContext.set(media.getUserId());
        }

        LeaseContext.Guard guard = LeaseContext.open();
        ScheduledFuture<?> heartbeat = startHeartbeat(taskId, leaseVersion, guard);
        try {
            Stage stage = Stage.valueOf(task.getStage());
            handlerRegistry.require(stage).handle(task.getMediaId());
            heartbeat.cancel(false);
            orchestrator.success(task, leaseVersion);
        } catch (LeaseLostException error) {
            log.warn("stage_lease_lost mediaId={} stage={} leaseVersion={} workerId={}",
                    task.getMediaId(), task.getStage(), leaseVersion, WORKER_ID);
        } catch (Exception error) {
            heartbeat.cancel(false);
            orchestrator.failure(task, error, leaseVersion);
        } finally {
            heartbeat.cancel(false);
            LeaseContext.close();
            UsageContext.clear();
        }
    }

    private ScheduledFuture<?> startHeartbeat(Long taskId, long leaseVersion, LeaseContext.Guard guard) {
        long period = Math.max(5, heartbeatSeconds);
        return HEARTBEAT.scheduleAtFixedRate(() -> {
            if (guard.isLost()) {
                return;
            }
            try {
                boolean renewed = taskService.renewLease(taskId, leaseVersion,
                        LocalDateTime.now().plusSeconds(leaseTimeoutSeconds));
                if (!renewed) {
                    guard.markLost();
                    log.warn("lease_renew_failed taskId={} leaseVersion={} workerId={}",
                            taskId, leaseVersion, WORKER_ID);
                }
            } catch (RuntimeException error) {
                log.warn("lease_renew_error taskId={} error={}", taskId, error.getMessage());
            }
        }, period, period, TimeUnit.SECONDS);
    }

    private static String workerId() {
        String host = System.getenv().getOrDefault("HOSTNAME",
                System.getenv().getOrDefault("COMPUTERNAME", "unknown"));
        return host + "-" + ProcessHandle.current().pid();
    }
}
