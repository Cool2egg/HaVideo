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
public class EsClient {

    private static final Logger log = LoggerFactory.getLogger(EsClient.class);
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final boolean enabled;
    private final String baseUrl;
    private final String index;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private final AtomicBoolean indexReady = new AtomicBoolean();

    public EsClient(@Value("${rag.es.enabled:true}") boolean enabled,
                    @Value("${rag.es.url:http://localhost:9200}") String baseUrl,
                    @Value("${rag.es.index:video_knowledge}") String index) {
        this.enabled = enabled;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.index = index;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void bulkIndex(List<KnowledgeDoc> docs) {
        if (!enabled || docs.isEmpty()) return;
        ensureIndex();

        StringBuilder ndjson = new StringBuilder();
        for (KnowledgeDoc doc : docs) {
            JSONObject meta = new JSONObject();
            JSONObject indexMeta = new JSONObject();
            indexMeta.put("_index", index);
            indexMeta.put("_id", doc.id());
            meta.put("index", indexMeta);
            ndjson.append(meta.toJSONString()).append('\n');

            JSONObject source = new JSONObject();
            source.put("text", doc.text());
            source.put("title", doc.title());
            source.put("userId", doc.userId());
            source.put("mediaId", doc.mediaId());
            source.put("startMs", doc.startMs());
            source.put("endMs", doc.endMs());
            source.put("keywords", doc.keywords());
            ndjson.append(source.toJSONString()).append('\n');
        }

        try (Response response = client.newCall(new Request.Builder()
                .url(baseUrl + "/_bulk?refresh=true")
                .header("Content-Type", "application/x-ndjson")
                .post(RequestBody.create(ndjson.toString(), JSON_TYPE))
                .build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("ES 批量写入失败: " + response.code() + " " + body);
            }
            JSONObject result = JSON.parseObject(body);
            if (result != null && result.getBooleanValue("errors")) {
                log.warn("es_bulk_partial_errors count={}", docs.size());
            }
        } catch (IOException error) {
            throw new IllegalStateException("ES 批量写入网络异常", error);
        }
    }

    public List<EsHit> search(String query, Long userId, Long mediaId, int limit) {
        if (!enabled || query == null || query.isBlank()) return List.of();
        try {
            ensureIndex();
            JSONArray must = new JSONArray();
            JSONObject multiMatch = new JSONObject();
            multiMatch.put("query", query);
            multiMatch.put("fields", List.of("title^2", "text", "keywords^1.5"));
            multiMatch.put("type", "best_fields");
            JSONObject mustItem = new JSONObject();
            mustItem.put("multi_match", multiMatch);
            must.add(mustItem);

            JSONArray filter = new JSONArray();
            filter.add(wrapTerm("userId", userId));
            if (mediaId != null) {
                filter.add(wrapTerm("mediaId", mediaId));
            }

            JSONObject bool = new JSONObject();
            bool.put("must", must);
            bool.put("filter", filter);
            JSONObject queryObject = new JSONObject();
            queryObject.put("bool", bool);

            JSONObject body = new JSONObject();
            body.put("size", limit);
            body.put("query", queryObject);
            body.put("_source", List.of("mediaId", "chunkIndex", "startMs", "endMs",
                    "summary", "keywords", "title", "text"));

            try (Response response = client.newCall(new Request.Builder()
                    .url(baseUrl + "/" + index + "/_search")
                    .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                    .build()).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    log.warn("es_search_failed status={} body={}", response.code(), responseBody);
                    return List.of();
                }
                return parseHits(responseBody);
            }
        } catch (IOException | RuntimeException error) {
            log.warn("es_search_unavailable queryChars={}", query.length(), error);
            return List.of();
        }
    }

    public void deleteMedia(Long mediaId) {
        if (!enabled) return;
        JSONObject term = term("mediaId", mediaId);
        JSONObject query = new JSONObject();
        query.put("term", term);
        JSONObject body = new JSONObject();
        body.put("query", query);
        try {
            client.newCall(new Request.Builder()
                    .url(baseUrl + "/" + index + "/_delete_by_query?refresh=true")
                    .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                    .build()).execute().close();
        } catch (IOException error) {
            log.warn("es_delete_failed mediaId={}", mediaId, error);
        }
    }

