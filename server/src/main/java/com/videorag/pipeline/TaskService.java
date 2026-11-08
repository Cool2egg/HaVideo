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
        task.setLeaseVersion(0L);
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

    public boolean claim(Long taskId, long leaseVersion, String workerId, LocalDateTime leasedUntil) {
        return taskMapper.update(null, Wrappers.<ProcessTask>lambdaUpdate()
                .eq(ProcessTask::getId, taskId)
                .eq(ProcessTask::getStatus, TaskStatus.QUEUED.name())
                .eq(ProcessTask::getLeaseVersion, leaseVersion)
                .set(ProcessTask::getStatus, TaskStatus.RUNNING.name())
                .set(ProcessTask::getWorkerId, workerId)
                .set(ProcessTask::getLockedUntil, leasedUntil)) == 1;
    }

    public boolean renewLease(Long taskId, long leaseVersion, LocalDateTime leasedUntil) {
        return taskMapper.update(null, Wrappers.<ProcessTask>lambdaUpdate()
                .eq(ProcessTask::getId, taskId)
                .eq(ProcessTask::getStatus, TaskStatus.RUNNING.name())
                .eq(ProcessTask::getLeaseVersion, leaseVersion)
                .set(ProcessTask::getLockedUntil, leasedUntil)) == 1;
    }

    public boolean markSuccess(ProcessTask task, long leaseVersion) {
        int updated = taskMapper.update(null, Wrappers.<ProcessTask>lambdaUpdate()
                .eq(ProcessTask::getId, task.getId())
                .eq(ProcessTask::getStatus, TaskStatus.RUNNING.name())
                .eq(ProcessTask::getLeaseVersion, leaseVersion)
                .set(ProcessTask::getStatus, TaskStatus.SUCCESS.name())
                .set(ProcessTask::getLockedUntil, null)
                .set(ProcessTask::getErrorMessage, null)
                .set(ProcessTask::getErrorCode, null)
                .set(ProcessTask::getNextRetryAt, null));
        if (updated == 0) {
            return false;
        }
        task.setStatus(TaskStatus.SUCCESS.name());
        task.setLockedUntil(null);
        task.setErrorMessage(null);
        task.setErrorCode(null);
        task.setNextRetryAt(null);
        return true;
    }

    public boolean markRetry(ProcessTask task, Throwable error, int maxAttempts, long leaseVersion) {
        int attempt = task.getAttempt() == null ? 0 : task.getAttempt();
        int nextAttempt = attempt + 1;
        boolean exhausted = nextAttempt >= maxAttempts;
        String nextStatus = exhausted ? TaskStatus.FAILED.name() : TaskStatus.RETRYING.name();
        LocalDateTime nextRetryAt = exhausted
                ? null
                : LocalDateTime.now().plusSeconds(Math.min(300, 5L << Math.min(6, nextAttempt)));
        String message = truncate(error.getMessage());

        int updated = taskMapper.update(null, Wrappers.<ProcessTask>lambdaUpdate()
                .eq(ProcessTask::getId, task.getId())
                .eq(ProcessTask::getStatus, TaskStatus.RUNNING.name())
                .eq(ProcessTask::getLeaseVersion, leaseVersion)
                .set(ProcessTask::getStatus, nextStatus)
                .set(ProcessTask::getAttempt, nextAttempt)
                .set(ProcessTask::getLockedUntil, null)
                .set(ProcessTask::getWorkerId, null)
                .set(ProcessTask::getErrorMessage, message)
                .set(ProcessTask::getNextRetryAt, nextRetryAt));
        if (updated == 0) {
            return false;
        }
        task.setStatus(nextStatus);
        task.setAttempt(nextAttempt);
        task.setLockedUntil(null);
        task.setWorkerId(null);
        task.setErrorMessage(message);
        task.setNextRetryAt(nextRetryAt);
        log.warn("task_failed mediaId={} stage={} attempt={} error={}",
                task.getMediaId(), task.getStage(), nextAttempt, error.getMessage());
        return true;
    }

    public void reset(ProcessTask task) {
        task.setStatus(TaskStatus.PENDING.name());
        task.setLockedUntil(null);
        task.setWorkerId(null);
        task.setNextRetryAt(LocalDateTime.now());
        task.setErrorMessage(null);
        taskMapper.update(null, Wrappers.<ProcessTask>lambdaUpdate()
                .eq(ProcessTask::getId, task.getId())
                .set(ProcessTask::getStatus, TaskStatus.PENDING.name())
                .set(ProcessTask::getLockedUntil, null)
                .set(ProcessTask::getWorkerId, null)
                .set(ProcessTask::getNextRetryAt, LocalDateTime.now())
                .set(ProcessTask::getErrorCode, null)
                .set(ProcessTask::getErrorMessage, null));
    }

    private String truncate(String message) {
        if (message == null) return "unknown error";
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
