package com.videorag.pipeline.handler;

import com.videorag.common.FileUtils;
import com.videorag.media.FfmpegService;
import com.videorag.media.MediaService;
import com.videorag.media.StorageService;
import com.videorag.media.entity.MediaFile;
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

@Component
public class ProbeHandler implements StageHandler {

    private static final Logger log = LoggerFactory.getLogger(ProbeHandler.class);

    private final MediaFileMapper mediaFileMapper;
    private final MediaService mediaService;
    private final StorageService storageService;
    private final FfmpegService ffmpegService;

    public ProbeHandler(MediaFileMapper mediaFileMapper,
                        MediaService mediaService,
                        StorageService storageService,
                        FfmpegService ffmpegService) {
        this.mediaFileMapper = mediaFileMapper;
        this.mediaService = mediaService;
        this.storageService = storageService;
        this.ffmpegService = ffmpegService;
    }

    @Override
    public Stage stage() {
        return Stage.PROBE;
    }

    @Override
    public void handle(Long mediaId) {
        MediaFile media = mediaFileMapper.selectById(mediaId);
        if (media == null) {
            throw new IllegalStateException("媒体不存在: " + mediaId);
        }
        mediaService.updateProgress(mediaId, "PROCESSING", 1);

        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("videorag-probe-");
            Path input = StageSupport.download(storageService, media, workspace);
            long durationMs = ffmpegService.probeDurationMs(input);
            mediaService.updateDuration(mediaId, durationMs);

            try {
                Path cover = workspace.resolve("cover.jpg");
                ffmpegService.extractCover(input, cover, durationMs);
                String coverKey = "covers/" + mediaId + ".jpg";
                try (InputStream stream = Files.newInputStream(cover)) {
                    storageService.put(coverKey, stream, Files.size(cover), "image/jpeg");
                }
                MediaFile update = new MediaFile();
                update.setId(mediaId);
                update.setCoverKey(coverKey);
                mediaFileMapper.updateById(update);
            } catch (RuntimeException | IOException coverError) {
                log.warn("cover_extract_failed mediaId={}", mediaId, coverError);
            }

            log.info("probe_completed mediaId={} durationMs={}", mediaId, durationMs);
        } catch (IOException error) {
            throw new IllegalStateException("读取源视频失败", error);
        } finally {
            FileUtils.deleteRecursively(workspace);
        }
    }
}
