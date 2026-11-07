package com.videorag.media.dto;

import java.time.LocalDateTime;

public record MediaDetail(
        Long id,
        String filename,
        String status,
        Integer progress,
        Long durationMs,
        String summaryJson,
        String transcriptText,
        String errorStage,
        String errorMessage,
        LocalDateTime createdAt
) {
}
