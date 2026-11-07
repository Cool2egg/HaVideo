package com.videorag.common;

public enum ErrorCode {
    OK(0),
    INVALID_ARGUMENT(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    CONFLICT(409),
    RATE_LIMITED(429),
    INTERNAL_ERROR(500),
    SERVICE_UNAVAILABLE(503);

    private final int code;

    ErrorCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
