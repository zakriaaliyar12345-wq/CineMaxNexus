package com.cinemax.nexus.music;

import java.util.*;
import java.util.regex.*;

/** Pure Java LRC parser: multiple stamps, optional fractions and a global offset. */
public final class TimedLyrics {
    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,7}):([0-5]\\d)(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:([+-]?\\d{1,9})\\]", Pattern.CASE_INSENSITIVE);
    public static final class Line {
        public final long timeMs;
        public final String text;
        public Line(long timeMs, String text) { this.timeMs = timeMs; this.text = text; }
    }
    private TimedLyrics() {}
    public static List<Line> parse(String source) {
        if (source == null || source.length() > 1024 * 1024) return Collections.emptyList();
        long offset = 0;
        Matcher offsets = OFFSET.matcher(source);
        while (offsets.find()) offset = Long.parseLong(offsets.group(1));
        List<Line> result = new ArrayList<>();
        for (String row : source.split("\\r?\\n")) {
            Matcher stamps = STAMP.matcher(row);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (stamps.find()) {
                String fraction = stamps.group(3);
                long ms = fraction == null ? 0 : Long.parseLong((fraction + "00").substring(0, 3));
                times.add(Long.parseLong(stamps.group(1)) * 60000 + Long.parseLong(stamps.group(2)) * 1000 + ms);
                end = stamps.end();
            }
            String text = row.substring(end).trim();
            for (long time : times) {
                if (result.size() >= 10000) break;
                result.add(new Line(Math.max(0, time + offset), text));
            }
            if (result.size() >= 10000) break;
        }
        result.sort(Comparator.comparingLong(line -> line.timeMs));
        return Collections.unmodifiableList(result);
    }
    /** Last line at or before playback; -1 means lyrics have not started yet. */
    public static int activeLine(List<Line> lines, long positionMs) {
        int low = 0, high = lines.size() - 1, result = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).timeMs <= positionMs) { result = mid; low = mid + 1; }
            else high = mid - 1;
        }
        return result;
    }
}
