package com.videorag.chat.dto;

import java.time.LocalDateTime;

public record SessionView(Long id, Long mediaId, String title, LocalDateTime updatedAt) {
}
