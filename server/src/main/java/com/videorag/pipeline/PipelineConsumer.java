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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RocketMQMessageListener(
        topic = "${rocketmq.topic.pipeline:videorag-pipeline}",
        consumerGroup = "${rocketmq.consumer.group:videorag-pipeline-consumer}",
        consumeTimeout = 30)
public class PipelineConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(PipelineConsumer.class);
    private static final String STAGE_LOCK_PREFIX = "pipeline:stage:";

    private final ProcessTaskMapper taskMapper;
    private final MediaFileMapper mediaFileMapper;
    private final HandlerRegistry handlerRegistry;
    private final PipelineOrchestrator orchestrator;
    private final RedissonClient redissonClient;

    public PipelineConsumer(ProcessTaskMapper taskMapper,
                            MediaFileMapper mediaFileMapper,
                            HandlerRegistry handlerRegistry,
                            PipelineOrchestrator orchestrator,
                            RedissonClient redissonClient) {
        this.taskMapper = taskMapper;
        this.mediaFileMapper = mediaFileMapper;
        this.handlerRegistry = handlerRegistry;
        this.orchestrator = orchestrator;
        this.redissonClient = redissonClient;
    }

    @Override
    public void onMessage(String message) {
        JSONObject payload;
        Long mediaId;
        String stageName;
        try {
            payload = JSON.parseObject(message);
            mediaId = payload.getLong("mediaId");
            stageName = payload.getString("stage");
        } catch (RuntimeException error) {
            log.error("pipeline_message_malformed message={}", message, error);
            return;
        }
        if (mediaId == null || stageName == null) {
            log.warn("pipeline_message_incomplete message={}", message);
            return;
        }

        RLock lock = redissonClient.getLock(stageLockKey(mediaId, stageName));
        if (!acquire(lock, mediaId, stageName)) {
            return;
        }

        ProcessTask task = null;
        try {
            Long taskId = payload.getLong("taskId");
            task = taskMapper.selectById(taskId);
            if (task == null) {
                log.warn("task_message_missing taskId={}", taskId);
                return;
            }
            if (!TaskStatus.RUNNING.name().equals(task.getStatus())) {
                log.info("task_message_ignored taskId={} status={}", taskId, task.getStatus());
                return;
            }
            MediaFile media = mediaFileMapper.selectById(task.getMediaId());
            if (media != null) {
                UsageContext.set(media.getUserId());
            }
            Stage stage = Stage.valueOf(task.getStage());
            handlerRegistry.require(stage).handle(task.getMediaId());
            orchestrator.success(task);
        } catch (Exception error) {
            if (task != null) {
                orchestrator.failure(task, error);
            } else {
                log.error("pipeline_message_failed message={}", message, error);
            }
        } finally {
            release(lock);
            UsageContext.clear();
        }
    }

    private boolean acquire(RLock lock, Long mediaId, String stage) {
        try {
            boolean locked = lock.tryLock(0, -1, TimeUnit.SECONDS);
            if (!locked) {
                log.info("pipeline_stage_busy mediaId={} stage={}", mediaId, stage);
            }
            return locked;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            log.warn("pipeline_lock_interrupted mediaId={} stage={}", mediaId, stage);
            return false;
        } catch (RuntimeException error) {
            // 锁不可用不标记业务失败，任务交给租约过期回收
            log.warn("pipeline_lock_unavailable mediaId={} stage={} error={}",
                    mediaId, stage, error.getMessage());
            return false;
        }
    }

    private void release(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException error) {
            log.warn("pipeline_lock_release_failed lock={} error={}",
                    lock.getName(), error.getMessage());
        }
    }

    private String stageLockKey(Long mediaId, String stage) {
        return STAGE_LOCK_PREFIX + mediaId + ":" + stage;
    }
}
