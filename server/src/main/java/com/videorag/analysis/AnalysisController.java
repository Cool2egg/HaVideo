package com.videorag.analysis;

import com.videorag.ai.AiQuotaService;
import com.videorag.auth.AuthService;
import com.videorag.common.BusinessException;
import com.videorag.common.ErrorCode;
import com.videorag.common.Result;
import com.videorag.common.ratelimit.RateLimitService;
import com.videorag.common.usage.UsageContext;
import com.videorag.config.RateLimitProperties;
import com.videorag.media.MediaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/analysis")
public class AnalysisController {

    private final AnalysisJobService jobService;
    private final ModeRouter modeRouter;
    private final MediaService mediaService;
    private final AiQuotaService quotaService;
    private final RateLimitService rateLimitService;
    private final RateLimitProperties rateLimitProperties;

    public AnalysisController(AnalysisJobService jobService,
                              ModeRouter modeRouter,
                              MediaService mediaService,
                              AiQuotaService quotaService,
                              RateLimitService rateLimitService,
                              RateLimitProperties rateLimitProperties) {
        this.jobService = jobService;
        this.modeRouter = modeRouter;
        this.mediaService = mediaService;
        this.quotaService = quotaService;
        this.rateLimitService = rateLimitService;
        this.rateLimitProperties = rateLimitProperties;
    }

    @PostMapping("/route")
    public Result<RouteResponse> route(@Valid @RequestBody RouteRequest request,
                                       @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        rateLimitService.require(userId, "analysis:route", rateLimitProperties.getAnalysisRoute());
        UsageContext.set(userId);
        try {
            return Result.ok(new RouteResponse(modeRouter.route(request.goal().trim()).name()));
        } finally {
            UsageContext.clear();
        }
    }

    @PostMapping("/{mediaId}/run")
    public Result<AnalysisJobService.AnalysisJobView> run(
            @PathVariable Long mediaId,
            @RequestParam String goal,
            @RequestParam(required = false) String mode,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwned(userId, mediaId);
        String normalizedGoal = normalizeGoal(goal);
        AnalysisMode analysisMode = AnalysisMode.fromRequest(mode);
        rateLimitService.require(userId, "analysis:run", rateLimitProperties.getAnalysisRun());
        quotaService.requireQuota(userId);
        return Result.ok(jobService.start(mediaId, userId, normalizedGoal, analysisMode));
    }

    @GetMapping("/{mediaId}/status")
    public Result<AnalysisJobService.AnalysisJobView> status(
            @PathVariable Long mediaId,
            @RequestParam String goal,
            @RequestParam(required = false) String mode,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwned(userId, mediaId);
        return Result.ok(jobService.status(mediaId, normalizeGoal(goal),
                AnalysisMode.fromRequest(mode)));
    }

    @GetMapping("/{mediaId}/result")
    public Result<String> result(
            @PathVariable Long mediaId,
            @RequestParam String goal,
            @RequestParam(required = false) String mode,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwned(userId, mediaId);
        String result = jobService.result(mediaId, normalizeGoal(goal),
                AnalysisMode.fromRequest(mode));
        if (result == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "暂无分析结果");
        }
        return Result.ok(result);
    }

    private String normalizeGoal(String goal) {
        if (goal == null || goal.isBlank() || goal.length() > 500) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "分析目标不能为空且不能超过 500 字");
        }
        return goal.trim();
    }

    public record RouteRequest(@NotBlank @Size(max = 500) String goal) {
    }

    public record RouteResponse(String mode) {
    }
}
