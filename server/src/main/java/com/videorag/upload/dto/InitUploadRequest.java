package com.videorag.upload.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record InitUploadRequest(
        @NotBlank(message = "文件名不能为空")
        @Size(max = 255, message = "文件名过长")
        String filename,

        @NotNull(message = "文件大小不能为空")
        @Min(value = 1, message = "文件大小不合法")
        Long size,

        @NotBlank(message = "文件指纹不能为空")
        @Pattern(regexp = "[a-fA-F0-9]{32,64}", message = "文件指纹格式不正确")
        String contentHash,

        @NotNull(message = "分片数不能为空")
        @Min(value = 1, message = "分片数不合法")
        @Max(value = 10000, message = "分片数过多")
        Integer totalParts,

        @Min(value = 5242880, message = "分片不能小于 5MB")
        Long chunkSize
) {
}
