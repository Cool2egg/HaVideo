package com.videorag.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Service
public class AsrClient {

    private static final MediaType AUDIO_TYPE = MediaType.parse("audio/wav");

    private final AiProperties properties;
    private final AiUsageService usageService;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    public AsrClient(AiProperties properties, AiUsageService usageService) {
        this.properties = properties;
        this.usageService = usageService;
    }

    public String transcribe(File audioFile) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new AiException("AI 服务未配置（SILICONFLOW_API_KEY）", false);
        }
        return RetrySupport.call("asr", 3, () -> doTranscribe(audioFile));
    }

    private String doTranscribe(File audioFile) {
        RequestBody fileBody = RequestBody.create(audioFile, AUDIO_TYPE);
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", properties.getAsrModel())
                .addFormDataPart("file", audioFile.getName(), fileBody)
                .build();

        Request request = new Request.Builder()
                .url(properties.getAsrUrl())
                .header("Authorization", "Bearer " + properties.getApiKey())
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new AiException("转写接口返回 " + response.code() + ": " + responseBody,
                        retryable(response.code()));
            }
            JSONObject root = JSON.parseObject(responseBody);
            String text = root.getString("text");
            JSONObject usage = root.getJSONObject("usage");
            long seconds = usage == null ? 0 : usage.getLongValue("seconds");
            if (seconds <= 0) {
                seconds = Math.max(1, audioFile.length() / 32_000);
            }
            usageService.recordAsr(seconds);
            return text == null ? "" : text.trim();
        } catch (IOException error) {
            throw new AiException("转写接口网络异常", true, error);
        }
    }

    private boolean retryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }
}
