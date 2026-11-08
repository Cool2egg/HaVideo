package com.videorag.pipeline.handler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videorag.ai.EmbeddingClient;
import com.videorag.knowledge.EsClient;
import com.videorag.knowledge.KnowledgeDoc;
import com.videorag.knowledge.QdrantClient;
import com.videorag.knowledge.entity.KnowledgeChunk;
import com.videorag.knowledge.entity.VideoSegment;
import com.videorag.knowledge.mapper.KnowledgeChunkMapper;
import com.videorag.knowledge.mapper.VideoSegmentMapper;
import com.videorag.media.MediaService;
import com.videorag.media.entity.MediaFile;
import com.videorag.media.mapper.MediaFileMapper;
import com.videorag.pipeline.LeaseContext;
import com.videorag.pipeline.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class IndexHandler implements StageHandler {

    private static final Logger log = LoggerFactory.getLogger(IndexHandler.class);

    private final MediaFileMapper mediaFileMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final VideoSegmentMapper segmentMapper;
    private final QdrantClient qdrantClient;
    private final EsClient esClient;
    private final EmbeddingClient embeddingClient;
    private final MediaService mediaService;

    public IndexHandler(MediaFileMapper mediaFileMapper,
                        KnowledgeChunkMapper chunkMapper,
                        VideoSegmentMapper segmentMapper,
                        QdrantClient qdrantClient,
                        EsClient esClient,
                        EmbeddingClient embeddingClient,
                        MediaService mediaService) {
        this.mediaFileMapper = mediaFileMapper;
        this.chunkMapper = chunkMapper;
        this.segmentMapper = segmentMapper;
        this.qdrantClient = qdrantClient;
        this.esClient = esClient;
        this.embeddingClient = embeddingClient;
        this.mediaService = mediaService;
    }

    @Override
    public Stage stage() {
        return Stage.INDEX;
    }

    @Override
    public void handle(Long mediaId) {
        mediaService.updateProgress(mediaId, "PROCESSING", 81);
        reindex(mediaId);
    }

    public void reindex(Long mediaId) {
        MediaFile media = mediaFileMapper.selectById(mediaId);
        if (media == null) throw new IllegalStateException("媒体不存在: " + mediaId);

        List<KnowledgeChunk> chunks = chunkMapper.selectList(
                Wrappers.<KnowledgeChunk>lambdaQuery()
                        .eq(KnowledgeChunk::getMediaId, mediaId)
                        .orderByAsc(KnowledgeChunk::getChunkIndex));
        if (chunks.isEmpty()) {
            log.info("index_skipped mediaId={} reason=no_chunks", mediaId);
            return;
        }

        List<VideoSegment> segments = segmentMapper.selectList(
                Wrappers.<VideoSegment>lambdaQuery()
                        .eq(VideoSegment::getMediaId, mediaId)
                        .eq(VideoSegment::getSource, "MERGED"));

        qdrantClient.deleteMedia(mediaId);
        esClient.deleteMedia(mediaId);

        List<KnowledgeDoc> docs = new ArrayList<>(chunks.size());
        int embedded = 0;
        for (KnowledgeChunk chunk : chunks) {
            LeaseContext.throwIfLost();
            String rawText = rawText(segments, chunk.getStartMs(), chunk.getEndMs());
            String keywords = chunk.getKeywords() == null ? "" : chunk.getKeywords();
            String docText = (chunk.getSummary() == null ? "" : chunk.getSummary())
                    + "\n关键词：" + keywords + "\n" + rawText;
            String pointId = UUID.nameUUIDFromBytes(
                    (mediaId + ":" + chunk.getStartMs() + ":" + chunk.getEndMs())
                            .getBytes(StandardCharsets.UTF_8)).toString();

            String qdrantPointId = null;
            if (qdrantClient.isEnabled()) {
                try {
                    List<Double> vector = embeddingClient.embed(trim(docText, 3000));
                    if (!vector.isEmpty()) {
                        qdrantClient.upsert(media.getUserId(), mediaId, chunk.getChunkIndex(),
                                chunk.getStartMs(), chunk.getEndMs(), chunk.getSummary(),
                                keywords, pointId, vector);
                        qdrantPointId = pointId;
                        embedded++;
                    }
                } catch (RuntimeException error) {
                    log.warn("embedding_failed mediaId={} chunk={} error={}",
                            mediaId, chunk.getChunkIndex(), error.getMessage());
                }
            }

            docs.add(new KnowledgeDoc(mediaId + ":" + chunk.getStartMs() + ":" + chunk.getEndMs(),
                    trim(docText, 12000),
                    media.getFilename(),
                    media.getUserId(),
                    mediaId,
                    chunk.getStartMs(),
                    chunk.getEndMs(),
                    keywords));

            KnowledgeChunk update = new KnowledgeChunk();
            update.setId(chunk.getId());
            update.setQdrantPointId(qdrantPointId);
            update.setEsDocId(mediaId + ":" + chunk.getStartMs() + ":" + chunk.getEndMs());
            chunkMapper.updateById(update);
        }

        if (esClient.isEnabled()) {
            esClient.bulkIndex(docs);
        }
        log.info("index_completed mediaId={} chunks={} embedded={}", mediaId, chunks.size(), embedded);
    }

    private String rawText(List<VideoSegment> segments, long startMs, long endMs) {
        return segments.stream()
                .filter(segment -> segment.getEndMs() > startMs && segment.getStartMs() < endMs)
                .map(segment -> {
                    StringBuilder text = new StringBuilder();
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

    private String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
