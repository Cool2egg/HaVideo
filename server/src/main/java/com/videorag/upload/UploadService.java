package com.videorag.upload;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videorag.common.BusinessException;
import com.videorag.common.ErrorCode;
import com.videorag.media.MediaService;
import com.videorag.media.StorageService;
import com.videorag.media.entity.MediaFile;
import com.videorag.media.mapper.MediaFileMapper;
import com.videorag.pipeline.Stage;
import com.videorag.pipeline.TaskService;
import com.videorag.upload.dto.CompleteUploadResponse;
import com.videorag.upload.dto.InitUploadRequest;
import com.videorag.upload.dto.InitUploadResponse;
import com.videorag.upload.dto.PrecheckRequest;
import com.videorag.upload.dto.PrecheckResponse;
import com.videorag.upload.dto.UploadPartResponse;
import com.videorag.upload.dto.UploadStatusResponse;
import com.videorag.upload.entity.UploadSession;
import com.videorag.upload.mapper.UploadSessionMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private static final String PARTS_PREFIX = "upload:parts:";

    private final MediaFileMapper mediaFileMapper;
    private final UploadSessionMapper uploadSessionMapper;
    private final MediaService mediaService;
    private final StorageService storageService;
    private final TaskService taskService;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;

    @Value("${app.upload.chunk-size:8388608}")
    private long defaultChunkSize;

    @Value("${app.upload.session-ttl-hours:24}")
    private long sessionTtlHours;

    public UploadService(MediaFileMapper mediaFileMapper,
                         UploadSessionMapper uploadSessionMapper,
                         MediaService mediaService,
                         StorageService storageService,
                         TaskService taskService,
                         RedissonClient redissonClient,
                         StringRedisTemplate redisTemplate) {
        this.mediaFileMapper = mediaFileMapper;
        this.uploadSessionMapper = uploadSessionMapper;
        this.mediaService = mediaService;
        this.storageService = storageService;
        this.taskService = taskService;
        this.redissonClient = redissonClient;
        this.redisTemplate = redisTemplate;
    }

    public PrecheckResponse precheck(Long userId, PrecheckRequest request) {
        MediaFile existing = findByContent(userId, request.contentHash(), request.size());
        if (existing == null) {
            return new PrecheckResponse(false, null, null);
        }
        return new PrecheckResponse(true, existing.getId(), existing.getStatus());
    }

    public InitUploadResponse init(Long userId, InitUploadRequest request) {
        long chunkSize = request.chunkSize() == null ? defaultChunkSize : request.chunkSize();
        long expectedParts = (request.size() + chunkSize - 1) / chunkSize;
        if (expectedParts != request.totalParts()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "分片数量与文件大小不匹配，期望 " + expectedParts + " 片");
        }

        String hash = request.contentHash().toLowerCase(Locale.ROOT);
        RLock lock = redissonClient.getLock("lock:upload:" + userId + ":" + hash);
        boolean locked = false;
        try {
            locked = lock.tryLock(10, 60, TimeUnit.SECONDS);
            if (!locked) {
                throw new BusinessException(ErrorCode.CONFLICT, "上传初始化繁忙，请稍后重试");
            }
            MediaFile existing = findByContent(userId, hash, request.size());
            if (existing != null) {
                return dedupResponse(existing, chunkSize, request.totalParts());
            }

            MediaFile media = new MediaFile();
            media.setUserId(userId);
            media.setFilename(safeFilename(request.filename()));
            media.setObjectKey(objectKey(userId, media.getFilename()));
            media.setContentHash(hash);
            media.setSize(request.size());
            media.setStatus("UPLOADING");
            media.setProgress(0);
            try {
                mediaFileMapper.insert(media);
            } catch (DuplicateKeyException error) {
                MediaFile concurrent = findByContent(userId, hash, request.size());
                if (concurrent != null) {
                    return dedupResponse(concurrent, chunkSize, request.totalParts());
                }
                throw error;
            }

            UploadSession session = createSession(media, userId, chunkSize, request.totalParts());
            return new InitUploadResponse(media.getId(), session.getUploadId(),
                    List.of(), false, media.getStatus());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "上传初始化被中断");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public UploadPartResponse uploadPart(Long userId, String uploadId, int partNumber,
                                         MultipartFile file) {
        UploadSession session = requireSession(userId, uploadId);
        if (!"UPLOADING".equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "上传会话已结束");
        }
        if (partNumber < 1 || partNumber > session.getTotalParts()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "分片序号超出范围");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "分片内容为空");
        }
        try (InputStream input = file.getInputStream()) {
            storageService.put(chunkKey(uploadId, partNumber), input, file.getSize(),
                    "application/octet-stream");
        } catch (IOException error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "分片读取失败");
        }
        redisTemplate.opsForSet().add(partsKey(uploadId), String.valueOf(partNumber));
        Long uploaded = redisTemplate.opsForSet().size(partsKey(uploadId));
        return new UploadPartResponse(partNumber,
                uploaded == null ? 0 : uploaded.intValue(), session.getTotalParts());
    }

    public CompleteUploadResponse complete(Long userId, String uploadId) {
        UploadSession session = requireSession(userId, uploadId);
        MediaFile media = mediaFileMapper.selectById(session.getMediaId());
        if (media == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "媒体不存在");
        }
        if ("COMPLETED".equals(session.getStatus()) || !"UPLOADING".equals(media.getStatus())) {
            return new CompleteUploadResponse(media.getId(), media.getStatus());
        }

        Set<String> uploaded = redisTemplate.opsForSet().members(partsKey(uploadId));
        List<Integer> missing = IntStream.rangeClosed(1, session.getTotalParts())
                .filter(part -> uploaded == null || !uploaded.contains(String.valueOf(part)))
                .boxed()
                .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "缺少分片: " + missing);
        }

        List<String> sources = IntStream.rangeClosed(1, session.getTotalParts())
                .mapToObj(part -> chunkKey(uploadId, part))
                .toList();
        if (sources.size() == 1) {
            storageService.copy(sources.get(0), media.getObjectKey());
        } else {
            storageService.compose(media.getObjectKey(), sources);
        }

        long actualSize = storageService.size(media.getObjectKey());
        if (actualSize != media.getSize()) {
            storageService.remove(media.getObjectKey());
            throw new BusinessException(ErrorCode.CONFLICT,
                    "合并后的文件大小不一致，请重新上传");
        }

        mediaService.updateProgress(media.getId(), "QUEUED", 0);
        session.setStatus("COMPLETED");
        uploadSessionMapper.updateById(session);
        taskService.createIfAbsent(media.getId(), Stage.PROBE);
        redisTemplate.delete(partsKey(uploadId));
        storageService.removePrefix(chunkPrefix(uploadId));
        log.info("upload_completed mediaId={} parts={} size={}",
                media.getId(), sources.size(), actualSize);
        return new CompleteUploadResponse(media.getId(), "QUEUED");
    }

    public UploadStatusResponse status(Long userId, String uploadId) {
        UploadSession session = requireSession(userId, uploadId);
        return new UploadStatusResponse(session.getMediaId(), uploadId, session.getTotalParts(),
                uploadedParts(uploadId), session.getStatus(), session.getExpiresAt());
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void cleanupExpiredSessions() {
        List<UploadSession> expired = uploadSessionMapper.selectList(
                Wrappers.<UploadSession>lambdaQuery()
                        .eq(UploadSession::getStatus, "UPLOADING")
                        .lt(UploadSession::getExpiresAt, LocalDateTime.now())
                        .last("limit 50"));
        for (UploadSession session : expired) {
            storageService.removePrefix(chunkPrefix(session.getUploadId()));
            redisTemplate.delete(partsKey(session.getUploadId()));
            session.setStatus("EXPIRED");
            uploadSessionMapper.updateById(session);
            MediaFile media = mediaFileMapper.selectById(session.getMediaId());
            if (media != null && "UPLOADING".equals(media.getStatus())) {
                mediaFileMapper.deleteById(media.getId());
            }
        }
        if (!expired.isEmpty()) {
            log.info("upload_sessions_expired count={}", expired.size());
        }
    }

    private InitUploadResponse dedupResponse(MediaFile existing, long chunkSize, int totalParts) {
        if ("UPLOADING".equals(existing.getStatus())) {
            UploadSession session = sessionByMedia(existing.getId());
            if (session == null || !"UPLOADING".equals(session.getStatus())
                    || session.getExpiresAt().isBefore(LocalDateTime.now())) {
                if (session != null) {
                    uploadSessionMapper.deleteById(session.getId());
                }
                session = createSession(existing, existing.getUserId(), chunkSize, totalParts);
            }
            return new InitUploadResponse(existing.getId(), session.getUploadId(),
                    uploadedParts(session.getUploadId()), true, existing.getStatus());
        }
        return new InitUploadResponse(existing.getId(), null, List.of(), true, existing.getStatus());
    }

    private UploadSession createSession(MediaFile media, Long userId, long chunkSize, int totalParts) {
        UploadSession session = new UploadSession();
        session.setMediaId(media.getId());
        session.setUserId(userId);
        session.setUploadId(UUID.randomUUID().toString().replace("-", ""));
        session.setChunkSize(chunkSize);
        session.setTotalParts(totalParts);
        session.setStatus("UPLOADING");
        session.setExpiresAt(LocalDateTime.now().plusHours(sessionTtlHours));
        uploadSessionMapper.insert(session);
        return session;
    }

    private MediaFile findByContent(Long userId, String hash, Long size) {
        LambdaQueryWrapper<MediaFile> query = Wrappers.lambdaQuery(MediaFile.class)
                .eq(MediaFile::getUserId, userId)
                .eq(MediaFile::getContentHash, hash.toLowerCase(Locale.ROOT))
                .eq(MediaFile::getSize, size)
                .last("limit 1");
        return mediaFileMapper.selectOne(query);
    }

    private UploadSession sessionByMedia(Long mediaId) {
        return uploadSessionMapper.selectOne(Wrappers.<UploadSession>lambdaQuery()
                .eq(UploadSession::getMediaId, mediaId)
                .last("limit 1"));
    }

    private UploadSession requireSession(Long userId, String uploadId) {
        UploadSession session = uploadSessionMapper.selectOne(Wrappers.<UploadSession>lambdaQuery()
                .eq(UploadSession::getUploadId, uploadId)
                .last("limit 1"));
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "上传会话不存在");
        }
        return session;
    }

    private List<Integer> uploadedParts(String uploadId) {
        Set<String> members = redisTemplate.opsForSet().members(partsKey(uploadId));
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        List<Integer> parts = new ArrayList<>(members.size());
        for (String member : members) {
            try {
                parts.add(Integer.parseInt(member));
            } catch (NumberFormatException ignored) {
                // 脏数据直接跳过
            }
        }
        parts.sort(Integer::compareTo);
        return parts;
    }

    private String partsKey(String uploadId) {
        return PARTS_PREFIX + uploadId;
    }

    private String chunkPrefix(String uploadId) {
        return "uploads/" + uploadId + "/";
    }

    private String chunkKey(String uploadId, int partNumber) {
        return chunkPrefix(uploadId) + partNumber;
    }

    private String objectKey(Long userId, String filename) {
        return "videos/" + userId + "/" + UUID.randomUUID().toString().replace("-", "")
                + "/" + filename;
    }

    private String safeFilename(String filename) {
        String name = filename.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (name.isEmpty()) {
            name = "video.mp4";
        }
        if (name.length() > 120) {
            name = name.substring(name.length() - 120);
        }
        return name;
    }
}
