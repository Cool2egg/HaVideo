package com.videorag.upload.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PrecheckRequest(
        @NotBlank(message = "文件指纹不能为空")
        @Pattern(regexp = "[a-fA-F0-9]{32,64}", message = "文件指纹格式不正确")
        String contentHash,

        @NotNull(message = "文件大小不能为空")
        @Min(value = 1, message = "文件大小不合法")
        Long size
) {
}
