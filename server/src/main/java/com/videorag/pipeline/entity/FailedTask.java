package com.videorag.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("failed_tasks")
public class FailedTask {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long mediaId;
    private String stage;
    private Integer attempt;
    private String errorType;
    private String errorMessage;
    private String status;
    private LocalDateTime createdAt;
}
