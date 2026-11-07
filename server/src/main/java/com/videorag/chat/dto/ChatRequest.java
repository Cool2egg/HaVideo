package com.videorag.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotNull(message = "会话不能为空") Long sessionId,
        @NotBlank(message = "问题不能为空")
        @Size(max = 500, message = "问题不能超过 500 字")
        String question
) {
}
