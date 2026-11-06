package com.cinemax.nexus.util;

/** Pure Java parsing shared by the seek dialog and local unit tests. */
public final class PlaybackTime {
    private PlaybackTime() { }

    /** Accept seconds, minutes:seconds or hours:minutes:seconds; reject overflow. */
    public static long parseMillis(String input) {
        if (input == null) throw new IllegalArgumentException("Missing time");
        String[] parts = input.trim().split(":", -1);
        if (parts.length > 3) throw new IllegalArgumentException("Too many fields");
        long seconds = 0;
        try {
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].matches("[0-9]+")) throw new IllegalArgumentException("Use digits");
                long value = Long.parseLong(parts[i]);
                if (i > 0 && value >= 60) throw new IllegalArgumentException("Field exceeds 59");
                seconds = Math.addExact(Math.multiplyExact(seconds, 60), value);
            }
            return Math.multiplyExact(seconds, 1000);
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Time is too large", e);
        }
    }
}
