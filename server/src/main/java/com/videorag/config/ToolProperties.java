package com.videorag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "tool")
public class ToolProperties {
    private String ffmpegDir;
    private String ffprobePath = "ffprobe";
    private String ocrCommand = "tesseract";
    private String tessdataDir;
}
