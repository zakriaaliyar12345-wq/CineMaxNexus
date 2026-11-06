package com.cinemax.nexus.util;

import androidx.annotation.NonNull;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Pure formatting helpers — unit-testable, no Android dependencies. */
public final class FormatUtils {

    private FormatUtils() {
    }

    /** Formats milliseconds as h:mm:ss or m:ss. */
    @NonNull
    public static String formatDuration(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    /** Formats a signed time delta (e.g. subtitle delay) as "+1.5s" / "-0.3s". */
    @NonNull
    public static String formatSignedSeconds(float seconds) {
        String sign = seconds >= 0 ? "+" : "";
        return String.format(Locale.US, "%s%.1fs", sign, seconds);
    }

    /** Human-readable file size (KB / MB / GB). */
    @NonNull
    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        if (unit == 0) return String.format(Locale.US, "%d %s", (long) value, units[0]);
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    /** Resolution label like "1080p" / "720p" when height is known, else empty. */
    @NonNull
    public static String formatResolution(int width, int height) {
        if (height <= 0) return "";
        // Classify by the SHORT side - landscape height, portrait width. Measuring the
        // long edge (max) tagged every 720p video as "1080p": max(1280, 720) = 1280.
        int h = Math.min(width, height);
        if (h >= 2160) return "2160p";
        if (h >= 1440) return "1440p";
        if (h >= 1080) return "1080p";
        if (h >= 720) return "720p";
        if (h >= 480) return "480p";
        if (h >= 360) return "360p";
        return height + "p";
    }

    /** Playback speed label like "1.5x" / "0.75x". */
    @NonNull
    public static String formatSpeed(float speed) {
        if (speed == (long) speed) {
            return String.format(Locale.US, "%dx", (long) speed);
        }
        return String.format(Locale.US, "%.2gx", speed);
    }

    /** Clamps a seek target into the valid [0, duration] range. */
    public static long clampPosition(long positionMs, long durationMs) {
        if (durationMs <= 0) return Math.max(positionMs, 0);
        return Math.max(0, Math.min(positionMs, durationMs));
    }

    /** Percentage complete (0-100) for resume/progress display. */
    public static int percentComplete(long positionMs, long durationMs) {
        if (durationMs <= 0) return 0;
        long p = clampPosition(positionMs, durationMs);
        return (int) ((p * 100) / durationMs);
    }
}
