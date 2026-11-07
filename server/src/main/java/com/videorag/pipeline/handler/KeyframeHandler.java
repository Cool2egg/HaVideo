package com.videorag.pipeline.handler;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videorag.common.FileUtils;
import com.videorag.media.FfmpegService;
import com.videorag.media.ImageHash;
import com.videorag.media.MediaService;
import com.videorag.media.StorageService;
import com.videorag.media.entity.MediaArtifact;
import com.videorag.media.entity.MediaFile;
import com.videorag.media.mapper.MediaArtifactMapper;
import com.videorag.media.mapper.MediaFileMapper;
import com.videorag.pipeline.Stage;
import com.videorag.pipeline.StageSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class KeyframeHandler implements StageHandler {

    private static final Logger log = LoggerFactory.getLogger(KeyframeHandler.class);
    private static final int INTERVAL_SECONDS = 10;
    private static final int MAX_FRAMES = 300;
    private static final int HASH_DISTANCE = 1;

    private final MediaFileMapper mediaFileMapper;
    private final MediaArtifactMapper artifactMapper;
    private final MediaService mediaService;
    private final StorageService storageService;
    private final FfmpegService ffmpegService;

    public KeyframeHandler(MediaFileMapper mediaFileMapper,
                           MediaArtifactMapper artifactMapper,
                           MediaService mediaService,
                           StorageService storageService,
                           FfmpegService ffmpegService) {
        this.mediaFileMapper = mediaFileMapper;
        this.artifactMapper = artifactMapper;
        this.mediaService = mediaService;
        this.storageService = storageService;
        this.ffmpegService = ffmpegService;
    }

    @Override
    public Stage stage() {
        return Stage.KEYFRAME;
    }

    @Override
    public void handle(Long mediaId) {
        MediaFile media = mediaFileMapper.selectById(mediaId);
        if (media == null) throw new IllegalStateException("媒体不存在: " + mediaId);
        mediaService.updateProgress(mediaId, "PROCESSING", 46);

        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("videorag-frames-");
            Path input = StageSupport.download(storageService, media, workspace);
            Path frameDir = Files.createDirectory(workspace.resolve("frames"));
            List<FfmpegService.Frame> frames = ffmpegService.extractFrames(
                    input, frameDir, INTERVAL_SECONDS, MAX_FRAMES);

            artifactMapper.delete(Wrappers.<MediaArtifact>lambdaQuery()
                    .eq(MediaArtifact::getMediaId, mediaId)
                    .eq(MediaArtifact::getType, "KEYFRAME"));

            List<Long> keptHashes = new ArrayList<>();
            int kept = 0;
            for (FfmpegService.Frame frameInfo : frames) {
                Path frame = frameInfo.path();
                long startMs = frameInfo.timestampMs();
                long computedHash;
                try {
                    computedHash = ImageHash.dhash(frame);
                } catch (IOException error) {
                    computedHash = 0;
                }
                final long hash = computedHash;
                boolean duplicate = hash != 0 && keptHashes.stream()
                        .anyMatch(previous -> ImageHash.hamming(previous, hash) <= HASH_DISTANCE);
                if (duplicate) continue;

                keptHashes.add(hash);
                kept++;
                String objectKey = "keyframes/" + mediaId + "/" + kept + ".jpg";
                try (InputStream stream = Files.newInputStream(frame)) {
                    storageService.put(objectKey, stream, Files.size(frame), "image/jpeg");
                }
                MediaArtifact artifact = new MediaArtifact();
                artifact.setMediaId(mediaId);
                artifact.setType("KEYFRAME");
                artifact.setSeq(kept);
                artifact.setObjectKey(objectKey);
                artifact.setStartMs(startMs);
                artifact.setEndMs(startMs + INTERVAL_SECONDS * 1000L);
                artifactMapper.insert(artifact);
            }
            log.info("keyframes_completed mediaId={} extracted={} kept={}",
                    mediaId, frames.size(), kept);
        } catch (IOException error) {
            throw new IllegalStateException("关键帧抽取失败", error);
        } finally {
            FileUtils.deleteRecursively(workspace);
        }
    }
}
