package com.videorag.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videorag.admin.dto.UsageView;
import com.videorag.ai.AiUsage;
import com.videorag.ai.mapper.AiUsageMapper;
import com.videorag.auth.AuthService;
import com.videorag.auth.entity.User;
import com.videorag.auth.mapper.UserMapper;
import com.videorag.common.BusinessException;
import com.videorag.common.ErrorCode;
import com.videorag.media.MediaService;
import com.videorag.media.dto.FailedTaskView;
import com.videorag.media.dto.TaskView;
import com.videorag.media.entity.MediaFile;
import com.videorag.media.mapper.MediaFileMapper;
import com.videorag.pipeline.TaskStatus;
import com.videorag.pipeline.entity.FailedTask;
import com.videorag.pipeline.entity.ProcessTask;
import com.videorag.pipeline.mapper.FailedTaskMapper;
import com.videorag.pipeline.mapper.ProcessTaskMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdminService {

    private final AuthService authService;
    private final ProcessTaskMapper taskMapper;
    private final FailedTaskMapper failedTaskMapper;
    private final MediaFileMapper mediaFileMapper;
    private final MediaService mediaService;
    private final AiUsageMapper aiUsageMapper;
    private final UserMapper userMapper;

    public AdminService(AuthService authService,
                        ProcessTaskMapper taskMapper,
                        FailedTaskMapper failedTaskMapper,
                        MediaFileMapper mediaFileMapper,
                        MediaService mediaService,
                        AiUsageMapper aiUsageMapper,
                        UserMapper userMapper) {
        this.authService = authService;
        this.taskMapper = taskMapper;
        this.failedTaskMapper = failedTaskMapper;
        this.mediaFileMapper = mediaFileMapper;
        this.mediaService = mediaService;
        this.aiUsageMapper = aiUsageMapper;
        this.userMapper = userMapper;
    }

    public List<TaskView> tasks(Long userId, String status) {
        authService.requireAdmin(userId);
        var query = Wrappers.<ProcessTask>lambdaQuery()
                .orderByDesc(ProcessTask::getUpdatedAt)
                .last("limit 100");
        if (status != null && !status.isBlank()) {
            query.eq(ProcessTask::getStatus, status.toUpperCase());
        }
        List<ProcessTask> tasks = taskMapper.selectList(query);
        Map<Long, String> filenames = filenames(tasks.stream()
                .map(ProcessTask::getMediaId).collect(Collectors.toSet()));
        return tasks.stream()
                .map(task -> new TaskView(task.getId(), task.getMediaId(),
                        filenames.getOrDefault(task.getMediaId(), "已删除视频"),
                        task.getStage(), task.getStatus(), task.getAttempt(),
                        task.getErrorMessage(), task.getUpdatedAt()))
                .toList();
    }

    public List<FailedTaskView> failedTasks(Long userId) {
        authService.requireAdmin(userId);
        List<FailedTask> tasks = failedTaskMapper.selectList(
                Wrappers.<FailedTask>lambdaQuery()
                        .eq(FailedTask::getStatus, "FAILED")
                        .orderByDesc(FailedTask::getCreatedAt)
                        .last("limit 100"));
        Map<Long, String> filenames = filenames(tasks.stream()
                .map(FailedTask::getMediaId).collect(Collectors.toSet()));
        return tasks.stream()
                .map(task -> new FailedTaskView(task.getId(), task.getMediaId(),
                        filenames.getOrDefault(task.getMediaId(), "已删除视频"),
                        task.getStage(), task.getAttempt(), task.getErrorType(),
                        task.getErrorMessage(), task.getCreatedAt()))
                .toList();
    }

    public List<UsageView> usage(Long adminUserId) {
        authService.requireAdmin(adminUserId);
        List<AiUsage> rows = aiUsageMapper.selectList(
                Wrappers.<AiUsage>lambdaQuery()
                        .orderByDesc(AiUsage::getStatDate)
                        .orderByAsc(AiUsage::getUserId)
                        .last("limit 200"));
        Set<Long> userIds = rows.stream().map(AiUsage::getUserId).collect(Collectors.toSet());
        Map<Long, String> usernames = userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getUsername,
                                (left, right) -> left));
        return rows.stream()
                .map(row -> new UsageView(row.getUserId(),
                        usernames.getOrDefault(row.getUserId(), "已删除用户"),
                        row.getStatDate(), row.getCalls(), row.getTokens(),
                        row.getAudioSeconds()))
                .toList();
    }

    public void requeue(Long userId, Long taskId) {
        authService.requireAdmin(userId);
        ProcessTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        taskMapper.update(null, Wrappers.<ProcessTask>lambdaUpdate()
                .eq(ProcessTask::getId, taskId)
                .set(ProcessTask::getStatus, TaskStatus.PENDING.name())
                .set(ProcessTask::getAttempt, 0)
                .set(ProcessTask::getLockedUntil, null)
                .set(ProcessTask::getWorkerId, null)
                .set(ProcessTask::getNextRetryAt, LocalDateTime.now())
                .set(ProcessTask::getErrorCode, null)
                .set(ProcessTask::getErrorMessage, null));
        mediaService.updateProgress(task.getMediaId(), "PROCESSING", 0);
        failedTaskMapper.update(null, Wrappers.<FailedTask>lambdaUpdate()
                .eq(FailedTask::getMediaId, task.getMediaId())
                .eq(FailedTask::getStage, task.getStage())
                .eq(FailedTask::getStatus, "FAILED")
                .set(FailedTask::getStatus, "REPLAYED"));
    }

    private Map<Long, String> filenames(Set<Long> mediaIds) {
        if (mediaIds.isEmpty()) return Map.of();
        return mediaFileMapper.selectBatchIds(mediaIds).stream()
                .collect(Collectors.toMap(MediaFile::getId, MediaFile::getFilename,
                        (left, right) -> left));
    }
}
