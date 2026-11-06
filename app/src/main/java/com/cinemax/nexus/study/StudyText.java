package com.cinemax.nexus.study;

import java.util.Locale;

/** Small pure functions shared by notebook rows, exports and unit tests. */
public final class StudyText {
    private StudyText() {}
    public static String timestamp(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
    public static boolean matches(String query, String... values) {
        String needle = query.toLowerCase(Locale.ROOT).trim();
        for (String value : values) if (value != null && value.toLowerCase(Locale.ROOT).contains(needle)) return true;
        return needle.isEmpty();
    }
    public static String safeName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return cleaned.isEmpty() ? "CineMax-study" : cleaned.substring(0, Math.min(80, cleaned.length()));
    }
    public static boolean validRange(long start, long end, long duration) {
        return start >= 0 && end - start >= 500 && (duration <= 0 || end <= duration);
    }
}
