package com.videorag.media;

import com.videorag.config.ToolProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private final FfmpegService processRunner;
    private final ToolProperties tools;

    public OcrService(FfmpegService processRunner, ToolProperties tools) {
        this.processRunner = processRunner;
        this.tools = tools;
    }

    public String recognize(Path image) {
        List<String> command = new ArrayList<>(List.of(
                tools.getOcrCommand(),
                image.toString(),
                "stdout",
                "-l", "chi_sim+eng",
                "--psm", "6"));
        if (tools.getTessdataDir() != null && !tools.getTessdataDir().isBlank()) {
            command.add("--tessdata-dir");
            command.add(tools.getTessdataDir());
        }
        FfmpegService.ProcessResult result = processRunner.run(command, Duration.ofMinutes(2));
        String text = clean(result.stdout());
        if (text.length() < 2) {
            return "";
        }
        return text;
    }

    private String clean(String raw) {
        if (raw == null) return "";
        String text = Arrays.stream(raw.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.matches("[\\p{Punct}\\s]+"))
                .collect(Collectors.joining(" "));
        text = text.replaceAll("[\\s\\u3000]+", " ").trim();
        return text;
    }
}
