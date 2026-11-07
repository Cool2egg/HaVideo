package com.videorag.pipeline.handler;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videorag.ai.AiClient;
import com.videorag.knowledge.entity.KnowledgeChunk;
import com.videorag.knowledge.entity.VideoSegment;
import com.videorag.knowledge.mapper.KnowledgeChunkMapper;
import com.videorag.knowledge.mapper.VideoSegmentMapper;
import com.videorag.media.MediaService;
import com.videorag.media.entity.MediaFile;
import com.videorag.media.mapper.MediaFileMapper;
import com.videorag.pipeline.Stage;
import com.videorag.pipeline.StageSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class SummaryHandler implements StageHandler {

    private static final Logger log = LoggerFactory.getLogger(SummaryHandler.class);

    private final MediaFileMapper mediaFileMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final VideoSegmentMapper segmentMapper;
    private final AiClient aiClient;
    private final MediaService mediaService;

    public SummaryHandler(MediaFileMapper mediaFileMapper,
                          KnowledgeChunkMapper chunkMapper,
                          VideoSegmentMapper segmentMapper,
                          AiClient aiClient,
                          MediaService mediaService) {
        this.mediaFileMapper = mediaFileMapper;
        this.chunkMapper = chunkMapper;
        this.segmentMapper = segmentMapper;
        this.aiClient = aiClient;
        this.mediaService = mediaService;
    }

    @Override
    public Stage stage() {
        return Stage.SUMMARY;
    }

    @Override
    public void handle(Long mediaId) {
        mediaService.updateProgress(mediaId, "PROCESSING", 91);
        MediaFile media = mediaFileMapper.selectById(mediaId);
        if (media == null) throw new IllegalStateException("媒体不存在: " + mediaId);

        List<KnowledgeChunk> chunks = chunkMapper.selectList(
                Wrappers.<KnowledgeChunk>lambdaQuery()
                        .eq(KnowledgeChunk::getMediaId, mediaId)
                        .orderByAsc(KnowledgeChunk::getChunkIndex));

        String summaryJson;
        if (chunks.isEmpty()) {
            summaryJson = "{\"oneLine\":\"该视频未识别到有效语音或画面文字\",\"outline\":[],"
                    + "\"keyPoints\":[],\"keywords\":[]}";
        } else {
            String digest = chunks.stream()
                    .map(chunk -> "[" + StageSupport.timeLabel(chunk.getStartMs()) + "-"
                            + StageSupport.timeLabel(chunk.getEndMs()) + "] "
                            + (chunk.getSummary() == null ? "" : chunk.getSummary()))
                    .collect(Collectors.joining("\n"));
            summaryJson = aiClient.finalSummary("理解视频核心内容并生成结构化总结",
                    "通用模式：给出结论、章节时间轴与要点", digest);
        }

        List<VideoSegment> merged = segmentMapper.selectList(
                Wrappers.<VideoSegment>lambdaQuery()
                        .eq(VideoSegment::getMediaId, mediaId)
                        .eq(VideoSegment::getSource, "MERGED")
                        .orderByAsc(VideoSegment::getStartMs));
        String transcript = merged.stream()
                .map(VideoSegment::getTranscript)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n"));

        MediaFile update = new MediaFile();
        update.setId(mediaId);
        update.setSummaryJson(summaryJson);
        update.setTranscriptText(transcript);
        update.setStatus("READY");
        update.setProgress(100);
        mediaFileMapper.updateById(update);
        log.info("summary_completed mediaId={} chunks={} transcriptChars={}",
                mediaId, chunks.size(), transcript.length());
    }
}
