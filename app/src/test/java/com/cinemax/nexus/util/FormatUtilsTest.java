package com.cinemax.nexus.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Course topic 03.2 (App testing) — plain JVM unit tests for the pure formatting
 * helpers. The resolution assertions encode the real bug this suite exists for:
 * a 1280x720 file used to be labelled "1080p" because the label was derived from
 * the long edge.
 */
public class FormatUtilsTest {

    // ---- formatResolution: classify by the SHORT side ----

    @Test
    public void landscape720pIs720p() {
        assertEquals("720p", FormatUtils.formatResolution(1280, 720));
    }

    @Test
    public void landscape1080pIs1080p() {
        assertEquals("1080p", FormatUtils.formatResolution(1920, 1080));
    }

    @Test
    public void portrait720x1280IsStill720p() {
        assertEquals("720p", FormatUtils.formatResolution(720, 1280));
    }

    @Test
    public void uhdIs2160p() {
        assertEquals("2160p", FormatUtils.formatResolution(3840, 2160));
    }

    @Test
    public void qhdIs1440p() {
        assertEquals("1440p", FormatUtils.formatResolution(2560, 1440));
    }

    @Test
    public void vgaIs480p() {
        assertEquals("480p", FormatUtils.formatResolution(854, 480));
    }

    @Test
    public void unknownResolutionIsEmpty() {
        assertEquals("", FormatUtils.formatResolution(0, 0));
    }

    // ---- formatDuration ----

    @Test
    public void durationUnderAnHourIsMss() {
        assertEquals("0:16", FormatUtils.formatDuration(16_275L));
        assertEquals("1:05", FormatUtils.formatDuration(65_000L));
    }

    @Test
    public void durationOverAnHourIsHms() {
        assertEquals("1:00:00", FormatUtils.formatDuration(3_600_000L));
    }

    @Test
    public void negativeDurationClampsToZero() {
        assertEquals("0:00", FormatUtils.formatDuration(-500L));
    }

    // ---- formatSize ----

    @Test
    public void sizeFallsBackToBytes() {
        assertEquals("0 B", FormatUtils.formatSize(0));
        assertEquals("512 B", FormatUtils.formatSize(512));
    }

    @Test
    public void sizeUsesBinarySteps() {
        assertEquals("1.0 KB", FormatUtils.formatSize(1024));
        assertEquals("730.2 KB", FormatUtils.formatSize(747_756L));
        assertEquals("1.0 MB", FormatUtils.formatSize(1024L * 1024L));
    }

    // ---- formatSpeed ----

    @Test
    public void wholeSpeedsDropDecimals() {
        assertEquals("1x", FormatUtils.formatSpeed(1f));
        assertEquals("2x", FormatUtils.formatSpeed(2f));
    }

    @Test
    public void fractionalSpeedsKeepTwoSignificantDigits() {
        assertEquals("1.5x", FormatUtils.formatSpeed(1.5f));
        assertEquals("0.75x", FormatUtils.formatSpeed(0.75f));
    }

    // ---- clampPosition / percentComplete ----

    @Test
    public void clampKeepsValidPositions() {
        assertEquals(5_000L, FormatUtils.clampPosition(5_000L, 10_000L));
    }

    @Test
    public void clampBoundsOutOfRangeValues() {
        assertEquals(0L, FormatUtils.clampPosition(-1L, 10_000L));
        assertEquals(10_000L, FormatUtils.clampPosition(99_999L, 10_000L));
    }

    @Test
    public void percentNeedsAKnownDuration() {
        assertEquals(0, FormatUtils.percentComplete(5_000L, 0L));
        assertEquals(50, FormatUtils.percentComplete(5_000L, 10_000L));
        assertEquals(100, FormatUtils.percentComplete(15_000L, 10_000L));
    }

    @Test
    public void signedSecondsCarryTheirSign() {
        assertEquals("+1.5s", FormatUtils.formatSignedSeconds(1.5f));
        assertEquals("-0.3s", FormatUtils.formatSignedSeconds(-0.3f));
    }

    @Test
    public void percentNeverExceedsHundred() {
        assertTrue(FormatUtils.percentComplete(Long.MAX_VALUE, 1_000L) <= 100);
    }
}
