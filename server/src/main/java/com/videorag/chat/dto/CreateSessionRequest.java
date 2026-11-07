package com.videorag.chat.dto;

import jakarta.validation.constraints.Size;

public record CreateSessionRequest(
        Long mediaId,
        @Size(max = 120, message = "标题过长") String title
) {
}
