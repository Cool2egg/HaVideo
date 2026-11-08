package com.videorag.pipeline.handler;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videorag.common.FileUtils;
import com.videorag.knowledge.entity.VideoSegment;
import com.videorag.knowledge.mapper.VideoSegmentMapper;
import com.videorag.media.MediaService;
import com.videorag.media.OcrService;
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
import java.util.List;

@Component
public class OcrHandler implements StageHandler {

    private static final Logger log = LoggerFactory.getLogger(OcrHandler.class);

    private final MediaArtifactMapper artifactMapper;
    private final VideoSegmentMapper segmentMapper;
    private final StorageService storageService;
    private final OcrService ocrService;
    private final MediaService mediaService;

    public OcrHandler(MediaArtifactMapper artifactMapper,
                      VideoSegmentMapper segmentMapper,
                      StorageService storageService,
                      OcrService ocrService,
                      MediaService mediaService) {
        this.artifactMapper = artifactMapper;
        this.segmentMapper = segmentMapper;
        this.storageService = storageService;
        this.ocrService = ocrService;
        this.mediaService = mediaService;
    }

    @Override
    public Stage stage() {
        return Stage.OCR;
    }

    @Override
    public void handle(Long mediaId) {
        mediaService.updateProgress(mediaId, "PROCESSING", 51);
        List<MediaArtifact> keyframes = artifactMapper.selectList(
                Wrappers.<MediaArtifact>lambdaQuery()
                        .eq(MediaArtifact::getMediaId, mediaId)
                        .eq(MediaArtifact::getType, "KEYFRAME")
                        .orderByAsc(MediaArtifact::getSeq));
        if (keyframes.isEmpty()) {
            log.info("ocr_skipped mediaId={} reason=no_keyframes", mediaId);
            return;
        }

        segmentMapper.delete(Wrappers.<VideoSegment>lambdaQuery()
                .eq(VideoSegment::getMediaId, mediaId)
                .eq(VideoSegment::getSource, "OCR"));

        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("videorag-ocr-");
            int recognized = 0;
            String previousText = "";
            for (MediaArtifact keyframe : keyframes) {
                LeaseContext.throwIfLost();
                Path image = workspace.resolve("frame_" + keyframe.getSeq() + ".jpg");
                try (InputStream stream = storageService.get(keyframe.getObjectKey())) {
                    Files.copy(stream, image, StandardCopyOption.REPLACE_EXISTING);
                }
                String text = ocrService.recognize(image);
                if (text.isBlank()) continue;
                if (similar(text, previousText)) continue;
                previousText = text;

                VideoSegment segment = new VideoSegment();
                segment.setMediaId(mediaId);
                segment.setStartMs(keyframe.getStartMs());
                segment.setEndMs(keyframe.getEndMs());
                segment.setOcrTexts(JSON.toJSONString(List.of(text)));
                segment.setSource("OCR");
                segment.setKeyframeKey(keyframe.getObjectKey());
                segmentMapper.insert(segment);
                recognized++;
            }
            log.info("ocr_completed mediaId={} keyframes={} recognized={}",
                    mediaId, keyframes.size(), recognized);
        } catch (IOException error) {
            throw new IllegalStateException("OCR 图片读取失败", error);
        } finally {
            FileUtils.deleteRecursively(workspace);
        }
    }

    private boolean similar(String left, String right) {
        if (right == null || right.isBlank()) return false;
        String a = left.replaceAll("\\s+", "");
        String b = right.replaceAll("\\s+", "");
        if (a.isEmpty() || b.isEmpty()) return false;
        int common = 0;
        int length = Math.min(a.length(), b.length());
        for (int i = 0; i < length; i++) {
            if (a.charAt(i) == b.charAt(i)) common++;
        }
        return (double) common / Math.max(a.length(), b.length()) > 0.85;
    }
}
