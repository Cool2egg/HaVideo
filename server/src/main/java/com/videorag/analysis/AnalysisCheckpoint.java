package com.videorag.analysis;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("analysis_checkpoints")
public class AnalysisCheckpoint {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long mediaId;
    private String goalDigest;
    private String mode;
    private String checkpointKey;
    private String payload;
    private LocalDateTime updatedAt;
}
