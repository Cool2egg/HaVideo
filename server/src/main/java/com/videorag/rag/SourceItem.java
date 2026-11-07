package com.videorag.rag;

public record SourceItem(
        String ref,
        Long mediaId,
        String title,
        long startMs,
        long endMs,
        String snippet,
        String source,
        double score
) {
}
