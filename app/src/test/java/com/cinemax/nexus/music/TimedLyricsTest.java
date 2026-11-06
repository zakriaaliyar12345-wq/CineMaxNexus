package com.cinemax.nexus.music;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class TimedLyricsTest {
    @Test public void fractionsMultipleStampsAndSorting() {
        List<TimedLyrics.Line> lines = TimedLyrics.parse("[ar:Artist]\n[00:12.35][00:02.5]Hello\n[00:03.125]World");
        assertEquals(3, lines.size());
        assertEquals(2500, lines.get(0).timeMs);
        assertEquals(3125, lines.get(1).timeMs);
        assertEquals(12350, lines.get(2).timeMs);
        assertEquals("Hello", lines.get(2).text);
    }
    @Test public void globalOffsetAndNegativeClamp() {
        List<TimedLyrics.Line> lines = TimedLyrics.parse("[00:01]Early\n[00:05]Later\n[offset:-2000]");
        assertEquals(0, lines.get(0).timeMs);
        assertEquals(3000, lines.get(1).timeMs);
    }
    @Test public void seekBeforeBetweenAndAfterLines() {
        List<TimedLyrics.Line> lines = TimedLyrics.parse("[00:02]First\n[00:04]Second");
        assertEquals(-1, TimedLyrics.activeLine(lines, 1999));
        assertEquals(0, TimedLyrics.activeLine(lines, 2000));
        assertEquals(0, TimedLyrics.activeLine(lines, 3999));
        assertEquals(1, TimedLyrics.activeLine(lines, 9000));
    }
    @Test public void malformedMetadataAndFarsi() {
        List<TimedLyrics.Line> lines = TimedLyrics.parse("[title:test]\n[00:99]Bad\n[00:01.20]سلام دنیا\nnot timed");
        assertEquals(1, lines.size());
        assertEquals("سلام دنیا", lines.get(0).text);
        assertTrue(TimedLyrics.parse(null).isEmpty());
        assertTrue(TimedLyrics.parse("plain lyrics").isEmpty());
    }
    @Test public void oversizedInputRejected() {
        assertTrue(TimedLyrics.parse("x".repeat(1024 * 1024 + 1)).isEmpty());
    }
    @Test public void emptyTimedLineClearsPreviousText() {
        List<TimedLyrics.Line> lines = TimedLyrics.parse("[00:01]Sing\n[00:03]\n[00:04]Again");
        assertEquals("", lines.get(TimedLyrics.activeLine(lines, 3500)).text);
    }
}
