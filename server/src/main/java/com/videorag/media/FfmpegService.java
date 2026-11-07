package com.videorag.media;

import com.videorag.config.ToolProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class FfmpegService {

    private static final Logger log = LoggerFactory.getLogger(FfmpegService.class);

    private final ToolProperties tools;

    public FfmpegService(ToolProperties tools) {
        this.tools = tools;
    }

    @PostConstruct
    public void logToolConfig() {
        log.info("media_tools ffmpeg={} ffprobe={} ocr={} tessdata={}",
                ffmpeg(), ffprobe(), tools.getOcrCommand(), tools.getTessdataDir());
    }

    public long probeDurationMs(Path input) {
        ProcessResult result = run(List.of(
                ffprobe(),
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                input.toString()), Duration.ofMinutes(2));
        String value = result.stdout().trim();
        if (value.isEmpty() || value.contains("N/A")) return 0;
        try {
            return Math.round(Double.parseDouble(value) * 1000);
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    public void extractCover(Path input, Path output, long durationMs) {
        double seconds = durationMs > 0 ? Math.min(1.0, durationMs / 10000.0) : 0;
        run(List.of(ffmpeg(),
                "-y", "-ss", String.valueOf(seconds), "-i", input.toString(),
                "-frames:v", "1", "-q:v", "2", output.toString()), Duration.ofMinutes(5));
    }

    public List<Path> splitAudio(Path input, Path directory, int segmentSeconds) {
        run(List.of(ffmpeg(),
                "-y", "-i", input.toString(),
                "-vn", "-ac", "1", "-ar", "16000",
                "-f", "segment", "-segment_time", String.valueOf(segmentSeconds),
                "-reset_timestamps", "1",
                directory.resolve("audio_%04d.wav").toString()), Duration.ofMinutes(30));
        return listFiles(directory, "audio_");
    }

    public List<Frame> extractFrames(Path input, Path directory, int intervalSeconds, int maxFrames) {
        ProcessResult result = run(List.of(ffmpeg(),
                "-y", "-i", input.toString(),
                "-vf", "fps=1/" + intervalSeconds + ",showinfo",
                "-frames:v", String.valueOf(maxFrames),
                "-q:v", "3",
                directory.resolve("frame_%05d.jpg").toString()), Duration.ofMinutes(30));
        List<Path> files = listFiles(directory, "frame_");
        List<Long> timestamps = parseShowinfoTimestamps(result.stderr());
        List<Frame> frames = new ArrayList<>(files.size());
        for (int index = 0; index < files.size(); index++) {
            long timestamp = index < timestamps.size()
                    ? timestamps.get(index)
                    : (long) index * intervalSeconds * 1000;
            frames.add(new Frame(files.get(index), timestamp));
        }
        return frames;
    }

    public ProcessResult run(List<String> command, Duration timeout) {
        ProcessBuilder builder = new ProcessBuilder(command);
        try {
            Process process = builder.start();
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(
                    () -> readQuietly(process.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(
                    () -> readQuietly(process.getErrorStream()));
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("命令执行超时: " + command.get(0));
            }
            String out = stdout.join();
            String err = stderr.join();
            if (process.exitValue() != 0) {
                throw new IllegalStateException("命令执行失败(" + process.exitValue() + "): " + tail(err, 800));
            }
            return new ProcessResult(out, err);
        } catch (IOException error) {
            throw new IllegalStateException("无法执行命令 " + command.get(0)
                    + "，请确认 FFmpeg/Tesseract 已安装并在 PATH 中", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("命令执行被中断", error);
        }
    }

    private List<Long> parseShowinfoTimestamps(String stderr) {
        List<Long> timestamps = new ArrayList<>();
        Matcher matcher = Pattern.compile("pts_time:([0-9]+(?:\\.[0-9]+)?)").matcher(stderr);
        while (matcher.find()) {
            timestamps.add(Math.round(Double.parseDouble(matcher.group(1)) * 1000));
        }
        return timestamps;
    }

    private String ffmpeg() {
        String dir = tools.getFfmpegDir();
        if (dir == null || dir.isBlank()) return "ffmpeg";
        for (String candidate : List.of("ffmpeg", "ffmpeg.exe")) {
            Path path = Path.of(dir, candidate);
            if (Files.exists(path)) return path.toString();
        }
        return "ffmpeg";
    }

    private String ffprobe() {
        String dir = tools.getFfmpegDir();
        if (dir != null && !dir.isBlank()) {
            for (String candidate : List.of("ffprobe", "ffprobe.exe")) {
                Path path = Path.of(dir, candidate);
                if (Files.exists(path)) return path.toString();
            }
        }
        return tools.getFfprobePath();
    }

    private List<Path> listFiles(Path directory, String prefix) {
        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> files = new ArrayList<>(stream
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> path.getFileName().toString().endsWith(".wav")
                            || path.getFileName().toString().endsWith(".jpg"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList());
            return files;
        } catch (IOException error) {
            throw new IllegalStateException("读取临时目录失败", error);
        }
    }

    private String readQuietly(InputStream stream) {
        try {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            return "";
        }
    }

    private String tail(String value, int max) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= max
                ? normalized
                : normalized.substring(normalized.length() - max);
    }

    public record ProcessResult(String stdout, String stderr) {
    }

    public record Frame(Path path, long timestampMs) {
    }
}
