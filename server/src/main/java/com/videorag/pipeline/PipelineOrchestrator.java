package com.videorag.pipeline;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videorag.media.MediaService;
import com.videorag.pipeline.entity.ProcessTask;
import com.videorag.pipeline.mapper.ProcessTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class PipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);
    private static final int MAX_ATTEMPTS = 3;

    private static final Map<Stage, Integer> WEIGHTS = new EnumMap<>(Stage.class);

    static {
        WEIGHTS.put(Stage.PROBE, 5);
        WEIGHTS.put(Stage.AUDIO, 10);
        WEIGHTS.put(Stage.ASR, 30);
        WEIGHTS.put(Stage.KEYFRAME, 5);
        WEIGHTS.put(Stage.OCR, 10);
        WEIGHTS.put(Stage.SEGMENT, 5);
        WEIGHTS.put(Stage.CHUNK, 10);
        WEIGHTS.put(Stage.INDEX, 10);
        WEIGHTS.put(Stage.SUMMARY, 15);
    }

    private final TaskService taskService;
    private final ProcessTaskMapper taskMapper;
    private final MediaService mediaService;
    private final FailedTaskService failedTaskService;
    private final TaskEventService taskEventService;

    public PipelineOrchestrator(TaskService taskService,
                                ProcessTaskMapper taskMapper,
                                MediaService mediaService,
                                FailedTaskService failedTaskService,
                                TaskEventService taskEventService) {
        this.taskService = taskService;
        this.taskMapper = taskMapper;
        this.mediaService = mediaService;
        this.failedTaskService = failedTaskService;
        this.taskEventService = taskEventService;
    }

    public void success(ProcessTask task, long leaseVersion) {
        Stage stage = Stage.valueOf(task.getStage());
        if (!taskService.markSuccess(task, leaseVersion)) {
            log.warn("stage_success_ignored mediaId={} stage={} leaseVersion={}",
                    task.getMediaId(), stage, leaseVersion);
            return;
        }

        Stage next = stage.next();
        if (next != null) {
            taskService.createIfAbsent(task.getMediaId(), next);
            int progress = completedProgress(task.getMediaId());
            mediaService.updateProgress(task.getMediaId(), "PROCESSING", progress);
            publish(task.getMediaId(), "PROCESSING", progress, stage.name(),
                    "阶段完成：" + stage.name());
        } else {
            mediaService.updateProgress(task.getMediaId(), "READY", 100);
            publish(task.getMediaId(), "READY", 100, stage.name(), "处理完成");
        }
        log.info("stage_success mediaId={} stage={} leaseVersion={}",
                task.getMediaId(), stage, leaseVersion);
    }

    public void failure(ProcessTask task, Throwable error, long leaseVersion) {
        Stage stage = Stage.valueOf(task.getStage());
        if (!taskService.markRetry(task, error, MAX_ATTEMPTS, leaseVersion)) {
            log.warn("stage_failure_ignored mediaId={} stage={} leaseVersion={}",
                    task.getMediaId(), stage, leaseVersion);
            return;
        }
        int progress = completedProgress(task.getMediaId());

        if (TaskStatus.FAILED.name().equals(task.getStatus())) {
            failedTaskService.record(task.getMediaId(), stage,
                    task.getAttempt() == null ? 0 : task.getAttempt(), error);
            mediaService.markFailed(task.getMediaId(), stage.name(), error.getMessage());
            publish(task.getMediaId(), "FAILED", progress, stage.name(),
                    "处理失败：" + safeMessage(error));
        } else {
            mediaService.updateProgress(task.getMediaId(), "PROCESSING", progress);
            publish(task.getMediaId(), "RETRYING", progress, stage.name(),
                    "第 " + task.getAttempt() + " 次失败，准备重试");
        }
        log.warn("stage_failure mediaId={} stage={} attempt={} error={}",
                task.getMediaId(), stage, task.getAttempt(), error.getMessage());
    }

    public int completedProgress(Long mediaId) {
        List<ProcessTask> tasks = taskMapper.selectList(
                Wrappers.<ProcessTask>lambdaQuery()
                        .eq(ProcessTask::getMediaId, mediaId)
                        .eq(ProcessTask::getStatus, TaskStatus.SUCCESS.name()));
        int total = tasks.stream()
                .map(task -> WEIGHTS.getOrDefault(Stage.valueOf(task.getStage()), 0))
                .mapToInt(Integer::intValue)
                .sum();
        return Math.min(99, total);
    }

    private void publish(Long mediaId, String status, int progress, String stage, String message) {
        taskEventService.publish(mediaId,
                new TaskEventService.ProgressEvent(mediaId, status, progress, stage, message));
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.length() <= 200 ? message : message.substring(0, 200);
    }
}
