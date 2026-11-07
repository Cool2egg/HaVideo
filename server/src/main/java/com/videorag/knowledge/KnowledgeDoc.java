package com.videorag.knowledge;

public record KnowledgeDoc(
        String id,
        String text,
        String title,
        Long userId,
        Long mediaId,
        long startMs,
        long endMs,
        String keywords
) {
}
