package com.videorag.media;

import com.videorag.auth.AuthService;
import com.videorag.common.PageResult;
import com.videorag.common.Result;
import com.videorag.media.dto.KeyframeView;
import com.videorag.media.dto.MediaDetail;
import com.videorag.media.dto.MediaSummary;
import com.videorag.media.dto.SegmentView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/media")
public class MediaController {

    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @GetMapping
    public Result<PageResult<MediaSummary>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(mediaService.list(userId, page, size));
    }

    @GetMapping("/{id}")
    public Result<MediaDetail> detail(@PathVariable Long id,
                                      @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(mediaService.detail(userId, id));
    }

    @GetMapping("/{id}/playback")
    public Result<String> playback(@PathVariable Long id,
                                   @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(mediaService.playbackUrl(userId, id));
    }

    @GetMapping("/{id}/segments")
    public Result<List<SegmentView>> segments(@PathVariable Long id,
                                              @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(mediaService.segments(userId, id));
    }

    @GetMapping("/{id}/keyframes")
    public Result<List<KeyframeView>> keyframes(@PathVariable Long id,
                                                @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(mediaService.keyframes(userId, id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.delete(userId, id);
        return Result.ok();
    }
}
