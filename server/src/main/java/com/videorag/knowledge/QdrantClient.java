package com.videorag.knowledge;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class QdrantClient {

    private static final Logger log = LoggerFactory.getLogger(QdrantClient.class);
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final boolean enabled;
    private final String baseUrl;
    private final String collection;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();
    private final AtomicBoolean collectionReady = new AtomicBoolean();

    public QdrantClient(@Value("${vector.qdrant.enabled:true}") boolean enabled,
                        @Value("${vector.qdrant.url:http://localhost:6333}") String baseUrl,
                        @Value("${vector.qdrant.collection:video_chunks}") String collection) {
        this.enabled = enabled;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.collection = collection;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void upsert(Long userId, Long mediaId, int chunkIndex, long startMs, long endMs,
                       String summary, String keywords, String pointId, List<Double> vector) {
        if (!enabled || vector == null || vector.isEmpty()) return;
        ensureCollection(vector.size());

        JSONObject payload = new JSONObject();
        payload.put("userId", userId);
        payload.put("mediaId", mediaId);
        payload.put("chunkIndex", chunkIndex);
        payload.put("startMs", startMs);
        payload.put("endMs", endMs);
        payload.put("summary", summary);
        payload.put("keywords", keywords);

        JSONObject point = new JSONObject();
        point.put("id", pointId);
        point.put("vector", vector);
        point.put("payload", payload);

        JSONArray points = new JSONArray();
        points.add(point);
        JSONObject body = new JSONObject();
        body.put("points", points);

        execute(new Request.Builder()
                .url(baseUrl + "/collections/" + collection + "/points?wait=true")
                .put(RequestBody.create(body.toJSONString(), JSON_TYPE)), "写入向量");
    }

    public List<VectorHit> search(List<Double> vector, Long userId, Long mediaId, int limit) {
        if (!enabled || vector == null || vector.isEmpty()) return List.of();
        ensureCollection(vector.size());

        JSONArray must = new JSONArray();
        JSONObject userCondition = new JSONObject();
        userCondition.put("key", "userId");
        JSONObject userMatch = new JSONObject();
        userMatch.put("value", userId);
        userCondition.put("match", userMatch);
        must.add(userCondition);
        if (mediaId != null) {
            JSONObject mediaCondition = new JSONObject();
            mediaCondition.put("key", "mediaId");
            JSONObject mediaMatch = new JSONObject();
            mediaMatch.put("value", mediaId);
            mediaCondition.put("match", mediaMatch);
            must.add(mediaCondition);
        }
        JSONObject filter = new JSONObject();
        filter.put("must", must);

        JSONObject body = new JSONObject();
        body.put("query", vector);
        body.put("filter", filter);
        body.put("limit", limit);
        body.put("with_payload", true);

        try {
            String response = execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points/query")
                    .post(RequestBody.create(body.toJSONString(), JSON_TYPE)), "向量检索");
            JSONObject root = JSON.parseObject(response);
            JSONObject result = root.getJSONObject("result");
            JSONArray points = result == null ? null : result.getJSONArray("points");
            if (points == null) return List.of();

            List<VectorHit> hits = new ArrayList<>(points.size());
            for (int i = 0; i < points.size(); i++) {
                JSONObject point = points.getJSONObject(i);
                JSONObject payload = point.getJSONObject("payload");
                if (payload == null) continue;
                hits.add(new VectorHit(
                        point.getString("id"),
                        point.getDoubleValue("score"),
                        payload.getLongValue("mediaId"),
                        payload.getIntValue("chunkIndex"),
                        payload.getLongValue("startMs"),
                        payload.getLongValue("endMs"),
                        payload.getString("summary"),
                        payload.getString("keywords")));
            }
            return hits;
        } catch (RuntimeException error) {
            log.warn("qdrant_search_failed mediaId={} userId={}", mediaId, userId, error);
            return List.of();
        }
    }

    public void deleteMedia(Long mediaId) {
        if (!enabled) return;
        JSONObject match = new JSONObject();
        match.put("value", mediaId);
        JSONObject condition = new JSONObject();
        condition.put("key", "mediaId");
        condition.put("match", match);
        JSONArray must = new JSONArray();
        must.add(condition);
        JSONObject filter = new JSONObject();
        filter.put("must", must);
        JSONObject body = new JSONObject();
        body.put("filter", filter);
        try {
            execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points/delete?wait=true")
                    .post(RequestBody.create(body.toJSONString(), JSON_TYPE)), "删除向量");
        } catch (RuntimeException error) {
            log.warn("qdrant_delete_failed mediaId={}", mediaId, error);
        }
    }

    private void ensureCollection(int vectorSize) {
        if (collectionReady.get()) return;
        synchronized (collectionReady) {
            if (collectionReady.get()) return;
            try {
                Request lookup = new Request.Builder()
                        .url(baseUrl + "/collections/" + collection)
                        .get().build();
                try (Response response = client.newCall(lookup).execute()) {
                    if (response.isSuccessful()) {
                        collectionReady.set(true);
                        return;
                    }
                    if (response.code() != 404) {
                        throw new IllegalStateException("Qdrant collection 查询失败: " + response.code());
                    }
                }

                JSONObject vectors = new JSONObject();
                vectors.put("size", vectorSize);
                vectors.put("distance", "Cosine");
                JSONObject body = new JSONObject();
                body.put("vectors", vectors);
                execute(new Request.Builder()
                        .url(baseUrl + "/collections/" + collection)
                        .put(RequestBody.create(body.toJSONString(), JSON_TYPE)), "创建向量集合");

                createIndex("userId");
                createIndex("mediaId");
                collectionReady.set(true);
            } catch (IOException error) {
                throw new IllegalStateException("Qdrant 连接失败", error);
            }
        }
    }

    private void createIndex(String field) {
        JSONObject body = new JSONObject();
        body.put("field_name", field);
        body.put("field_schema", "integer");
        try {
            execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/index?wait=true")
                    .put(RequestBody.create(body.toJSONString(), JSON_TYPE)), "创建 payload 索引");
        } catch (RuntimeException error) {
            log.debug("qdrant_index_create_skipped field={}", field);
        }
    }

    private String execute(Request.Builder request, String action) {
        try (Response response = client.newCall(request.build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Qdrant " + action + "失败: "
                        + response.code() + " " + body);
            }
            return body;
        } catch (IOException error) {
            throw new IllegalStateException("Qdrant " + action + "网络异常", error);
        }
    }

    public record VectorHit(String id, double score, long mediaId, int chunkIndex,
                            long startMs, long endMs, String summary, String keywords) {
    }
}
