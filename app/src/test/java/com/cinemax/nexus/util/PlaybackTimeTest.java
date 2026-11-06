package com.cinemax.nexus.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackTimeTest {
    @Test public void parsesAllSupportedFormats() {
        assertEquals(90000, PlaybackTime.parseMillis("90"));
        assertEquals(90000, PlaybackTime.parseMillis(" 1:30 "));
        assertEquals(3723000, PlaybackTime.parseMillis("1:02:03"));
        assertEquals(0, PlaybackTime.parseMillis("00:00"));
    }
    @Test public void rejectsMalformedAndOverflowingInput() {
        String[] invalid = {"", " ", "-1", "1:", "1:60", "1:60:00", "1:2:3:4",
                "1.5", "abc", "9223372036854775807", "99999999999999999999", null};
        for (String input : invalid) {
            try { PlaybackTime.parseMillis(input); fail("Accepted " + input); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
