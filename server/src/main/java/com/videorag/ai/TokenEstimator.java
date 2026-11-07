package com.videorag.ai;

public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static long estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long han = text.codePoints()
                .filter(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)
                .count();
        long rest = Math.max(0, text.length() - han);
        return Math.max(1, han + Math.round(rest / 4.0));
    }
}
