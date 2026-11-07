package com.videorag.analysis;

import com.videorag.media.MediaService;
import com.videorag.media.entity.MediaFile;
import com.videorag.common.usage.UsageContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Service
public class AnalysisJobService {

    private final AnalysisAgent agent;
    private final CheckpointService checkpointService;
    private final Executor executor;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public AnalysisJobService(AnalysisAgent agent,
                              CheckpointService checkpointService,
                              @Qualifier("aiTaskExecutor") Executor executor) {
        this.agent = agent;
        this.checkpointService = checkpointService;
        this.executor = executor;
    }

    public AnalysisJobView start(Long mediaId, Long userId, String goal, AnalysisMode mode) {
        String digest = CheckpointService.digest(goal, mode);
        String cached = checkpointService.load(mediaId, digest, mode, "FINAL");
        if (cached != null) {
            return new AnalysisJobView("DONE", 100, "已有分析结果", cached, null);
        }

        String key = key(mediaId, digest, mode);
        Job job = jobs.computeIfAbsent(key, ignored -> new Job());
        synchronized (job) {
            if ("RUNNING".equals(job.status) || "PENDING".equals(job.status)) {
                return job.view(mediaId);
            }
            job.status = "PENDING";
            job.progress = 0;
            job.message = "排队中";
            job.resultJson = null;
            job.error = null;
            executor.execute(() -> {
                UsageContext.set(userId);
                job.status = "RUNNING";
                try {
                    String result = agent.run(mediaId, userId, goal, mode,
                            (progress, message) -> {
                                job.progress = progress;
                                job.message = message;
                            });
                    job.resultJson = result;
                    job.progress = 100;
                    job.message = "分析完成";
                    job.status = "DONE";
                } catch (RuntimeException error) {
                    job.status = "FAILED";
                    job.error = error.getMessage();
                    job.message = "分析失败";
                } finally {
                    UsageContext.clear();
                }
            });
            return job.view(mediaId);
        }
    }

    public AnalysisJobView status(Long mediaId, String goal, AnalysisMode mode) {
        String digest = CheckpointService.digest(goal, mode);
        String cached = checkpointService.load(mediaId, digest, mode, "FINAL");
        if (cached != null) {
            return new AnalysisJobView("DONE", 100, "已有分析结果", cached, null);
        }
        Job job = jobs.get(key(mediaId, digest, mode));
        if (job == null) {
            return new AnalysisJobView("IDLE", 0, "尚未开始", null, null);
        }
        return job.view(mediaId);
    }

    public String result(Long mediaId, String goal, AnalysisMode mode) {
        String digest = CheckpointService.digest(goal, mode);
        return checkpointService.load(mediaId, digest, mode, "FINAL");
    }

    private String key(Long mediaId, String digest, AnalysisMode mode) {
        return mediaId + ":" + digest + ":" + mode.name();
    }

    public record AnalysisJobView(String status, int progress, String message,
                                  String resultJson, String error) {
    }

    private static final class Job {
        private volatile String status = "IDLE";
        private volatile int progress;
        private volatile String message = "";
        private volatile String resultJson;
        private volatile String error;

        private AnalysisJobView view(Long mediaId) {
            return new AnalysisJobView(status, progress, message, resultJson, error);
        }
    }
}
