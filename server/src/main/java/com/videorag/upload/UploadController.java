package com.videorag.upload;

import com.videorag.auth.AuthService;
import com.videorag.common.Result;
import com.videorag.common.ratelimit.RateLimitService;
import com.videorag.config.RateLimitProperties;
import com.videorag.upload.dto.CompleteUploadResponse;
import com.videorag.upload.dto.InitUploadRequest;
import com.videorag.upload.dto.InitUploadResponse;
import com.videorag.upload.dto.PrecheckRequest;
import com.videorag.upload.dto.PrecheckResponse;
import com.videorag.upload.dto.UploadPartResponse;
import com.videorag.upload.dto.UploadStatusResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/upload")
public class UploadController {

    private final UploadService uploadService;
    private final RateLimitService rateLimitService;
    private final RateLimitProperties rateLimitProperties;

    public UploadController(UploadService uploadService,
                            RateLimitService rateLimitService,
                            RateLimitProperties rateLimitProperties) {
        this.uploadService = uploadService;
        this.rateLimitService = rateLimitService;
        this.rateLimitProperties = rateLimitProperties;
    }

    @PostMapping("/precheck")
    public Result<PrecheckResponse> precheck(@Valid @RequestBody PrecheckRequest request,
                                             @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        rateLimitService.require(userId, "upload:precheck", rateLimitProperties.getUploadPrecheck());
        return Result.ok(uploadService.precheck(userId, request));
    }

    @PostMapping("/init")
    public Result<InitUploadResponse> init(@Valid @RequestBody InitUploadRequest request,
                                           @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        rateLimitService.require(userId, "upload:init", rateLimitProperties.getUploadInit());
        return Result.ok(uploadService.init(userId, request));
    }

    @PutMapping(value = "/{uploadId}/parts/{partNumber}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<UploadPartResponse> uploadPart(
            @PathVariable String uploadId,
            @PathVariable int partNumber,
            @RequestPart("file") MultipartFile file,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        rateLimitService.require(userId, "upload:part", rateLimitProperties.getUploadPart());
        return Result.ok(uploadService.uploadPart(userId, uploadId, partNumber, file));
    }

    @PostMapping("/{uploadId}/complete")
    public Result<CompleteUploadResponse> complete(
            @PathVariable String uploadId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        rateLimitService.require(userId, "upload:complete", rateLimitProperties.getUploadComplete());
        return Result.ok(uploadService.complete(userId, uploadId));
    }

    @GetMapping("/{uploadId}")
    public Result<UploadStatusResponse> status(
            @PathVariable String uploadId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(uploadService.status(userId, uploadId));
    }
}
