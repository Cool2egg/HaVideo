package com.videorag.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {
    private String baseUrl;
    private String apiKey;
    private String chatModel;
    private int chatTimeoutSeconds = 300;
    private String embeddingModel;
    private String rerankModel;
    private String asrUrl;
    private String asrModel;
    private int asrSegmentSeconds = 60;
}
