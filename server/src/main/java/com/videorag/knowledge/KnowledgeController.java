package com.videorag.knowledge;

import com.videorag.auth.AuthService;
import com.videorag.common.Result;
import com.videorag.common.usage.UsageContext;
import com.videorag.media.MediaService;
import com.videorag.media.entity.MediaFile;
import com.videorag.pipeline.handler.IndexHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {

    private final MediaService mediaService;
    private final IndexHandler indexHandler;

    public KnowledgeController(MediaService mediaService, IndexHandler indexHandler) {
        this.mediaService = mediaService;
        this.indexHandler = indexHandler;
    }

    @PostMapping("/{mediaId}/reindex")
    public Result<Void> reindex(@PathVariable Long mediaId,
                                @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        MediaFile media = mediaService.requireOwned(userId, mediaId);
        UsageContext.set(media.getUserId());
        try {
            indexHandler.reindex(mediaId);
        } finally {
            UsageContext.clear();
        }
        return Result.ok();
    }
}
