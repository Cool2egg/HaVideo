package com.videorag.pipeline;

import com.videorag.pipeline.entity.FailedTask;
import com.videorag.pipeline.mapper.FailedTaskMapper;
import org.springframework.stereotype.Service;

@Service
public class FailedTaskService {

    private final FailedTaskMapper failedTaskMapper;

    public FailedTaskService(FailedTaskMapper failedTaskMapper) {
        this.failedTaskMapper = failedTaskMapper;
    }

    public void record(Long mediaId, Stage stage, int attempt, Throwable error) {
        FailedTask task = new FailedTask();
        task.setMediaId(mediaId);
        task.setStage(stage.name());
        task.setAttempt(attempt);
        task.setErrorType(error.getClass().getSimpleName());
        task.setErrorMessage(truncate(error.getMessage()));
        task.setStatus("FAILED");
        failedTaskMapper.insert(task);
    }

    private String truncate(String message) {
        if (message == null) return "unknown error";
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
