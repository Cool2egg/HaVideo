package com.videorag.rag;

import java.util.List;

public record Evidence(
        String ref,
        Long mediaId,
        String title,
        long startMs,
        long endMs,
        String snippet,
        String transcript,
        List<String> ocrTexts,
        String source,
        double score
) {
    public SourceItem toSourceItem() {
        return new SourceItem(ref, mediaId, title, startMs, endMs, snippet, source, score);
    }
}
