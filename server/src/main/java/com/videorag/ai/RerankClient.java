package com.videorag.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class RerankClient {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final AiProperties properties;
    private final AiUsageService usageService;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    public RerankClient(AiProperties properties, AiUsageService usageService) {
        this.properties = properties;
        this.usageService = usageService;
    }

    public List<RerankScore> rerank(String query, List<String> documents, int topN) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank() || documents.isEmpty()) {
            return List.of();
        }
        try {
            return RetrySupport.call("rerank", 2, () -> doRerank(query, documents, topN));
        } catch (RuntimeException error) {
            return List.of();
        }
    }

    private List<RerankScore> doRerank(String query, List<String> documents, int topN) {
        JSONObject body = new JSONObject();
        body.put("model", properties.getRerankModel());
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", topN);
        body.put("return_documents", false);

        Request request = new Request.Builder()
                .url(properties.getBaseUrl() + "/rerank")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new AiException("精排接口返回 " + response.code(), retryable(response.code()));
            }
            JSONObject root = JSON.parseObject(responseBody);
            JSONArray results = root.getJSONArray("results");
            List<RerankScore> scores = new ArrayList<>();
            if (results != null) {
                for (int i = 0; i < results.size(); i++) {
                    JSONObject item = results.getJSONObject(i);
                    scores.add(new RerankScore(item.getIntValue("index"),
                            item.getDoubleValue("relevance_score")));
                }
            }
            long tokens = 0;
            JSONObject meta = root.getJSONObject("meta");
            if (meta != null) {
                JSONObject usage = meta.getJSONObject("tokens");
                if (usage != null) tokens = usage.getLongValue("input_tokens");
            }
            usageService.recordRerank(tokens);
            return scores;
        } catch (IOException error) {
            throw new AiException("精排接口网络异常", true, error);
        }
    }

    private boolean retryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    public record RerankScore(int index, double score) {
    }
}
