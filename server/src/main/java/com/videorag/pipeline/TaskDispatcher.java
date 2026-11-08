package com.videorag.pipeline;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videorag.pipeline.entity.ProcessTask;
import com.videorag.pipeline.mapper.ProcessTaskMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);
    private static final int BATCH_SIZE = 20;

    private final ProcessTaskMapper taskMapper;
    private final RocketMQTemplate rocketMQTemplate;

    @Value("${rocketmq.topic.pipeline:videorag-pipeline}")
    private String topic;

    @Value("${app.pipeline.lease.queue-timeout-seconds:120}")
    private long queueTimeoutSeconds;

    public TaskDispatcher(ProcessTaskMapper taskMapper, RocketMQTemplate rocketMQTemplate) {
        this.taskMapper = taskMapper;
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 5000)
    public void dispatch() {
        resetExpired();
        LocalDateTime now = LocalDateTime.now();
        List<ProcessTask> due = taskMapper.selectList(
                Wrappers.<ProcessTask>lambdaQuery()
                        .in(ProcessTask::getStatus, TaskStatus.PENDING.name(), TaskStatus.RETRYING.name())
                        .and(query -> query.isNull(ProcessTask::getNextRetryAt)
                                .or().le(ProcessTask::getNextRetryAt, now))
                        .orderByAsc(ProcessTask::getId)
                        .last("limit " + BATCH_SIZE));
        for (ProcessTask task : due) {
            dispatchOne(task);
        }
    }

    private void dispatchOne(ProcessTask task) {
        long currentVersion = task.getLeaseVersion() == null ? 0L : task.getLeaseVersion();
        long nextVersion = currentVersion + 1;
        LocalDateTime now = LocalDateTime.now();
        int queued = taskMapper.update(null, Wrappers.<ProcessTask>lambdaUpdate()
                .eq(ProcessTask::getId, task.getId())
                .in(ProcessTask::getStatus, TaskStatus.PENDING.name(), TaskStatus.RETRYING.name())
                .eq(ProcessTask::getLeaseVersion, currentVersion)
                .set(ProcessTask::getStatus, TaskStatus.QUEUED.name())
                .set(ProcessTask::getLeaseVersion, nextVersion)
                .set(ProcessTask::getLockedUntil, now.plusSeconds(queueTimeoutSeconds))
                .set(ProcessTask::getNextRetryAt, null)
                .set(ProcessTask::getWorkerId, null));
        if (queued == 0) {
            return;
        }

        JSONObject message = new JSONObject();
        message.put("taskId", task.getId());
        message.put("mediaId", task.getMediaId());
        message.put("stage", task.getStage());
        message.put("leaseVersion", nextVersion);
        try {
            rocketMQTemplate.syncSend(topic + ":" + task.getStage(), message.toJSONString());
        } catch (RuntimeException error) {
            log.warn("mq_send_failed taskId={} stage={}", task.getId(), task.getStage(), error);
            taskMapper.update(null, Wrappers.<ProcessTask>lambdaUpdate()
                    .eq(ProcessTask::getId, task.getId())
                    .eq(ProcessTask::getStatus, TaskStatus.QUEUED.name())
                    .eq(ProcessTask::getLeaseVersion, nextVersion)
                    .set(ProcessTask::getStatus, TaskStatus.RETRYING.name())
                    .set(ProcessTask::getLockedUntil, null)
                    .set(ProcessTask::getNextRetryAt, LocalDateTime.now().plusSeconds(30))
                    .set(ProcessTask::getErrorMessage, "消息投递失败"));
        }
    }

    private void resetExpired() {
        LocalDateTime now = LocalDateTime.now();
        int running = taskMapper.update(null, Wrappers.<ProcessTask>lambdaUpdate()
                .eq(ProcessTask::getStatus, TaskStatus.RUNNING.name())
                .lt(ProcessTask::getLockedUntil, now)
                .set(ProcessTask::getStatus, TaskStatus.RETRYING.name())
                .set(ProcessTask::getLockedUntil, null)
                .set(ProcessTask::getWorkerId, null)
                .set(ProcessTask::getNextRetryAt, now));
        int queued = taskMapper.update(null, Wrappers.<ProcessTask>lambdaUpdate()
                .eq(ProcessTask::getStatus, TaskStatus.QUEUED.name())
                .lt(ProcessTask::getLockedUntil, now)
                .set(ProcessTask::getStatus, TaskStatus.RETRYING.name())
                .set(ProcessTask::getLockedUntil, null)
                .set(ProcessTask::getNextRetryAt, now));
        if (running + queued > 0) {
            log.warn("pipeline_expired_reset running={} queued={}", running, queued);
        }
    }
}
