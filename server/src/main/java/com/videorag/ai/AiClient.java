package com.videorag.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final AiProperties properties;
    private final AiUsageService usageService;
    private final OkHttpClient client;

    public AiClient(AiProperties properties, AiUsageService usageService) {
        this.properties = properties;
        this.usageService = usageService;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(properties.getChatTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, false);
    }

    public String chat(String systemPrompt, String userPrompt, boolean jsonMode) {
        requireConfigured();
        return RetrySupport.call("chat", 3, () -> doChat(systemPrompt, userPrompt, jsonMode));
    }

    public ChunkSummary summarizeChunk(String content) {
        String prompt = """
                你是长视频内容整理助手。下面是视频某一段的语音转写与画面 OCR 文本。
                请输出 JSON：{"summary":"120 字以内的中文摘要","keywords":["3-8 个关键词"]}
                摘要要保留具体信息（人物、方法、结论），不要空话。只输出 JSON。
                文本：
                """ + trim(content, 6000);
        try {
            String raw = chat("只输出合法 JSON，不要 Markdown 代码块。", prompt, true);
            JSONObject json = parseJsonObject(raw);
            String summary = json.getString("summary");
            List<String> keywords = new ArrayList<>();
            JSONArray array = json.getJSONArray("keywords");
            if (array != null) {
                for (int i = 0; i < array.size(); i++) {
                    String keyword = array.getString(i);
                    if (keyword != null && !keyword.isBlank()) {
                        keywords.add(keyword.trim());
                    }
                }
            }
            return new ChunkSummary(summary == null ? "" : summary.trim(), keywords);
        } catch (RuntimeException error) {
            log.warn("chunk_summary_fallback error={}", error.getMessage());
            return new ChunkSummary(trim(content, 300), List.of());
        }
    }

    public String finalSummary(String goal, String modeInstruction, String chunkDigest) {
        String prompt = """
                你是视频内容分析助手。分析目标：%s
                模式要求：%s
                下面是视频按时间排列的分块摘要（含时间范围）。请输出 JSON：
                {
                  "oneLine": "一句话总结",
                  "outline": [{"title":"章节标题","startMs":0,"endMs":300000}],
                  "keyPoints": ["要点1","要点2"],
                  "keywords": ["关键词"]
                }
                outline 必须使用给出的时间范围，不要编造时间。只输出 JSON。
                分块摘要：
                %s
                """.formatted(goal, modeInstruction, trim(chunkDigest, 12000));
        try {
            return chat("只输出合法 JSON，不要 Markdown 代码块。", prompt, true);
        } catch (RuntimeException error) {
            log.warn("final_summary_fallback error={}", error.getMessage());
            return "{\"oneLine\":\"摘要生成失败\",\"outline\":[],\"keyPoints\":[],\"keywords\":[]}";
        }
    }

    private String doChat(String systemPrompt, String userPrompt, boolean jsonMode) {
        JSONObject body = new JSONObject();
        body.put("model", properties.getChatModel());

        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", systemPrompt);
        messages.add(system);
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", userPrompt);
        messages.add(user);
        body.put("messages", messages);
        body.put("temperature", jsonMode ? 0.1 : 0.3);

        if (jsonMode) {
            JSONObject format = new JSONObject();
            format.put("type", "json_object");
            body.put("response_format", format);
        }

        Request request = new Request.Builder()
                .url(properties.getBaseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                if (jsonMode && response.code() == 400 && responseBody.contains("response_format")) {
                    return doChat(systemPrompt, userPrompt, false);
                }
                throw new AiException("对话接口返回 " + response.code() + ": " + trim(responseBody, 300),
                        retryable(response.code()));
            }
            JSONObject root = JSON.parseObject(responseBody);
            JSONArray choices = root.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new AiException("对话接口返回为空", true);
            }
            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            String content = message == null ? null : message.getString("content");
            if (content == null || content.isBlank()) {
                throw new AiException("对话结果为空", true);
            }
            recordChatUsage(root, systemPrompt, userPrompt, content);
            return content;
        } catch (IOException error) {
            throw new AiException("对话接口网络异常", true, error);
        }
    }

    private void recordChatUsage(JSONObject root, String systemPrompt,
                                 String userPrompt, String content) {
        JSONObject usage = root.getJSONObject("usage");
        long tokens = usage == null ? 0 : usage.getLongValue("total_tokens");
        if (tokens <= 0) {
            tokens = TokenEstimator.estimate(systemPrompt)
                    + TokenEstimator.estimate(userPrompt)
                    + TokenEstimator.estimate(content);
        }
        usageService.recordChat(tokens);
    }

    private JSONObject parseJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AiException("不是合法 JSON", false);
        }
        return JSON.parseObject(raw.substring(start, end + 1));
    }

    private void requireConfigured() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new AiException("AI 服务未配置（SILICONFLOW_API_KEY）", false);
        }
    }

    private boolean retryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record ChunkSummary(String summary, List<String> keywords) {
    }
}
