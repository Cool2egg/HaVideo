package com.videorag.ai;

import com.videorag.ai.mapper.AiUsageMapper;
import com.videorag.common.usage.UsageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AiUsageService {

    private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);

    private final AiUsageMapper usageMapper;

    public AiUsageService(AiUsageMapper usageMapper) {
        this.usageMapper = usageMapper;
    }

    public void recordChat(long tokens) {
        record(tokens, 0);
    }

    public void recordEmbedding(long tokens) {
        record(tokens, 0);
    }

    public void recordRerank(long tokens) {
        record(tokens, 0);
    }

    public void recordAsr(long audioSeconds) {
        record(0, audioSeconds);
    }

    private void record(long tokens, long audioSeconds) {
        Long userId = UsageContext.userId();
        if (userId == null) {
            return;
        }
        try {
            usageMapper.upsert(userId, Math.max(0, tokens), Math.max(0, audioSeconds));
        } catch (RuntimeException error) {
            log.warn("ai_usage_record_failed userId={} tokens={} seconds={}",
                    userId, tokens, audioSeconds, error);
        }
    }
}
