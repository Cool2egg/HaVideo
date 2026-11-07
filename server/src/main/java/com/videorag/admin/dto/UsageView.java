package com.videorag.admin.dto;

import java.time.LocalDate;

public record UsageView(
        Long userId,
        String username,
        LocalDate statDate,
        Integer calls,
        Long tokens,
        Long audioSeconds
) {
}
