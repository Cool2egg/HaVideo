package com.videorag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@MapperScan({
        "com.videorag.auth.mapper",
        "com.videorag.media.mapper",
        "com.videorag.upload.mapper",
        "com.videorag.pipeline.mapper",
        "com.videorag.knowledge.mapper",
        "com.videorag.chat.mapper",
        "com.videorag.analysis",
        "com.videorag.ai.mapper"
})
@SpringBootApplication
public class VideoRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoRagApplication.class, args);
    }
}
