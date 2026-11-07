package com.videorag.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("video_segments")
public class VideoSegment {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long mediaId;
    private Long startMs;
    private Long endMs;
    private String transcript;
    private String ocrTexts;
    private String source;
    private String keyframeKey;
    private LocalDateTime createdAt;
}
