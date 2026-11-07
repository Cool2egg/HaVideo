package com.videorag.media.dto;

import java.time.LocalDateTime;

public record FailedTaskView(
        Long id,
        Long mediaId,
        String filename,
        String stage,
        Integer attempt,
        String errorType,
        String errorMessage,
        LocalDateTime createdAt
) {
}
