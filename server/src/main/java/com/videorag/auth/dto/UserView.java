package com.videorag.auth.dto;

public record UserView(
        Long id,
        String username,
        String nickname,
        String avatar,
        String role
) {
}
