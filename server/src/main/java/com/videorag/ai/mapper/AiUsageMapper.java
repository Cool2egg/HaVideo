package com.videorag.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videorag.ai.AiUsage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiUsageMapper extends BaseMapper<AiUsage> {

    @Insert("""
            INSERT INTO ai_usage (user_id, stat_date, calls, tokens, audio_seconds)
            VALUES (#{userId}, CURDATE(), 1, #{tokens}, #{audioSeconds})
            ON DUPLICATE KEY UPDATE
              calls = calls + 1,
              tokens = tokens + #{tokens},
              audio_seconds = audio_seconds + #{audioSeconds}
            """)
    int upsert(@Param("userId") Long userId,
               @Param("tokens") long tokens,
               @Param("audioSeconds") long audioSeconds);
}
