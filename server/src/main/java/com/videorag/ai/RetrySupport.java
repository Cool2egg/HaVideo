package com.videorag.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class RetrySupport {

    private static final Logger log = LoggerFactory.getLogger(RetrySupport.class);

    private RetrySupport() {
    }

    public static <T> T call(String action, int maxAttempts, Supplier<T> supplier) {
        AiException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return supplier.get();
            } catch (AiException error) {
                last = error;
                if (!error.isRetryable() || attempt == maxAttempts) {
                    throw error;
                }
                long delay = (long) Math.min(8000, 500 * Math.pow(2, attempt - 1))
                        + ThreadLocalRandom.current().nextLong(0, 300);
                log.warn("ai_call_retry action={} attempt={} delayMs={} error={}",
                        action, attempt, delay, error.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AiException("AI 调用被中断", false, interrupted);
                }
            }
        }
        throw last == null ? new AiException("AI 调用失败", false) : last;
    }
}
