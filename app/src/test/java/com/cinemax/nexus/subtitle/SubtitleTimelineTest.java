package com.cinemax.nexus.subtitle;
import org.junit.Test;
import static org.junit.Assert.*;
public class SubtitleTimelineTest {
    private static final String SRT="1\r\n00:00:01,000 --> 00:00:03,000\r\nHello <i>world</i>\r\n\r\n2\r\n00:00:02,000 --> 00:00:04,000\r\nSecond line\r\n";
    @Test public void parsesMarkupAndOverlappingCues(){SubtitleTimeline t=SubtitleTimeline.parse(SRT);assertEquals("Hello world\nSecond line",t.activeAt(2500,0));assertEquals("Second line",t.activeAt(3000,0));assertEquals("",t.activeAt(4000,0));}
    @Test public void positiveAndNegativeDelay(){SubtitleTimeline t=SubtitleTimeline.parse(SRT);assertEquals("",t.activeAt(1000,100));assertEquals("Hello world",t.activeAt(900,-100));}
    @Test public void parsesVttSettingsAndUnicode(){SubtitleTimeline t=SubtitleTimeline.parse("WEBVTT\n\ncue-id\n00:01.000 --> 00:02.000 align:start\nسلام &amp; hello");assertEquals("سلام & hello",t.activeAt(1500,0));}
    @Test public void exportClipsNegativeAndRoundTrips(){SubtitleTimeline t=SubtitleTimeline.parse(SRT);SubtitleTimeline shifted=SubtitleTimeline.parse(t.toSrt(-2000));assertEquals(0,shifted.entries.get(0).startMs);assertEquals(1000,shifted.entries.get(0).endMs);assertEquals(2,shifted.entries.size());}
    @Test public void exportDropsFullyNegativeCues(){assertEquals(1,SubtitleTimeline.parse(SubtitleTimeline.parse(SRT).toSrt(-3000)).entries.size());}
    @Test public void searchIsCaseInsensitive(){assertEquals(1,SubtitleTimeline.parse(SRT).search("WORLD").size());}
    @Test(expected=IllegalArgumentException.class) public void rejectsReversedTime(){SubtitleTimeline.parse("00:00:03,000 --> 00:00:01,000\nBad");}
    @Test(expected=IllegalArgumentException.class) public void rejectsMalformedTimestamp(){SubtitleTimeline.parse("00:99:00,000 --> 01:00:00,000\nBad");}
    @Test(expected=IllegalArgumentException.class) public void rejectsEmptyFile(){SubtitleTimeline.parse("WEBVTT\n\n");}
    @Test public void keepsLongOverlappingCue(){SubtitleTimeline t=SubtitleTimeline.parse("00:00:00,000 --> 00:01:00,000\nLong\n\n00:00:01,000 --> 00:00:02,000\nShort");assertEquals("Long",t.activeAt(59000,0));}
}
