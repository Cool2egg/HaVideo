package com.videorag.pipeline.handler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videorag.knowledge.entity.VideoSegment;
import com.videorag.knowledge.mapper.VideoSegmentMapper;
import com.videorag.media.MediaService;
import com.videorag.media.entity.MediaArtifact;
import com.videorag.media.entity.MediaFile;
import com.videorag.media.mapper.MediaArtifactMapper;
import com.videorag.media.mapper.MediaFileMapper;
import com.videorag.pipeline.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class SegmentHandler implements StageHandler {

    private static final Logger log = LoggerFactory.getLogger(SegmentHandler.class);
    private static final long WINDOW_MS = 30_000L;

    private final MediaFileMapper mediaFileMapper;
    private final VideoSegmentMapper segmentMapper;
    private final MediaArtifactMapper artifactMapper;
    private final MediaService mediaService;

    public SegmentHandler(MediaFileMapper mediaFileMapper,
                          VideoSegmentMapper segmentMapper,
                          MediaArtifactMapper artifactMapper,
                          MediaService mediaService) {
        this.mediaFileMapper = mediaFileMapper;
        this.segmentMapper = segmentMapper;
        this.artifactMapper = artifactMapper;
        this.mediaService = mediaService;
    }

    @Override
    public Stage stage() {
        return Stage.SEGMENT;
    }

    @Override
    public void handle(Long mediaId) {
        mediaService.updateProgress(mediaId, "PROCESSING", 61);
        List<VideoSegment> raw = segmentMapper.selectList(
                Wrappers.<VideoSegment>lambdaQuery()
                        .eq(VideoSegment::getMediaId, mediaId)
                        .in(VideoSegment::getSource, List.of("ASR", "OCR"))
                        .orderByAsc(VideoSegment::getStartMs));
        if (raw.isEmpty()) {
            log.info("segment_skipped mediaId={} reason=no_raw_segments", mediaId);
            return;
        }

        MediaFile media = mediaFileMapper.selectById(mediaId);
        long duration = media != null && media.getDurationMs() != null
                ? media.getDurationMs()
                : raw.get(raw.size() - 1).getEndMs();

        List<MediaArtifact> keyframes = artifactMapper.selectList(
                Wrappers.<MediaArtifact>lambdaQuery()
                        .eq(MediaArtifact::getMediaId, mediaId)
                        .eq(MediaArtifact::getType, "KEYFRAME")
                        .orderByAsc(MediaArtifact::getSeq));

        segmentMapper.delete(Wrappers.<VideoSegment>lambdaQuery()
                .eq(VideoSegment::getMediaId, mediaId)
                .eq(VideoSegment::getSource, "MERGED"));

        int merged = 0;
        for (long start = 0; start < duration; start += WINDOW_MS) {
            long end = Math.min(duration, start + WINDOW_MS);
            final long windowStart = start;
            final long windowEnd = end;
            List<VideoSegment> window = raw.stream()
                    .filter(segment -> segment.getStartMs() >= windowStart
                            && segment.getStartMs() < windowEnd)
                    .toList();
            if (window.isEmpty()) continue;

            String transcript = window.stream()
                    .filter(segment -> "ASR".equals(segment.getSource()))
                    .map(VideoSegment::getTranscript)
                    .filter(text -> text != null && !text.isBlank())
                    .reduce((left, right) -> left + " " + right)
                    .orElse("");
            Set<String> ocrTexts = new LinkedHashSet<>();
            for (VideoSegment segment : window) {
                if (!"OCR".equals(segment.getSource()) || segment.getOcrTexts() == null) continue;
                JSONArray array = JSON.parseArray(segment.getOcrTexts());
                if (array == null) continue;
                for (int i = 0; i < array.size(); i++) {
                    String text = array.getString(i);
                    if (text != null && !text.isBlank()) ocrTexts.add(text);
                }
            }
            if (transcript.isBlank() && ocrTexts.isEmpty()) continue;

            VideoSegment mergedSegment = new VideoSegment();
            mergedSegment.setMediaId(mediaId);
            mergedSegment.setStartMs(start);
            mergedSegment.setEndMs(end);
            mergedSegment.setTranscript(transcript);
            mergedSegment.setOcrTexts(JSON.toJSONString(new ArrayList<>(ocrTexts)));
            mergedSegment.setSource("MERGED");
            mergedSegment.setKeyframeKey(nearestKeyframe(keyframes, start));
            segmentMapper.insert(mergedSegment);
            merged++;
        }
        log.info("segments_merged mediaId={} windows={}", mediaId, merged);
    }

    private String nearestKeyframe(List<MediaArtifact> keyframes, long timestamp) {
        return keyframes.stream()
                .min(Comparator.comparingLong(frame -> Math.abs(frame.getStartMs() - timestamp)))
                .map(MediaArtifact::getObjectKey)
                .orElse(null);
    }
}
