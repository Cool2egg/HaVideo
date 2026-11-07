package com.videorag.pipeline;

import com.videorag.auth.AuthService;
import com.videorag.common.Result;
import com.videorag.media.MediaService;
import com.videorag.media.dto.MediaDetail;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/pipeline")
public class PipelineController {

    private final MediaService mediaService;
    private final TaskEventService taskEventService;

    public PipelineController(MediaService mediaService, TaskEventService taskEventService) {
        this.mediaService = mediaService;
        this.taskEventService = taskEventService;
    }

    @GetMapping(value = "/{mediaId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long mediaId,
                             @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        MediaDetail media = mediaService.detail(userId, mediaId);
        boolean failed = "FAILED".equals(media.status());
        TaskEventService.ProgressEvent initial = new TaskEventService.ProgressEvent(
                mediaId, media.status(), media.progress(),
                failed ? media.errorStage() : null,
                failed ? media.errorMessage() : "已连接");
        return taskEventService.subscribe(mediaId, initial);
    }

    @GetMapping("/{mediaId}/status")
    public Result<MediaDetail> status(@PathVariable Long mediaId,
                                      @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(mediaService.detail(userId, mediaId));
    }
}
