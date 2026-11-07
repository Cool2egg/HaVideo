package com.videorag.analysis;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class CheckpointService {

    private final AnalysisCheckpointMapper checkpointMapper;

    public CheckpointService(AnalysisCheckpointMapper checkpointMapper) {
        this.checkpointMapper = checkpointMapper;
    }

    public String load(Long mediaId, String digest, AnalysisMode mode, String key) {
        AnalysisCheckpoint checkpoint = checkpointMapper.selectOne(
                Wrappers.<AnalysisCheckpoint>lambdaQuery()
                        .eq(AnalysisCheckpoint::getMediaId, mediaId)
                        .eq(AnalysisCheckpoint::getGoalDigest, digest)
                        .eq(AnalysisCheckpoint::getMode, mode.name())
                        .eq(AnalysisCheckpoint::getCheckpointKey, key)
                        .last("limit 1"));
        return checkpoint == null ? null : checkpoint.getPayload();
    }

    public void save(Long mediaId, String digest, AnalysisMode mode, String key, String payload) {
        AnalysisCheckpoint existing = checkpointMapper.selectOne(
                Wrappers.<AnalysisCheckpoint>lambdaQuery()
                        .eq(AnalysisCheckpoint::getMediaId, mediaId)
                        .eq(AnalysisCheckpoint::getGoalDigest, digest)
                        .eq(AnalysisCheckpoint::getMode, mode.name())
                        .eq(AnalysisCheckpoint::getCheckpointKey, key)
                        .last("limit 1"));
        if (existing == null) {
            AnalysisCheckpoint checkpoint = new AnalysisCheckpoint();
            checkpoint.setMediaId(mediaId);
            checkpoint.setGoalDigest(digest);
            checkpoint.setMode(mode.name());
            checkpoint.setCheckpointKey(key);
            checkpoint.setPayload(payload);
            checkpointMapper.insert(checkpoint);
        } else {
            existing.setPayload(payload);
            checkpointMapper.updateById(existing);
        }
    }

    public static String digest(String goal, AnalysisMode mode) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest((goal.trim() + ":" + mode.name())
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException("无法计算分析目标摘要", error);
        }
    }
}
