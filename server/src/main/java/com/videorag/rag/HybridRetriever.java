package com.videorag.rag;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videorag.ai.EmbeddingClient;
import com.videorag.ai.RerankClient;
import com.videorag.knowledge.EsClient;
import com.videorag.knowledge.QdrantClient;
import com.videorag.knowledge.entity.VideoSegment;
import com.videorag.knowledge.mapper.VideoSegmentMapper;
import com.videorag.media.entity.MediaFile;
import com.videorag.media.mapper.MediaFileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrantClient;
    private final EsClient esClient;
    private final RerankClient rerankClient;
    private final MediaFileMapper mediaFileMapper;
    private final VideoSegmentMapper segmentMapper;

    private final int rrfK;
    private final int candidateLimit;
    private final int defaultTopK;
    private final boolean rerankEnabled;
    private final double rerankMinScore;

    public HybridRetriever(EmbeddingClient embeddingClient,
                           QdrantClient qdrantClient,
                           EsClient esClient,
                           RerankClient rerankClient,
                           MediaFileMapper mediaFileMapper,
                           VideoSegmentMapper segmentMapper,
                           @Value("${rag.rrf-k:20}") int rrfK,
                           @Value("${rag.candidate-limit:30}") int candidateLimit,
                           @Value("${rag.top-k:8}") int defaultTopK,
                           @Value("${rag.rerank-enabled:true}") boolean rerankEnabled,
                           @Value("${rag.rerank-min-score:0}") double rerankMinScore) {
        this.embeddingClient = embeddingClient;
        this.qdrantClient = qdrantClient;
        this.esClient = esClient;
        this.rerankClient = rerankClient;
        this.mediaFileMapper = mediaFileMapper;
        this.segmentMapper = segmentMapper;
        this.rrfK = Math.max(1, rrfK);
        this.candidateLimit = Math.max(1, candidateLimit);
        this.defaultTopK = Math.max(1, defaultTopK);
        this.rerankEnabled = rerankEnabled;
        this.rerankMinScore = rerankMinScore;
    }

    public List<Evidence> retrieve(Long userId, Long mediaId, String query, Integer limit) {
        int topK = limit == null ? defaultTopK : Math.max(1, limit);
        if (query == null || query.isBlank()) return List.of();

        List<Double> vector = List.of();
        if (qdrantClient.isEnabled()) {
            try {
                vector = embeddingClient.embed(query);
            } catch (RuntimeException error) {
                log.warn("query_embedding_failed error={}", error.getMessage());
            }
        }

        List<QdrantClient.VectorHit> vectorHits =
                qdrantClient.search(vector, userId, mediaId, candidateLimit);
        List<EsClient.EsHit> keywordHits =
                esClient.search(query, userId, mediaId, candidateLimit);
        if (vectorHits.isEmpty() && keywordHits.isEmpty()) return List.of();

        Map<String, Candidate> fused = new LinkedHashMap<>();
        addVectorChannel(fused, vectorHits);
        addKeywordChannel(fused, keywordHits);

        List<Candidate> candidates = fused.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::score).reversed())
                .limit(candidateLimit)
                .collect(Collectors.toCollection(ArrayList::new));
        candidates = rerank(query, candidates);
        candidates = candidates.stream().limit(topK).toList();

        return toEvidence(candidates);
    }

    private void addVectorChannel(Map<String, Candidate> fused,
                                  List<QdrantClient.VectorHit> hits) {
        int rank = 0;
        for (QdrantClient.VectorHit hit : hits) {
            String key = key(hit.mediaId(), hit.startMs(), hit.endMs());
            Candidate candidate = fused.get(key);
            if (candidate == null) {
                candidate = new Candidate(hit.mediaId(), hit.chunkIndex(),
                        hit.startMs(), hit.endMs(), hit.summary(), hit.keywords(), "");
                fused.put(key, candidate);
            }
            candidate.score += 1.0 / (rrfK + rank + 1);
            rank++;
        }
    }

    private void addKeywordChannel(Map<String, Candidate> fused, List<EsClient.EsHit> hits) {
        int rank = 0;
        for (EsClient.EsHit hit : hits) {
            String key = key(hit.mediaId(), hit.startMs(), hit.endMs());
            Candidate candidate = fused.get(key);
            if (candidate == null) {
                candidate = new Candidate(hit.mediaId(), hit.chunkIndex(),
                        hit.startMs(), hit.endMs(), hit.summary(), hit.keywords(), hit.text());
                fused.put(key, candidate);
            } else {
                candidate.text = hit.text();
                if (candidate.summary == null || candidate.summary.isBlank()) {
                    candidate.summary = hit.summary();
                }
                if (candidate.keywords == null || candidate.keywords.isBlank()) {
                    candidate.keywords = hit.keywords();
                }
            }
            candidate.score += 1.0 / (rrfK + rank + 1);
            rank++;
        }
    }

    private List<Candidate> rerank(String query, List<Candidate> candidates) {
        if (!rerankEnabled || candidates.isEmpty()) return candidates;
        List<String> documents = candidates.stream()
                .map(candidate -> trim(candidate.text, 1500))
                .toList();
        List<RerankClient.RerankScore> scores =
                rerankClient.rerank(query, documents, candidates.size());
        if (scores.isEmpty()) return candidates;

        List<Candidate> reranked = new ArrayList<>();
        for (RerankClient.RerankScore score : scores) {
            if (score.index() < 0 || score.index() >= candidates.size()) continue;
            if (score.score() < rerankMinScore) continue;
            Candidate candidate = candidates.get(score.index());
            candidate.score = score.score();
            reranked.add(candidate);
        }
        reranked.sort(Comparator.comparingDouble(Candidate::score).reversed());
        return reranked;
    }

    private List<Evidence> toEvidence(List<Candidate> candidates) {
        if (candidates.isEmpty()) return List.of();

        Set<Long> mediaIds = candidates.stream().map(Candidate::mediaId).collect(Collectors.toSet());
        Map<Long, String> titles = mediaFileMapper.selectBatchIds(mediaIds).stream()
                .collect(Collectors.toMap(MediaFile::getId, MediaFile::getFilename,
                        (left, right) -> left));
        Map<Long, List<VideoSegment>> segments = segmentMapper.selectList(
                        Wrappers.<VideoSegment>lambdaQuery()
                                .in(VideoSegment::getMediaId, mediaIds)
                                .eq(VideoSegment::getSource, "MERGED"))
                .stream()
                .collect(Collectors.groupingBy(VideoSegment::getMediaId));

        List<Evidence> evidence = new ArrayList<>(candidates.size());
        int index = 0;
        for (Candidate candidate : candidates) {
            List<VideoSegment> window = segments.getOrDefault(candidate.mediaId, List.of()).stream()
                    .filter(segment -> segment.getEndMs() > candidate.startMs
                            && segment.getStartMs() < candidate.endMs)
                    .toList();
            String transcript = window.stream()
                    .map(VideoSegment::getTranscript)
                    .filter(text -> text != null && !text.isBlank())
                    .collect(Collectors.joining(" "));
            List<String> ocrTexts = new ArrayList<>();
            for (VideoSegment segment : window) {
                if (segment.getOcrTexts() == null || segment.getOcrTexts().isBlank()) continue;
                JSONArray array = JSON.parseArray(segment.getOcrTexts());
                if (array == null) continue;
                for (int i = 0; i < array.size(); i++) {
                    String text = array.getString(i);
                    if (text != null && !text.isBlank() && !ocrTexts.contains(text)) {
                        ocrTexts.add(text);
                    }
                }
            }
            String snippet = snippet(transcript, ocrTexts, candidate.summary);
            String source = transcript.isBlank()
                    ? (ocrTexts.isEmpty() ? "片段" : "OCR")
                    : (ocrTexts.isEmpty() ? "ASR" : "ASR+OCR");
            index++;
            evidence.add(new Evidence("V" + index, candidate.mediaId,
                    titles.getOrDefault(candidate.mediaId, "未知视频"),
                    candidate.startMs, candidate.endMs, snippet, transcript, ocrTexts,
                    source, candidate.score));
        }
        return evidence;
    }

    private String snippet(String transcript, List<String> ocrTexts, String summary) {
        String text = !transcript.isBlank()
                ? transcript
                : (!ocrTexts.isEmpty() ? String.join(" ", ocrTexts) : summary);
        if (text == null) return "";
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 180) + "…";
    }

    private String key(long mediaId, long startMs, long endMs) {
        return mediaId + ":" + startMs + ":" + endMs;
    }

    private String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static final class Candidate {
        private final long mediaId;
        private final int chunkIndex;
        private final long startMs;
        private final long endMs;
        private String summary;
        private String keywords;
        private String text;
        private double score;

        private Candidate(long mediaId, int chunkIndex, long startMs, long endMs,
                          String summary, String keywords, String text) {
            this.mediaId = mediaId;
            this.chunkIndex = chunkIndex;
            this.startMs = startMs;
            this.endMs = endMs;
            this.summary = summary;
            this.keywords = keywords;
            this.text = text == null ? "" : text;
        }

        long mediaId() { return mediaId; }
        int chunkIndex() { return chunkIndex; }
        long startMs() { return startMs; }
        long endMs() { return endMs; }
        String summary() { return summary; }
        String keywords() { return keywords; }
        String text() { return text; }
        double score() { return score; }
    }
}
