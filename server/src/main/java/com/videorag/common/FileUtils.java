package com.videorag.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public final class FileUtils {

    private FileUtils() {
    }

    public static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响主流程
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    public static String suffix(String filename) {
        if (filename == null) return ".mp4";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return ".mp4";
        String suffix = filename.substring(dot).toLowerCase();
        return suffix.length() > 8 ? ".mp4" : suffix;
    }
}
