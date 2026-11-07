package com.videorag.chat.dto;

import java.time.LocalDateTime;

public record MessageView(Long id, String role, String content,
                          String sourcesJson, LocalDateTime createdAt) {
}
