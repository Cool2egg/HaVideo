package com.videorag.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videorag.pipeline.entity.ProcessTask;
import com.videorag.pipeline.mapper.ProcessTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final ProcessTaskMapper taskMapper;

    public TaskService(ProcessTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    public void createIfAbsent(Long mediaId, Stage stage) {
        ProcessTask task = new ProcessTask();
        task.setMediaId(mediaId);
        task.setStage(stage.name());
        task.setStatus(TaskStatus.PENDING.name());
        task.setAttempt(0);
        task.setNextRetryAt(LocalDateTime.now());
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException ignored) {
            // 同一阶段只允许一条任务，重复创建即幂等命中。
        }
    }

    public ProcessTask findByMediaAndStage(Long mediaId, Stage stage) {
        LambdaQueryWrapper<ProcessTask> query = Wrappers.lambdaQuery(ProcessTask.class)
                .eq(ProcessTask::getMediaId, mediaId)
                .eq(ProcessTask::getStage, stage.name());
        return taskMapper.selectOne(query);
    }

    public void markSuccess(ProcessTask task) {
        task.setStatus(TaskStatus.SUCCESS.name());
        task.setLockedUntil(null);
        task.setErrorMessage(null);
        task.setErrorCode(null);
        taskMapper.update(null, Wrappers.<ProcessTask>lambdaUpdate()
                .eq(ProcessTask::getId, task.getId())
                .set(ProcessTask::getStatus, TaskStatus.SUCCESS.name())
                .set(ProcessTask::getLockedUntil, null)
                .set(ProcessTask::getErrorMessage, null)
                .set(ProcessTask::getErrorCode, null)
                .set(ProcessTask::getNextRetryAt, null));
    }

    public void markRetry(ProcessTask task, Throwable error, int maxAttempts) {
        int attempt = task.getAttempt() == null ? 0 : task.getAttempt();
        task.setAttempt(attempt + 1);
        task.setLockedUntil(null);
        task.setErrorMessage(truncate(error.getMessage()));

        boolean exhausted = task.getAttempt() >= maxAttempts;
        if (exhausted) {
            task.setStatus(TaskStatus.FAILED.name());
            task.setNextRetryAt(null);
        } else {
            task.setStatus(TaskStatus.RETRYING.name());
            long delaySeconds = Math.min(300, 5L << Math.min(6, task.getAttempt()));
            task.setNextRetryAt(LocalDateTime.now().plusSeconds(delaySeconds));
        }

        taskMapper.update(null, Wrappers.<ProcessTask>lambdaUpdate()
                .eq(ProcessTask::getId, task.getId())
                .set(ProcessTask::getStatus, task.getStatus())
                .set(ProcessTask::getAttempt, task.getAttempt())
                .set(ProcessTask::getLockedUntil, null)
                .set(ProcessTask::getErrorMessage, task.getErrorMessage())
                .set(ProcessTask::getNextRetryAt, task.getNextRetryAt()));
        log.warn("task_failed mediaId={} stage={} attempt={} error={}",
                task.getMediaId(), task.getStage(), task.getAttempt(), error.getMessage());
    }

    public void reset(ProcessTask task) {
        task.setStatus(TaskStatus.PENDING.name());
        task.setLockedUntil(null);
        task.setNextRetryAt(LocalDateTime.now());
        task.setErrorMessage(null);
        taskMapper.update(null, Wrappers.<ProcessTask>lambdaUpdate()
                .eq(ProcessTask::getId, task.getId())
                .set(ProcessTask::getStatus, TaskStatus.PENDING.name())
                .set(ProcessTask::getLockedUntil, null)
                .set(ProcessTask::getNextRetryAt, LocalDateTime.now())
                .set(ProcessTask::getErrorCode, null)
                .set(ProcessTask::getErrorMessage, null));
    }

    private String truncate(String message) {
        if (message == null) return "unknown error";
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
