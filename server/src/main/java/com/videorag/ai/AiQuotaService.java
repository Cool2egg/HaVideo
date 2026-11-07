package com.videorag.ai;

import com.videorag.common.BusinessException;
import com.videorag.common.ErrorCode;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AiQuotaService {

    private final RedissonClient redissonClient;

    @Value("${app.limit.ai.user-per-minute:20}")
    private long userPerMinute;

    @Value("${app.limit.ai.global-per-minute:60}")
    private long globalPerMinute;

    public AiQuotaService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public void requireQuota(Long userId) {
        RRateLimiter userLimiter = redissonClient.getRateLimiter("limit:ai:user:" + userId);
        userLimiter.trySetRate(RateType.OVERALL, Math.max(1, userPerMinute), 1,
                RateIntervalUnit.MINUTES);
        if (!userLimiter.tryAcquire()) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "AI 请求过于频繁，请稍后再试");
        }

        RRateLimiter globalLimiter = redissonClient.getRateLimiter("limit:ai:global");
        globalLimiter.trySetRate(RateType.OVERALL, Math.max(1, globalPerMinute), 1,
                RateIntervalUnit.MINUTES);
        if (!globalLimiter.tryAcquire()) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "系统繁忙，请稍后再试");
        }
    }
}
