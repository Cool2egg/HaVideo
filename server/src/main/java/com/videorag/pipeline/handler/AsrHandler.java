package com.videorag.pipeline.handler;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videorag.ai.AsrClient;
import com.videorag.common.FileUtils;
import com.videorag.knowledge.entity.VideoSegment;
import com.videorag.knowledge.mapper.VideoSegmentMapper;
import com.videorag.media.MediaService;
import com.videorag.media.StorageService;
import com.videorag.media.entity.MediaArtifact;
import com.videorag.media.mapper.MediaArtifactMapper;
import com.videorag.pipeline.LeaseContext;
import com.videorag.pipeline.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

@Component
public class AsrHandler implements StageHandler {

    private static final Logger log = LoggerFactory.getLogger(AsrHandler.class);

    private final MediaArtifactMapper artifactMapper;
    private final VideoSegmentMapper segmentMapper;
    private final StorageService storageService;
    private final AsrClient asrClient;
    private final MediaService mediaService;

    public AsrHandler(MediaArtifactMapper artifactMapper,
                      VideoSegmentMapper segmentMapper,
                      StorageService storageService,
                      AsrClient asrClient,
                      MediaService mediaService) {
        this.artifactMapper = artifactMapper;
        this.segmentMapper = segmentMapper;
        this.storageService = storageService;
        this.asrClient = asrClient;
        this.mediaService = mediaService;
    }

    @Override
    public Stage stage() {
        return Stage.ASR;
    }

    @Override
    public void handle(Long mediaId) {
        mediaService.updateProgress(mediaId, "PROCESSING", 16);
        List<MediaArtifact> artifacts = artifactMapper.selectList(
                Wrappers.<MediaArtifact>lambdaQuery()
                        .eq(MediaArtifact::getMediaId, mediaId)
                        .eq(MediaArtifact::getType, "AUDIO")
                        .orderByAsc(MediaArtifact::getSeq));
        if (artifacts.isEmpty()) {
            log.info("asr_skipped mediaId={} reason=no_audio", mediaId);
            return;
        }

        segmentMapper.delete(Wrappers.<VideoSegment>lambdaQuery()
                .eq(VideoSegment::getMediaId, mediaId)
                .eq(VideoSegment::getSource, "ASR"));

        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("videorag-asr-");
            int totalSegments = 0;
            for (MediaArtifact artifact : artifacts) {
                LeaseContext.throwIfLost();
                Path audio = workspace.resolve("asr_" + artifact.getSeq() + ".wav");
                try (InputStream stream = storageService.get(artifact.getObjectKey())) {
                    Files.copy(stream, audio, StandardCopyOption.REPLACE_EXISTING);
                }
                String text = asrClient.transcribe(audio.toFile());
                List<Span> spans = splitText(text, artifact.getStartMs(), artifact.getEndMs());
                for (Span span : spans) {
                    VideoSegment segment = new VideoSegment();
                    segment.setMediaId(mediaId);
                    segment.setStartMs(span.startMs());
                    segment.setEndMs(span.endMs());
                    segment.setTranscript(span.text());
                    segment.setSource("ASR");
                    segmentMapper.insert(segment);
                    totalSegments++;
                }
            }
            log.info("asr_completed mediaId={} segments={}", mediaId, totalSegments);
        } catch (IOException error) {
            throw new IllegalStateException("ASR 音频读取失败", error);
        } finally {
            FileUtils.deleteRecursively(workspace);
        }
    }

    private List<Span> splitText(String text, long startMs, long endMs) {
        if (text == null || text.isBlank() || endMs <= startMs) {
            return List.of();
        }
        String cleaned = text.replaceAll("\\s+", " ").trim();
        String[] rawParts = cleaned.split("(?<=[。！？!?；;])");
        List<String> parts = new ArrayList<>();
        for (String part : rawParts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) parts.add(trimmed);
        }
        if (parts.isEmpty()) {
            parts.add(cleaned);
        }

        long totalChars = parts.stream().mapToLong(String::length).sum();
        long range = endMs - startMs;
        long cursor = startMs;
        List<Span> spans = new ArrayList<>(parts.size());
        for (int index = 0; index < parts.size(); index++) {
            String part = parts.get(index);
            long spanEnd = index == parts.size() - 1
                    ? endMs
                    : cursor + Math.max(1, range * part.length() / Math.max(1, totalChars));
            spans.add(new Span(cursor, Math.min(endMs, spanEnd), part));
            cursor = spanEnd;
        }
        return spans;
    }

    private record Span(long startMs, long endMs, String text) {
    }
}
