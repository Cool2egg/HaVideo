package com.videorag.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_chunks")
public class KnowledgeChunk {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long mediaId;
    private Integer chunkIndex;
    private Long startMs;
    private Long endMs;
    private String summary;
    private String keywords;
    private String qdrantPointId;
    private String esDocId;
    private LocalDateTime createdAt;
}
