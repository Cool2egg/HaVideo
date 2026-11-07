package com.videorag.upload.dto;

import java.time.LocalDateTime;
import java.util.List;

public record UploadStatusResponse(
        Long mediaId,
        String uploadId,
        int totalParts,
        List<Integer> uploadedParts,
        String status,
        LocalDateTime expiresAt
) {
}
