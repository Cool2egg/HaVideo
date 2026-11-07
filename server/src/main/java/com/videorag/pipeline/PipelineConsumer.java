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
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "${rocketmq.topic.pipeline:videorag-pipeline}",
        consumerGroup = "${rocketmq.consumer.group:videorag-pipeline-consumer}",
        consumeTimeout = 30)
public class PipelineConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(PipelineConsumer.class);

    private final ProcessTaskMapper taskMapper;
    private final MediaFileMapper mediaFileMapper;
    private final HandlerRegistry handlerRegistry;
    private final PipelineOrchestrator orchestrator;

    public PipelineConsumer(ProcessTaskMapper taskMapper,
                            MediaFileMapper mediaFileMapper,
                            HandlerRegistry handlerRegistry,
                            PipelineOrchestrator orchestrator) {
        this.taskMapper = taskMapper;
        this.mediaFileMapper = mediaFileMapper;
        this.handlerRegistry = handlerRegistry;
        this.orchestrator = orchestrator;
    }

    @Override
    public void onMessage(String message) {
        ProcessTask task = null;
        try {
            JSONObject payload = JSON.parseObject(message);
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
            UsageContext.clear();
        }
    }
}
