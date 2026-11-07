package com.videorag.common.usage;

public final class UsageContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private UsageContext() {
    }

    public static void set(Long userId) {
        USER_ID.set(userId);
    }

    public static Long userId() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}
