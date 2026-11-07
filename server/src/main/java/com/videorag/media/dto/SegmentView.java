package com.videorag.media.dto;

public record SegmentView(
        long startMs,
        long endMs,
        String transcript,
        String ocrTexts,
        String keyframeKey
) {
}
