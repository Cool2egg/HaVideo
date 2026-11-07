package com.videorag.media.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("media_files")
public class MediaFile {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String filename;
    private String objectKey;
    private String contentHash;
    private Long size;
    private String status;
    private Integer progress;
    private Long durationMs;
    private String coverKey;
    private String summaryJson;
    private String transcriptText;
    private String errorStage;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
