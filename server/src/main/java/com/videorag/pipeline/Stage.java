package com.videorag.pipeline;

public enum Stage {
    PROBE,
    AUDIO,
    ASR,
    KEYFRAME,
    OCR,
    SEGMENT,
    CHUNK,
    INDEX,
    SUMMARY;

    public Stage next() {
        Stage[] stages = values();
        int index = ordinal() + 1;
        return index < stages.length ? stages[index] : null;
    }
}
