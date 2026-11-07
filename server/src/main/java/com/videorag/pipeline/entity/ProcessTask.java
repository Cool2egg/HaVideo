package com.videorag.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("process_tasks")
public class ProcessTask {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long mediaId;
    private String stage;
    private String status;
    private Integer attempt;
    private LocalDateTime lockedUntil;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime nextRetryAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
