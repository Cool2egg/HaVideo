package com.videorag.common.ratelimit;

import com.videorag.common.BusinessException;
import com.videorag.common.ErrorCode;

public class RateLimitException extends BusinessException {

    private final long retryAfterSeconds;

    public RateLimitException(long retryAfterMs) {
        super(ErrorCode.RATE_LIMITED, message(retryAfterMs));
        this.retryAfterSeconds = seconds(retryAfterMs);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static String message(long retryAfterMs) {
        return "请求过于频繁，请 " + seconds(retryAfterMs) + " 秒后重试";
    }

    private static long seconds(long retryAfterMs) {
        return Math.max(1, (Math.max(0, retryAfterMs) + 999) / 1000);
    }
}
