package com.videorag.media;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

public final class ImageHash {

    private ImageHash() {
    }

    public static long dhash(Path imagePath) throws IOException {
        BufferedImage source = ImageIO.read(imagePath.toFile());
        if (source == null) return 0;
        BufferedImage gray = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = gray.createGraphics();
        graphics.drawImage(source, 0, 0, 9, 8, null);
        graphics.dispose();

        long hash = 0;
        int bit = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = gray.getRaster().getSample(x, y, 0);
                int right = gray.getRaster().getSample(x + 1, y, 0);
                if (left > right) hash |= (1L << bit);
                bit++;
            }
        }
        return hash;
    }

    public static int hamming(long left, long right) {
        return Long.bitCount(left ^ right);
    }
}
