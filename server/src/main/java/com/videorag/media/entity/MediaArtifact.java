package com.videorag.media.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("media_artifacts")
public class MediaArtifact {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long mediaId;
    private String type;
    private Integer seq;
    private String objectKey;
    private Long startMs;
    private Long endMs;
    private LocalDateTime createdAt;
}