    private List<EsHit> parseHits(String responseBody) {
        JSONObject root = JSON.parseObject(responseBody);
        JSONObject hitsObject = root.getJSONObject("hits");
        JSONArray hits = hitsObject == null ? null : hitsObject.getJSONArray("hits");
        if (hits == null) return List.of();
        List<EsHit> results = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            JSONObject hit = hits.getJSONObject(i);
            JSONObject source = hit.getJSONObject("_source");
            if (source == null) continue;
            results.add(new EsHit(
                    hit.getString("_id"),
                    hit.getDoubleValue("_score"),
                    source.getLongValue("mediaId"),
                    source.getIntValue("chunkIndex"),
                    source.getLongValue("startMs"),
                    source.getLongValue("endMs"),
                    source.getString("summary"),
                    source.getString("keywords"),
                    source.getString("title"),
                    source.getString("text")));
        }
        return results;
    }

    private JSONObject wrapTerm(String field, Object value) {
        JSONObject wrapper = new JSONObject();
        wrapper.put("term", term(field, value));
        return wrapper;
    }

    private JSONObject term(String field, Object value) {
        JSONObject term = new JSONObject();
        term.put(field, value);
        return term;
    }

    private void ensureIndex() {
        if (indexReady.get()) return;
        synchronized (indexReady) {
            if (indexReady.get()) return;
            try {
                Request head = new Request.Builder().url(baseUrl + "/" + index).head().build();
                try (Response response = client.newCall(head).execute()) {
                    if (response.isSuccessful()) {
                        indexReady.set(true);
                        return;
                    }
                }
                if (!putIndex(mapping("ik_max_word", "ik_smart"))) {
                    log.warn("ik_analyzer_unavailable index={} fallback=standard", index);
                    if (!putIndex(mapping("standard", "standard"))) {
                        throw new IllegalStateException("ES 索引创建失败");
                    }
                }
                indexReady.set(true);
            } catch (IOException error) {
                throw new IllegalStateException("ES 连接失败", error);
            }
        }
    }

    private boolean putIndex(String mappingJson) throws IOException {
        try (Response response = client.newCall(new Request.Builder()
                .url(baseUrl + "/" + index)
                .put(RequestBody.create(mappingJson, JSON_TYPE))
                .build()).execute()) {
            return response.isSuccessful();
        }
    }

    private String mapping(String analyzer, String searchAnalyzer) {
        JSONObject textProps = new JSONObject();
        textProps.put("type", "text");
        textProps.put("analyzer", analyzer);
        textProps.put("search_analyzer", searchAnalyzer);

        JSONObject titleProps = new JSONObject(textProps);
        JSONObject keywordField = new JSONObject();
        keywordField.put("type", "keyword");
        JSONObject fields = new JSONObject();
        fields.put("keyword", keywordField);
        titleProps.put("fields", fields);

        JSONObject properties = new JSONObject();
        properties.put("text", textProps);
        properties.put("title", titleProps);
        properties.put("userId", type("long"));
        properties.put("mediaId", type("long"));
        properties.put("startMs", type("long"));
        properties.put("endMs", type("long"));
        properties.put("keywords", type("keyword"));

        JSONObject mappings = new JSONObject();
        mappings.put("properties", properties);

        JSONObject settings = new JSONObject();
        settings.put("number_of_shards", 1);
        settings.put("number_of_replicas", 0);

        JSONObject root = new JSONObject();
        root.put("settings", settings);
        root.put("mappings", mappings);
        return root.toJSONString();
    }

    private JSONObject type(String type) {
        JSONObject object = new JSONObject();
        object.put("type", type);
        return object;
    }

    public record EsHit(String id, double score, long mediaId, int chunkIndex,
                        long startMs, long endMs, String summary, String keywords,
                        String title, String text) {
    }
}
