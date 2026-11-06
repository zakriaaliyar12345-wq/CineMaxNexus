package com.cinemax.nexus;

import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.cinemax.nexus.study.*;
import com.cinemax.nexus.subtitle.SubtitleTimeline;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.action.ViewActions.*;

/** Requires the publisher's model ZIP and speech WAV staged in the debug app files. */
@RunWith(AndroidJUnit4.class)
public class OfflineSpeechE2ETest {
    @Test public void realFileSpeechIsIndexedWithSearchableTimestamps() throws Exception {
        MediaTestPermissions.grant(); Context context=ApplicationProvider.getApplicationContext();
        File model=new File(context.getFilesDir(),"speech-test-model.zip");
        File speech=new File(context.getFilesDir(),"speech-test.wav");
        try (InputStream input=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("speech-test.wav");
             OutputStream output=new FileOutputStream(speech)) {
            byte[] bytes=new byte[8192];int count;while((count=input.read(bytes))!=-1)output.write(bytes,0,count);
        }
        if(!SpeechModelStore.ready(context)) {
            assertTrue("Prepare the English model in Study workspace, or stage the official model ZIP before testing",model.isFile());
            try(InputStream input=new FileInputStream(model)){SpeechModelStore.install(context,input,new AtomicBoolean());}
        }
        String key="speech-e2e-"+UUID.randomUUID();
        Intent intent=new Intent(context,StudyActivity.class).putExtra("key",key).putExtra("source",Uri.fromFile(speech).toString())
                .putExtra("title","Real offline speech test").putExtra("duration",30000L);
        try(ActivityScenario<StudyActivity> scene=ActivityScenario.launch(intent)) {
            onView(withId(R.id.study_index)).perform(click());
            onView(withText("Start indexing")).perform(click());
            SubtitleTimeline result=null;
            long deadline=SystemClock.elapsedRealtime()+90000;
            while(SystemClock.elapsedRealtime()<deadline) {
                SystemClock.sleep(750); result=TranscriptStore.read(context,key); if(result!=null)break;
                StudyProcessingService.State state=StudyProcessingService.status.getValue();
                if(state!=null && key.equals(state.mediaKey) && !state.busy && state.message.startsWith(context.getString(R.string.study_job_failed)))fail(state.message);
            }
            assertNotNull("Real speech indexing should finish",result);
            assertTrue("Expected spoken zero in the publisher's fixture",result.search("zero").size()>0);
            assertTrue(result.entries.get(0).startMs>=0);
            SystemClock.sleep(800);
            onView(withId(R.id.study_search)).perform(replaceText("zero"),closeSoftKeyboard());
            MediaTestPermissions.capture("study-speech-search.png");
        }
    }
}
