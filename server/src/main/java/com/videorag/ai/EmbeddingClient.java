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
public class EmbeddingClient {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final AiProperties properties;
    private final AiUsageService usageService;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    public EmbeddingClient(AiProperties properties, AiUsageService usageService) {
        this.properties = properties;
        this.usageService = usageService;
    }

    public List<Double> embed(String text) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new AiException("AI 服务未配置（SILICONFLOW_API_KEY）", false);
        }
        return RetrySupport.call("embedding", 3, () -> doEmbed(text));
    }

    private List<Double> doEmbed(String text) {
        JSONObject body = new JSONObject();
        body.put("model", properties.getEmbeddingModel());
        body.put("input", text);
        body.put("encoding_format", "float");

        Request request = new Request.Builder()
                .url(properties.getBaseUrl() + "/embeddings")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new AiException("向量接口返回 " + response.code(), retryable(response.code()));
            }
            JSONObject root = JSON.parseObject(responseBody);
            JSONArray data = root.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                throw new AiException("向量接口返回为空", true);
            }
            JSONArray vector = data.getJSONObject(0).getJSONArray("embedding");
            List<Double> embedding = new ArrayList<>(vector.size());
            for (int i = 0; i < vector.size(); i++) {
                embedding.add(vector.getDoubleValue(i));
            }
            JSONObject usage = root.getJSONObject("usage");
            long tokens = usage == null ? 0 : usage.getLongValue("total_tokens");
            if (tokens <= 0) tokens = TokenEstimator.estimate(text);
            usageService.recordEmbedding(tokens);
            return embedding;
        } catch (IOException error) {
            throw new AiException("向量接口网络异常", true, error);
        }
    }

    private boolean retryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }
}
