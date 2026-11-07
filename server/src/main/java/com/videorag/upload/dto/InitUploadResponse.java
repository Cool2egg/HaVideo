package com.videorag.upload.dto;

import java.util.List;

public record InitUploadResponse(
        Long mediaId,
        String uploadId,
        List<Integer> uploadedParts,
        boolean dedup,
        String status
) {
}
