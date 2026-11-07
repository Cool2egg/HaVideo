package com.videorag.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AuthRequest(
        @NotBlank(message = "不能为空")
        @Pattern(regexp = "[A-Za-z0-9_]{3,32}", message = "需为 3-32 位字母、数字或下划线")
        String username,

        @NotBlank(message = "不能为空")
        @Size(min = 8, max = 128, message = "长度需为 8-128 位")
        String password,

        @Size(max = 50, message = "不能超过 50 个字符")
        String nickname
) {
}
