package com.videorag.upload.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("upload_sessions")
public class UploadSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long mediaId;
    private Long userId;
    private String uploadId;
    private Long chunkSize;
    private Integer totalParts;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
