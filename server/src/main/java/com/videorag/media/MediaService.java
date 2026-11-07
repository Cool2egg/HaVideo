package com.videorag.media;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.videorag.common.BusinessException;
import com.videorag.common.ErrorCode;
import com.videorag.common.PageResult;
import com.videorag.knowledge.EsClient;
import com.videorag.knowledge.QdrantClient;
import com.videorag.knowledge.entity.KnowledgeChunk;
import com.videorag.knowledge.entity.VideoSegment;
import com.videorag.knowledge.mapper.KnowledgeChunkMapper;
import com.videorag.knowledge.mapper.VideoSegmentMapper;
import com.videorag.media.dto.KeyframeView;
import com.videorag.media.dto.MediaDetail;
import com.videorag.media.dto.MediaSummary;
import com.videorag.media.dto.SegmentView;
import com.videorag.media.entity.MediaArtifact;
import com.videorag.media.entity.MediaFile;
import com.videorag.media.mapper.MediaArtifactMapper;
import com.videorag.media.mapper.MediaFileMapper;
import com.videorag.pipeline.entity.ProcessTask;
import com.videorag.pipeline.mapper.ProcessTaskMapper;
import com.videorag.upload.entity.UploadSession;
import com.videorag.upload.mapper.UploadSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private final MediaFileMapper mediaFileMapper;
    private final ProcessTaskMapper processTaskMapper;
    private final UploadSessionMapper uploadSessionMapper;
    private final VideoSegmentMapper videoSegmentMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final MediaArtifactMapper mediaArtifactMapper;
    private final QdrantClient qdrantClient;
    private final EsClient esClient;
    private final StorageService storageService;

    public MediaService(MediaFileMapper mediaFileMapper,
                        ProcessTaskMapper processTaskMapper,
                        UploadSessionMapper uploadSessionMapper,
                        VideoSegmentMapper videoSegmentMapper,
                        KnowledgeChunkMapper knowledgeChunkMapper,
                        MediaArtifactMapper mediaArtifactMapper,
                        QdrantClient qdrantClient,
                        EsClient esClient,
                        StorageService storageService) {
        this.mediaFileMapper = mediaFileMapper;
        this.processTaskMapper = processTaskMapper;
        this.uploadSessionMapper = uploadSessionMapper;
        this.videoSegmentMapper = videoSegmentMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.mediaArtifactMapper = mediaArtifactMapper;
        this.qdrantClient = qdrantClient;
        this.esClient = esClient;
        this.storageService = storageService;
    }

    public PageResult<MediaSummary> list(Long userId, long page, long size) {
        Page<MediaFile> result = mediaFileMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(50, Math.max(1, size))),
                Wrappers.<MediaFile>lambdaQuery()
                        .eq(MediaFile::getUserId, userId)
                        .orderByDesc(MediaFile::getCreatedAt));
        List<MediaSummary> items = result.getRecords().stream()
                .map(this::toSummary)
                .toList();
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), items);
    }

    public MediaDetail detail(Long userId, Long mediaId) {
        MediaFile media = requireOwned(userId, mediaId);
        return new MediaDetail(media.getId(), media.getFilename(), media.getStatus(),
                media.getProgress(), media.getDurationMs(), media.getSummaryJson(),
                media.getTranscriptText(), media.getErrorStage(), media.getErrorMessage(),
                media.getCreatedAt());
    }

    public String playbackUrl(Long userId, Long mediaId) {
        MediaFile media = requireOwned(userId, mediaId);
        return storageService.presignedGet(media.getObjectKey(), Duration.ofHours(2));
    }

    public MediaFile requireOwned(Long userId, Long mediaId) {
        MediaFile media = mediaId == null ? null : mediaFileMapper.selectById(mediaId);
        if (media == null || !media.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "视频不存在");
        }
        return media;
    }

    public MediaFile findFirstByUser(Long userId) {
        LambdaQueryWrapper<MediaFile> query = Wrappers.lambdaQuery(MediaFile.class)
                .eq(MediaFile::getUserId, userId)
                .orderByDesc(MediaFile::getCreatedAt)
                .last("limit 1");
        return mediaFileMapper.selectOne(query);
    }

    public void updateProgress(Long mediaId, String status, int progress) {
        mediaFileMapper.update(null, Wrappers.<MediaFile>lambdaUpdate()
                .eq(MediaFile::getId, mediaId)
                .set(MediaFile::getStatus, status)
                .set(MediaFile::getProgress, Math.max(0, Math.min(100, progress)))
                .set(MediaFile::getErrorStage, null)
                .set(MediaFile::getErrorMessage, null));
    }

    public void markFailed(Long mediaId, String stage, String message) {
        MediaFile update = new MediaFile();
        update.setId(mediaId);
        update.setStatus("FAILED");
        update.setErrorStage(stage);
        update.setErrorMessage(message == null ? "处理失败" : truncate(message));
        mediaFileMapper.updateById(update);
        log.warn("media_failed mediaId={} stage={} message={}", mediaId, stage, message);
    }

    public void updateDuration(Long mediaId, Long durationMs) {
        MediaFile update = new MediaFile();
        update.setId(mediaId);
        update.setDurationMs(durationMs);
        mediaFileMapper.updateById(update);
    }

    public void delete(Long userId, Long mediaId) {
        MediaFile media = requireOwned(userId, mediaId);
        qdrantClient.deleteMedia(mediaId);
        esClient.deleteMedia(mediaId);
        videoSegmentMapper.delete(Wrappers.<VideoSegment>lambdaQuery()
                .eq(VideoSegment::getMediaId, mediaId));
        knowledgeChunkMapper.delete(Wrappers.<KnowledgeChunk>lambdaQuery()
                .eq(KnowledgeChunk::getMediaId, mediaId));
        mediaArtifactMapper.delete(Wrappers.<MediaArtifact>lambdaQuery()
                .eq(MediaArtifact::getMediaId, mediaId));
        storageService.remove(media.getObjectKey());
        storageService.removePrefix("audio/" + mediaId + "/");
        storageService.removePrefix("keyframes/" + mediaId + "/");
        storageService.remove("covers/" + mediaId + ".jpg");
        processTaskMapper.delete(Wrappers.<ProcessTask>lambdaQuery()
                .eq(ProcessTask::getMediaId, mediaId));
        uploadSessionMapper.delete(Wrappers.<UploadSession>lambdaQuery()
                .eq(UploadSession::getMediaId, mediaId));
        mediaFileMapper.deleteById(mediaId);
    }

    public List<SegmentView> segments(Long userId, Long mediaId) {
        requireOwned(userId, mediaId);
        return videoSegmentMapper.selectList(Wrappers.<VideoSegment>lambdaQuery()
                        .eq(VideoSegment::getMediaId, mediaId)
                        .eq(VideoSegment::getSource, "MERGED")
                        .orderByAsc(VideoSegment::getStartMs))
                .stream()
                .map(segment -> new SegmentView(segment.getStartMs(), segment.getEndMs(),
                        segment.getTranscript(), segment.getOcrTexts(), segment.getKeyframeKey()))
                .toList();
    }

    public List<KeyframeView> keyframes(Long userId, Long mediaId) {
        requireOwned(userId, mediaId);
        return mediaArtifactMapper.selectList(Wrappers.<MediaArtifact>lambdaQuery()
                        .eq(MediaArtifact::getMediaId, mediaId)
                        .eq(MediaArtifact::getType, "KEYFRAME")
                        .orderByAsc(MediaArtifact::getSeq))
                .stream()
                .map(artifact -> new KeyframeView(artifact.getStartMs(),
                        storageService.presignedGet(artifact.getObjectKey(), Duration.ofHours(1))))
                .toList();
    }

    private MediaSummary toSummary(MediaFile media) {
        String coverUrl = null;
        if (media.getCoverKey() != null && !media.getCoverKey().isBlank()) {
            try {
                coverUrl = storageService.presignedGet(media.getCoverKey(), Duration.ofHours(1));
            } catch (RuntimeException ignored) {
                coverUrl = null;
            }
        }
        return new MediaSummary(media.getId(), media.getFilename(), media.getStatus(),
                media.getProgress(), media.getDurationMs(), coverUrl,
                media.getErrorStage(), media.getCreatedAt());
    }

    private String truncate(String message) {
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
