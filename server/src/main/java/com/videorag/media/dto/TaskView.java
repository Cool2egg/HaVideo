package com.videorag.media.dto;

import java.time.LocalDateTime;

public record TaskView(
        Long id,
        Long mediaId,
        String filename,
        String stage,
        String status,
        Integer attempt,
        String errorMessage,
        LocalDateTime updatedAt
) {
}
