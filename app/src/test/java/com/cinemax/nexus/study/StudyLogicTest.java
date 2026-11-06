package com.cinemax.nexus.study;

import com.cinemax.nexus.subtitle.SubtitleTimeline;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class StudyLogicTest {
    @Test public void timestampsClampAndKeepHours() { assertEquals("00:00:00", StudyText.timestamp(-4)); assertEquals("02:03:04", StudyText.timestamp(7384000)); }
    @Test public void searchesBodyAndUnicode() { assertTrue(StudyText.matches("DAO", "Title", "Room dao notes")); assertTrue(StudyText.matches("سلام", "سلام دنیا")); assertFalse(StudyText.matches("absent", "text")); }
    @Test public void namesStayInsideOneFile() { assertEquals("movie__clip_", StudyText.safeName("movie:/clip?")); assertEquals("CineMax-study", StudyText.safeName("  ")); assertEquals(80, StudyText.safeName(String.join("", Collections.nCopies(100,"a"))).length()); }
    @Test public void rangeValidationHasRealBoundaries() { assertTrue(StudyText.validRange(0,500,1000)); assertFalse(StudyText.validRange(0,499,1000)); assertFalse(StudyText.validRange(-1,500,1000)); assertFalse(StudyText.validRange(0,2000,1000)); }
    @Test public void resamplingDownPreservesDuration() { List<Short> output=new ArrayList<>(); PcmResampler r=new PcmResampler(48000,output::add); for(int i=0;i<480;i++)r.accept(.5f); assertEquals(160,output.size()); assertEquals(16384,(short)output.get(100)); }
    @Test public void resamplingUpInterpolatesAcrossCalls() { List<Short> output=new ArrayList<>(); PcmResampler r=new PcmResampler(8000,output::add); r.accept(0);r.accept(1);r.accept(0);assertEquals(5,output.size());assertEquals(16384,(short)output.get(1));assertEquals(32767,(short)output.get(2)); }
    @Test public void resamplingClampsUntrustedPcm() { List<Short> output=new ArrayList<>();PcmResampler r=new PcmResampler(16000,output::add);r.accept(4);r.accept(-4);assertEquals(32767,(short)output.get(0));assertEquals(-32767,(short)output.get(1)); }
    @Test(expected=IllegalArgumentException.class) public void rejectsInvalidRate() {new PcmResampler(0,value -> {});}
    @Test public void clipCaptionsAreClippedAndRebased() { SubtitleTimeline t=SubtitleTimeline.parse("00:00:01,000 --> 00:00:03,000\nFirst\n\n00:00:04,000 --> 00:00:06,000\nSecond"); SubtitleTimeline clip=SubtitleTimeline.parse(t.toSrtRange(2000,5000));assertEquals(0,clip.entries.get(0).startMs);assertEquals(1000,clip.entries.get(0).endMs);assertEquals(2000,clip.entries.get(1).startMs);assertEquals(3000,clip.entries.get(1).endMs); }
    @Test public void clipWithoutCuesExportsNoFakeText() {assertEquals("",SubtitleTimeline.parse("00:00:01,000 --> 00:00:02,000\nFirst").toSrtRange(3000,4000));}
}
