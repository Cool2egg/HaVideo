package com.videorag.analysis;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videorag.ai.AiClient;
import com.videorag.common.BusinessException;
import com.videorag.common.ErrorCode;
import com.videorag.knowledge.entity.VideoSegment;
import com.videorag.knowledge.mapper.VideoSegmentMapper;
import com.videorag.media.entity.MediaFile;
import com.videorag.media.mapper.MediaFileMapper;
import com.videorag.pipeline.StageSupport;
import com.videorag.rag.Evidence;
import com.videorag.rag.HybridRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AnalysisAgent {

    private static final Logger log = LoggerFactory.getLogger(AnalysisAgent.class);

    private final HybridRetriever retriever;
    private final AiClient aiClient;
    private final MediaFileMapper mediaFileMapper;
    private final VideoSegmentMapper segmentMapper;
    private final CheckpointService checkpointService;

    public AnalysisAgent(HybridRetriever retriever,
                         AiClient aiClient,
                         MediaFileMapper mediaFileMapper,
                         VideoSegmentMapper segmentMapper,
                         CheckpointService checkpointService) {
        this.retriever = retriever;
        this.aiClient = aiClient;
        this.mediaFileMapper = mediaFileMapper;
        this.segmentMapper = segmentMapper;
        this.checkpointService = checkpointService;
    }

    public String run(Long mediaId, Long userId, String goal, AnalysisMode mode,
                      ProgressCallback callback) {
        String digest = CheckpointService.digest(goal, mode);
        String cached = checkpointService.load(mediaId, digest, mode, "FINAL");
        if (cached != null) {
            callback.update(100, "命中已有分析结果");
            return cached;
        }

        MediaFile media = mediaFileMapper.selectById(mediaId);
        if (media == null) throw new BusinessException(ErrorCode.NOT_FOUND, "视频不存在");
        long segmentCount = segmentMapper.selectCount(
                Wrappers.<VideoSegment>lambdaQuery()
                        .eq(VideoSegment::getMediaId, mediaId)
                        .eq(VideoSegment::getSource, "MERGED"));
        if (segmentCount == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "视频尚未完成解析，请稍后再试");
        }

        Map<String, Evidence> evidencePool = new LinkedHashMap<>();
        callback.update(10, "规划分析任务");
        String planRaw = aiClient.chat(
                "你是视频分析 Planner，只输出合法 JSON，不要解释。",
                plannerPrompt(goal, mode, media, segmentCount), true);
        List<String> tasks = parseTasks(planRaw, goal);
        checkpointService.save(mediaId, digest, mode, "PLAN", planRaw);

        List<TaskResult> results = new ArrayList<>();
        for (int index = 0; index < tasks.size(); index++) {
            String task = tasks.get(index);
            callback.update(20 + (int) (35.0 * index / Math.max(1, tasks.size())),
                    "执行任务：" + task);
            List<Evidence> evidence = retrieve(userId, mediaId, task, 5, evidencePool);
            results.add(executeTask(mode, task, evidence));
        }

        callback.update(60, "检查分析质量");
        String critiqueRaw = aiClient.chat(
                "你是视频分析 Critic，只输出合法 JSON，不要解释。",
                criticPrompt(goal, mode, results, evidencePool), true);
        checkpointService.save(mediaId, digest, mode, "CRITIQUE", critiqueRaw);

        if (!parsePass(critiqueRaw)) {
            List<String> missing = parseArray(critiqueRaw, "missing");
            for (String query : missing.stream().limit(4).toList()) {
                List<Evidence> evidence = retrieve(userId, mediaId, query, 3, evidencePool);
                if (!evidence.isEmpty()) {
                    results.add(executeTask(mode, query, evidence));
                }
            }
        }

        callback.update(80, "生成最终报告");
        String finalRaw = aiClient.chat(finalSystem(), finalPrompt(goal, mode, results, evidencePool),
                true);
        String finalJson = normalizeFinal(finalRaw, goal, mode);
        checkpointService.save(mediaId, digest, mode, "FINAL", finalJson);
        callback.update(100, "分析完成");
        log.info("analysis_completed mediaId={} mode={} tasks={} evidence={}",
                mediaId, mode, tasks.size(), evidencePool.size());
        return finalJson;
    }

    private String plannerPrompt(String goal, AnalysisMode mode, MediaFile media, long segmentCount) {
        long durationSeconds = media.getDurationMs() == null ? 0 : media.getDurationMs() / 1000;
        return """
                分析目标：%s
                分析模式：%s（%s）
                视频时长：%d 秒；已切分 %d 个时间片段。
                请把目标拆成 3~6 个互补的任务，每个任务是一句具体的中文检索问题。
                输出 JSON：{"tasks":["任务1","任务2"]}
                """.formatted(goal, mode.name(), mode.instruction(), durationSeconds, segmentCount);
    }

    private TaskResult executeTask(AnalysisMode mode, String task, List<Evidence> evidence) {
        String prompt = """
                分析模式：%s（%s）
                当前任务：%s
                可用证据：
                %s
                请只依据证据输出 JSON：
                {"title":"任务标题","content":"分析结论，句末用 [Vn] 标注依据","keyPoints":["要点"]}
                证据不足时明确写“证据不足”，不要编造；只能使用给出的编号。
                """.formatted(mode.name(), mode.instruction(), task, formatEvidence(evidence));
        String raw = aiClient.chat(
                "你是视频内容分析 Executor，只输出合法 JSON，不要输出 Markdown。",
                prompt, true);
        try {
            JSONObject json = parseObject(raw);
            String title = json.getString("title");
            String content = json.getString("content");
            List<String> keyPoints = parseArray(raw, "keyPoints");
            return new TaskResult(
                    title == null || title.isBlank() ? task : title,
                    content == null || content.isBlank() ? raw : content,
                    keyPoints);
        } catch (RuntimeException error) {
            return new TaskResult(task, raw, List.of());
        }
    }

    private String criticPrompt(String goal, AnalysisMode mode, List<TaskResult> results,
                                Map<String, Evidence> pool) {
        String resultText = results.stream()
                .map(result -> "## " + result.title() + "\n" + result.content()
                        + "\n要点：" + String.join("；", result.keyPoints()))
                .collect(Collectors.joining("\n\n"));
        return """
                分析目标：%s
                分析模式：%s
                已有任务结果：
                %s
                可用证据编号：%s
                请检查：目标是否覆盖、结构是否完整、结论是否都有 [Vn] 引用、时间信息是否合理。
                输出 JSON：{"pass":true或false,"missing":["需要补充检索的问题"],"feedback":["问题"]}
                """.formatted(goal, mode.name(), resultText, String.join(", ",
                pool.values().stream().map(Evidence::ref).toList()));
    }

    private String finalSystem() {
        return "你是视频分析报告生成器，只输出合法 JSON，不要输出 Markdown 代码块。";
    }

    private String finalPrompt(String goal, AnalysisMode mode, List<TaskResult> results,
                               Map<String, Evidence> pool) {
        String resultText = results.stream()
                .map(result -> "## " + result.title() + "\n" + result.content()
                        + "\n要点：" + String.join("；", result.keyPoints()))
                .collect(Collectors.joining("\n\n"));
        String evidenceText = formatEvidence(new ArrayList<>(pool.values()));
        return """
                分析目标：%s
                分析模式：%s（%s）
                任务结果：
                %s
                全部证据：
                %s
                请输出最终 JSON：
                {
                  "title":"报告标题",
                  "summary":"一段总结",
                  "sections":[{"title":"章节","content":"内容，句末用 [Vn]","startMs":0,"endMs":0,"refs":["V1"]}],
                  "keyPoints":["要点"],
                  "extras":{},
                  "citations":["V1"]
                }
                模式附加要求：%s
                只使用给出的证据编号；跨时间点给出 startMs/endMs；只输出 JSON。
                """.formatted(goal, mode.name(), mode.instruction(), resultText,
                evidenceText, mode.outputHint());
    }

    private List<Evidence> retrieve(Long userId, Long mediaId, String query, int limit,
                                    Map<String, Evidence> pool) {
        List<Evidence> local = retriever.retrieve(userId, mediaId, query, limit);
        List<Evidence> global = new ArrayList<>(local.size());
        for (Evidence evidence : local) {
            String key = evidence.mediaId() + ":" + evidence.startMs() + ":" + evidence.endMs();
            Evidence existing = pool.get(key);
            if (existing == null) {
                existing = new Evidence("V" + (pool.size() + 1), evidence.mediaId(),
                        evidence.title(), evidence.startMs(), evidence.endMs(),
                        evidence.snippet(), evidence.transcript(), evidence.ocrTexts(),
                        evidence.source(), evidence.score());
                pool.put(key, existing);
            }
            global.add(existing);
        }
        return global;
    }

    private String formatEvidence(List<Evidence> evidence) {
        if (evidence.isEmpty()) return "（没有检索到证据）";
        return evidence.stream()
                .map(item -> "[" + item.ref() + "]《" + item.title() + "》 "
                        + StageSupport.timeLabel(item.startMs()) + "-"
                        + StageSupport.timeLabel(item.endMs()) + "｜" + item.source()
                        + "｜" + item.snippet())
                .collect(Collectors.joining("\n"));
    }

    private List<String> parseTasks(String raw, String goal) {
        List<String> tasks = parseArray(raw, "tasks");
        if (tasks.isEmpty()) tasks = List.of(goal);
        return tasks.stream().limit(6).toList();
    }

    private boolean parsePass(String raw) {
        try {
            return parseObject(raw).getBooleanValue("pass");
        } catch (RuntimeException error) {
            return true;
        }
    }

    private List<String> parseArray(String raw, String field) {
        try {
            JSONArray array = parseObject(raw).getJSONArray(field);
            if (array == null) return List.of();
            List<String> values = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                Object value = array.get(i);
                if (value instanceof String text && !text.isBlank()) {
                    values.add(text.trim());
                } else if (value instanceof JSONObject json) {
                    values.add(json.toJSONString());
                }
            }
            return values;
        } catch (RuntimeException error) {
            return List.of();
        }
    }

    private JSONObject parseObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("不是合法 JSON");
        }
        return JSON.parseObject(raw.substring(start, end + 1));
    }

    private String normalizeFinal(String raw, String goal, AnalysisMode mode) {
        try {
            JSONObject json = parseObject(raw);
            json.put("goal", goal);
            json.put("mode", mode.name());
            if (!json.containsKey("sections")) json.put("sections", new JSONArray());
            if (!json.containsKey("keyPoints")) json.put("keyPoints", new JSONArray());
            if (!json.containsKey("extras")) json.put("extras", new JSONObject());
            if (!json.containsKey("citations")) json.put("citations", new JSONArray());
            return json.toJSONString();
        } catch (RuntimeException error) {
            JSONObject fallback = new JSONObject();
            fallback.put("goal", goal);
            fallback.put("mode", mode.name());
            fallback.put("title", goal);
            fallback.put("summary", raw);
            fallback.put("sections", new JSONArray());
            fallback.put("keyPoints", new JSONArray());
            fallback.put("extras", new JSONObject());
            fallback.put("citations", new JSONArray());
            return fallback.toJSONString();
        }
    }

    public interface ProgressCallback {
        void update(int progress, String message);
    }

    private record TaskResult(String title, String content, List<String> keyPoints) {
    }
}
