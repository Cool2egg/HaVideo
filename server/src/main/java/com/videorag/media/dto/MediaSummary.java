package com.videorag.media.dto;

import java.time.LocalDateTime;

public record MediaSummary(
        Long id,
        String filename,
        String status,
        Integer progress,
        Long durationMs,
        String coverUrl,
        String errorStage,
        LocalDateTime createdAt
) {
}
