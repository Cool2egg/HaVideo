package com.videorag.pipeline;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
    private static final int LEASE_MINUTES = 15;

    private final ProcessTaskMapper taskMapper;
    private final RocketMQTemplate rocketMQTemplate;

    @Value("${rocketmq.topic.pipeline:videorag-pipeline}")
    private String topic;

    public TaskDispatcher(ProcessTaskMapper taskMapper, RocketMQTemplate rocketMQTemplate) {
        this.taskMapper = taskMapper;
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 5000)
    public void dispatch() {
        resetStaleLeases();
        LocalDateTime now = LocalDateTime.now();
        List<ProcessTask> due = taskMapper.selectList(
                Wrappers.<ProcessTask>lambdaQuery()
                        .in(ProcessTask::getStatus, TaskStatus.PENDING.name(), TaskStatus.RETRYING.name())
                        .and(query -> query.isNull(ProcessTask::getNextRetryAt)
                                .or().le(ProcessTask::getNextRetryAt, now))
                        .orderByAsc(ProcessTask::getId)
                        .last("limit 20"));
        for (ProcessTask task : due) {
            dispatchOne(task);
        }
    }

    private void dispatchOne(ProcessTask task) {
        LambdaUpdateWrapper<ProcessTask> lock = Wrappers.<ProcessTask>lambdaUpdate()
                .eq(ProcessTask::getId, task.getId())
                .in(ProcessTask::getStatus, TaskStatus.PENDING.name(), TaskStatus.RETRYING.name())
                .set(ProcessTask::getStatus, TaskStatus.RUNNING.name())
                .set(ProcessTask::getLockedUntil, LocalDateTime.now().plusMinutes(LEASE_MINUTES));
        if (taskMapper.update(null, lock) == 0) {
            return;
        }

        JSONObject message = new JSONObject();
        message.put("taskId", task.getId());
        message.put("mediaId", task.getMediaId());
        message.put("stage", task.getStage());
        try {
            rocketMQTemplate.syncSend(topic + ":" + task.getStage(), message.toJSONString());
        } catch (RuntimeException error) {
            log.warn("mq_send_failed taskId={} stage={}", task.getId(), task.getStage(), error);
            ProcessTask reset = new ProcessTask();
            reset.setId(task.getId());
            reset.setStatus(TaskStatus.RETRYING.name());
            reset.setLockedUntil(null);
            reset.setNextRetryAt(LocalDateTime.now().plusSeconds(30));
            reset.setErrorMessage("消息投递失败");
            taskMapper.updateById(reset);
        }
    }

    private void resetStaleLeases() {
        LambdaUpdateWrapper<ProcessTask> update = Wrappers.<ProcessTask>lambdaUpdate()
                .eq(ProcessTask::getStatus, TaskStatus.RUNNING.name())
                .lt(ProcessTask::getLockedUntil, LocalDateTime.now())
                .set(ProcessTask::getStatus, TaskStatus.RETRYING.name())
                .set(ProcessTask::getLockedUntil, null)
                .set(ProcessTask::getNextRetryAt, LocalDateTime.now());
        int reset = taskMapper.update(null, update);
        if (reset > 0) {
            log.warn("stale_tasks_reset count={}", reset);
        }
    }
}
