package com.cinemax.nexus.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class StreamRequestTest {
    @Test public void parsesHeadersWithColonInValue(){StreamRequest r=new StreamRequest("example.com/video.mp4","Referer: https://example.com/path\nUser-Agent: CineMax");assertEquals("https://example.com/video.mp4",r.url);assertEquals("https://example.com/path",r.headers.get("Referer"));}
    @Test(expected=IllegalArgumentException.class) public void rejectsBadUrl(){new StreamRequest("https://example.com/a b","");}
    @Test(expected=IllegalArgumentException.class) public void rejectsCredentialsInUrl(){new StreamRequest("https://name:secret@example.com/video","");}
    @Test(expected=IllegalArgumentException.class) public void rejectsHeaderInjection(){new StreamRequest("https://example.com","User-Agent: okay\rInjected: bad");}
    @Test(expected=IllegalArgumentException.class) public void rejectsManagedHeader(){new StreamRequest("https://example.com","Range: bytes=5-");}
    @Test(expected=IllegalArgumentException.class) public void rejectsDuplicateHeader(){new StreamRequest("https://example.com","Origin: one\norigin: two");}
}
