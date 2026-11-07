package com.videorag.pipeline.handler;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videorag.ai.AiProperties;
import com.videorag.common.FileUtils;
import com.videorag.media.FfmpegService;
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
import java.util.List;

@Component
public class AudioHandler implements StageHandler {

    private static final Logger log = LoggerFactory.getLogger(AudioHandler.class);

    private final MediaFileMapper mediaFileMapper;
    private final MediaArtifactMapper artifactMapper;
    private final MediaService mediaService;
    private final StorageService storageService;
    private final FfmpegService ffmpegService;
    private final AiProperties aiProperties;

    public AudioHandler(MediaFileMapper mediaFileMapper,
                        MediaArtifactMapper artifactMapper,
                        MediaService mediaService,
                        StorageService storageService,
                        FfmpegService ffmpegService,
                        AiProperties aiProperties) {
        this.mediaFileMapper = mediaFileMapper;
        this.artifactMapper = artifactMapper;
        this.mediaService = mediaService;
        this.storageService = storageService;
        this.ffmpegService = ffmpegService;
        this.aiProperties = aiProperties;
    }

    @Override
    public Stage stage() {
        return Stage.AUDIO;
    }

    @Override
    public void handle(Long mediaId) {
        MediaFile media = mediaFileMapper.selectById(mediaId);
        if (media == null) throw new IllegalStateException("媒体不存在: " + mediaId);
        mediaService.updateProgress(mediaId, "PROCESSING", 6);

        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("videorag-audio-");
            Path input = StageSupport.download(storageService, media, workspace);
            Path audioDir = Files.createDirectory(workspace.resolve("audio"));
            int segmentSeconds = aiProperties.getAsrSegmentSeconds();
            List<Path> segments = ffmpegService.splitAudio(input, audioDir, segmentSeconds);

            artifactMapper.delete(Wrappers.<MediaArtifact>lambdaQuery()
                    .eq(MediaArtifact::getMediaId, mediaId)
                    .eq(MediaArtifact::getType, "AUDIO"));

            long duration = media.getDurationMs() == null ? 0 : media.getDurationMs();
            int seq = 1;
            for (Path segment : segments) {
                String objectKey = "audio/" + mediaId + "/" + seq + ".wav";
                try (InputStream stream = Files.newInputStream(segment)) {
                    storageService.put(objectKey, stream, Files.size(segment), "audio/wav");
                }
                MediaArtifact artifact = new MediaArtifact();
                artifact.setMediaId(mediaId);
                artifact.setType("AUDIO");
                artifact.setSeq(seq);
                artifact.setObjectKey(objectKey);
                artifact.setStartMs((long) (seq - 1) * segmentSeconds * 1000);
                long end = (long) seq * segmentSeconds * 1000;
                artifact.setEndMs(duration > 0 ? Math.min(end, duration) : end);
                artifactMapper.insert(artifact);
                seq++;
            }
            log.info("audio_split_completed mediaId={} segments={}", mediaId, segments.size());
        } catch (IOException error) {
            throw new IllegalStateException("音频切分失败", error);
        } finally {
            FileUtils.deleteRecursively(workspace);
        }
    }
}
