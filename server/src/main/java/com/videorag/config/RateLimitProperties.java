package com.videorag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private Bucket uploadPrecheck = new Bucket();
    private Bucket uploadInit = new Bucket();
    private Bucket uploadPart = new Bucket();
    private Bucket uploadComplete = new Bucket();
    private Bucket ragChat = new Bucket();
    private Bucket analysisRoute = new Bucket();
    private Bucket analysisRun = new Bucket();

    @Data
    public static class Bucket {
        private int capacity = 10;
        private double refillPerSecond = 1;
    }
}
