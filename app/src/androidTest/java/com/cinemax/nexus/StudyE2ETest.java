package com.cinemax.nexus;

import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.*;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.cinemax.nexus.study.*;
import com.cinemax.nexus.subtitle.SubtitleTimeline;
import com.cinemax.nexus.util.VideoTrimmer;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.action.ViewActions.*;

@RunWith(AndroidJUnit4.class)
public class StudyE2ETest {
    @Before public void access() {MediaTestPermissions.grant();}
    private Context context(){return ApplicationProvider.getApplicationContext();}
    private Intent intent(String key) {return new Intent(context(),StudyActivity.class).putExtra("key",key).putExtra("source","file:///sdcard/Movies/nexus_long.mp4").putExtra("title","Android exam study").putExtra("position",1500L).putExtra("duration",16275L);}
    @Test public void notesPersistSearchAndLandscapeWork() throws Exception {
        String key="study-notes-"+UUID.randomUUID();
        try(ActivityScenario<StudyActivity> scene=ActivityScenario.launch(intent(key))) {
            onView(withId(R.id.study_add_note)).perform(click());
            onView(withHint("Note title")).perform(replaceText("DAO revision"));
            onView(withHint("What do you want to remember?")).perform(replaceText("Room runs SQL off the main thread."),closeSoftKeyboard());
            onView(withText("Save")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            SystemClock.sleep(500);scene.recreate();SystemClock.sleep(500);
            onView(withText("DAO revision")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()));
            onView(withId(R.id.study_search)).perform(replaceText("absent"),closeSoftKeyboard());
            onView(withId(R.id.study_empty)).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()));
            onView(withId(R.id.study_search)).perform(replaceText("DAO"),closeSoftKeyboard());
            MediaTestPermissions.capture("study-notes.png");
            scene.onActivity(a -> a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            SystemClock.sleep(1200);
            onView(withText("DAO revision")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()));
            MediaTestPermissions.capture("study-landscape.png");
        } finally {for(StudyNote note:StudyDatabase.get(context()).notes().getSync(key))StudyDatabase.get(context()).notes().delete(note.id,key);}
    }
    @Test public void transcriptSearchReturnsARealSentenceLoop() throws Exception {
        String key="study-transcript-"+UUID.randomUUID();
        TranscriptStore.save(context(),key,"1\n00:00:01,000 --> 00:00:03,000\nAn introduction\n\n2\n00:00:05,000 --> 00:00:07,000\nDatabase lesson\n");
        try(ActivityScenario<StudyActivity> scene=ActivityScenario.launchActivityForResult(intent(key))) {
            SystemClock.sleep(700);
            onView(withId(R.id.study_search)).perform(replaceText("database"),closeSoftKeyboard());
            onView(withText("Database lesson")).perform(click());
            onView(withText("Loop this sentence")).perform(click());
            assertEquals(android.app.Activity.RESULT_OK,scene.getResult().getResultCode());
            assertEquals(5000,scene.getResult().getResultData().getLongExtra("start",-1));
            assertEquals(7000,scene.getResult().getResultData().getLongExtra("end",-1));
        }
    }
    @Test public void snapshotAndRevisionBundleContainUsableAssets() throws Exception {
        Context c=context();Uri source=Uri.parse("file:///sdcard/Movies/nexus_long.mp4");
        Uri snapshot=StudyAssets.capture(c,source,1500);
        CountDownLatch ready=new CountDownLatch(1);Uri[] clip={null};Exception[] failure={null};
        long actual=VideoTrimmer.actualStartMs(c,source,1000);
        VideoTrimmer.trim(c,source,1000000,5000000,new VideoTrimmer.Callback(){public void onFinished(Uri output,boolean scan){clip[0]=output;ready.countDown();}public void onFailed(Exception error){failure[0]=error;ready.countDown();}});
        assertTrue(ready.await(35,TimeUnit.SECONDS));assertNull(failure[0]);assertNotNull(clip[0]);
        android.media.MediaMetadataRetriever metadata=new android.media.MediaMetadataRetriever();
        try {metadata.setDataSource(c,clip[0]);assertTrue(Long.parseLong(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION))>0);assertTrue(Integer.parseInt(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))>0);}
        finally {metadata.release();}
        StudyNote range=new StudyNote();range.label="Study test";range.sourceTitle="Exam";range.positionMs=1000;range.endMs=5000;
        StudyNote note=new StudyNote();note.id=1;note.label="Important";note.body="Remember Room";note.positionMs=1500;
        StudyNote shot=new StudyNote();shot.id=2;shot.kind="snapshot";shot.positionMs=1500;shot.assetUri=snapshot.toString();
        Uri bundle=null;
        try {
            bundle=StudyExports.bundle(c,range,clip[0],actual,SubtitleTimeline.parse("00:00:01,000 --> 00:00:03,000\nDatabase lesson"),Arrays.asList(note,shot));
            Set<String> files=new HashSet<>();
            try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(c.getContentResolver().openInputStream(bundle))) {
                java.util.zip.ZipEntry entry;
                while((entry=zip.getNextEntry())!=null) { files.add(entry.getName()); if(entry.getName().equals("captions.srt")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int count;while((count=zip.read(buffer))!=-1)bytes.write(buffer,0,count);assertEquals(Math.max(0,1000-actual),SubtitleTimeline.parse(bytes.toString("UTF-8")).entries.get(0).startMs);} }
            }
            assertTrue(files.containsAll(Arrays.asList("clip.mp4","captions.srt","notes.md","manifest.json","screenshots/2.png")));
        } finally {StudyAssets.delete(c,snapshot.toString());c.getContentResolver().delete(clip[0],null,null);if(bundle!=null)c.getContentResolver().delete(bundle,null,null);}
    }
    @Test public void modelZipTraversalIsRejected() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(bytes)){zip.putNextEntry(new java.util.zip.ZipEntry(SpeechModelStore.MODEL_NAME+"/../../escape"));zip.write(1);zip.closeEntry();}
        try {SpeechModelStore.install(context(),new ByteArrayInputStream(bytes.toByteArray()),new java.util.concurrent.atomic.AtomicBoolean());fail("Bad model archive should fail");}catch(IOException expected){}
    }
}
