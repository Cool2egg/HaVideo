package com.videorag.pipeline.handler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videorag.ai.AiClient;
import com.videorag.knowledge.entity.KnowledgeChunk;
import com.videorag.knowledge.entity.VideoSegment;
import com.videorag.knowledge.mapper.KnowledgeChunkMapper;
import com.videorag.knowledge.mapper.VideoSegmentMapper;
import com.videorag.media.MediaService;
import com.videorag.pipeline.Stage;
import com.videorag.pipeline.StageSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class ChunkHandler implements StageHandler {

    private static final Logger log = LoggerFactory.getLogger(ChunkHandler.class);
    private static final long CHUNK_MS = 5 * 60 * 1000L;

    private final VideoSegmentMapper segmentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final AiClient aiClient;
    private final MediaService mediaService;

    public ChunkHandler(VideoSegmentMapper segmentMapper,
                        KnowledgeChunkMapper chunkMapper,
                        AiClient aiClient,
                        MediaService mediaService) {
        this.segmentMapper = segmentMapper;
        this.chunkMapper = chunkMapper;
        this.aiClient = aiClient;
        this.mediaService = mediaService;
    }

    @Override
    public Stage stage() {
        return Stage.CHUNK;
    }

    @Override
    public void handle(Long mediaId) {
        mediaService.updateProgress(mediaId, "PROCESSING", 71);
        List<VideoSegment> segments = segmentMapper.selectList(
                Wrappers.<VideoSegment>lambdaQuery()
                        .eq(VideoSegment::getMediaId, mediaId)
                        .eq(VideoSegment::getSource, "MERGED")
                        .orderByAsc(VideoSegment::getStartMs));

        chunkMapper.delete(Wrappers.<KnowledgeChunk>lambdaQuery()
                .eq(KnowledgeChunk::getMediaId, mediaId));
        if (segments.isEmpty()) {
            log.info("chunk_skipped mediaId={} reason=no_segments", mediaId);
            return;
        }

        long lastEnd = segments.get(segments.size() - 1).getEndMs();
        int chunkIndex = 0;
        for (long start = 0; start < lastEnd; start += CHUNK_MS) {
            long end = start + CHUNK_MS;
            long windowStart = start;
            long windowEnd = end;
            List<VideoSegment> window = segments.stream()
                    .filter(segment -> segment.getEndMs() > windowStart
                            && segment.getStartMs() < windowEnd)
                    .toList();
            if (window.isEmpty()) continue;

            String rawText = buildWindowText(window);
            AiClient.ChunkSummary summary = aiClient.summarizeChunk(rawText);

            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setMediaId(mediaId);
            chunk.setChunkIndex(chunkIndex++);
            chunk.setStartMs(start);
            chunk.setEndMs(Math.min(end, lastEnd));
            chunk.setSummary(summary.summary());
            chunk.setKeywords(JSON.toJSONString(summary.keywords()));
            chunkMapper.insert(chunk);
        }
        log.info("chunks_built mediaId={} chunks={}", mediaId, chunkIndex);
    }

    private String buildWindowText(List<VideoSegment> window) {
        return window.stream()
                .map(segment -> {
                    StringBuilder text = new StringBuilder();
                    text.append('[').append(StageSupport.timeLabel(segment.getStartMs())).append("] ");
                    if (segment.getTranscript() != null && !segment.getTranscript().isBlank()) {
                        text.append(segment.getTranscript());
                    }
                    if (segment.getOcrTexts() != null && !segment.getOcrTexts().isBlank()) {
                        JSONArray array = JSON.parseArray(segment.getOcrTexts());
                        if (array != null && !array.isEmpty()) {
                            text.append(" 画面：");
                            for (int i = 0; i < array.size(); i++) {
                                if (i > 0) text.append(' ');
                                text.append(array.getString(i));
                            }
                        }
                    }
                    return text.toString();
                })
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining("\n"));
    }
}
