package com.videorag.pipeline;

import com.videorag.common.FileUtils;
import com.videorag.media.StorageService;
import com.videorag.media.entity.MediaFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class StageSupport {

    private StageSupport() {
    }

    public static Path download(StorageService storage, MediaFile media, Path directory)
            throws IOException {
        Path input = directory.resolve("input" + FileUtils.suffix(media.getFilename()));
        try (InputStream inputStream = storage.get(media.getObjectKey())) {
            Files.copy(inputStream, input, StandardCopyOption.REPLACE_EXISTING);
        }
        return input;
    }

    public static String timeLabel(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long minutes = seconds / 60;
        long remain = seconds % 60;
        return String.format("%02d:%02d", minutes, remain);
    }
}
