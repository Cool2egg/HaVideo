package com.videorag.common.ratelimit;

import com.videorag.common.BusinessException;
import com.videorag.common.ErrorCode;
import com.videorag.config.RateLimitProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RateLimitService {

    private static final String LUA = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local cost = tonumber(ARGV[4])

            if capacity < 1 then capacity = 1 end
            if refill <= 0 then refill = 0.001 end
            if cost < 1 then cost = 1 end

            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil then tokens = capacity end
            if ts == nil then ts = now end

            local elapsed = math.max(0, now - ts) / 1000.0
            tokens = math.min(capacity, tokens + elapsed * refill)

            local allowed = 0
            local retry = 0
            if tokens >= cost then
              tokens = tokens - cost
              allowed = 1
            else
              retry = math.ceil((cost - tokens) / refill * 1000)
            end

            redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(now))
            local ttl = math.max(1000, math.ceil(capacity / refill * 2000))
            redis.call('PEXPIRE', key, ttl)
            return {allowed, math.floor(tokens), retry}
            """;

    private static final DefaultRedisScript<List> SCRIPT =
            new DefaultRedisScript<>(LUA, List.class);

    private final StringRedisTemplate redisTemplate;

    public RateLimitService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void require(Long userId, String scope, RateLimitProperties.Bucket bucket) {
        int capacity = Math.max(1, bucket.getCapacity());
        double refill = bucket.getRefillPerSecond() > 0 ? bucket.getRefillPerSecond() : 1.0;
        String key = "rl:" + scope + ":" + userId;
        try {
            List<?> result = redisTemplate.execute(SCRIPT, List.of(key),
                    String.valueOf(capacity),
                    String.valueOf(refill),
                    String.valueOf(System.currentTimeMillis()),
                    "1");
            if (result == null || result.size() < 3) {
                throw new IllegalStateException("令牌桶返回结果不完整");
            }
            long allowed = ((Number) result.get(0)).longValue();
            long retryAfterMs = ((Number) result.get(2)).longValue();
            if (allowed != 1) {
                throw new RateLimitException(retryAfterMs);
            }
        } catch (RateLimitException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "限流器暂不可用，请稍后再试");
        }
    }
}
