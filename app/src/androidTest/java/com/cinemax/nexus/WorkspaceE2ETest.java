package com.cinemax.nexus;

import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.*;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.espresso.*;
import androidx.media3.exoplayer.ExoPlayer;
import com.cinemax.nexus.player.*;
import com.cinemax.nexus.subtitle.SubtitleStudio;
import com.cinemax.nexus.ui.MainActivity;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.action.ViewActions.*;

@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class WorkspaceE2ETest {
    @Before public void permissions(){MediaTestPermissions.grant();}
    private Object field(Object object,String name){try{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(Exception e){throw new AssertionError(e);}}
    private ActivityScenario<PlayerActivity> launch(){
        Context c=ApplicationProvider.getApplicationContext();
        return ActivityScenario.launch(new Intent(c,PlayerActivity.class).setData(Uri.parse("file:///sdcard/Movies/nexus_long.mp4"))
                .putExtra(PlayerActivity.EXTRA_VIDEO_ID,"workspace-test").putExtra(PlayerActivity.EXTRA_START_POSITION,1000L));
    }
    @Test public void dualCaptionsTranscriptAndDelayWork() throws Exception {
        Context c=ApplicationProvider.getApplicationContext();File file=new File(c.getFilesDir(),"workspace.srt");
        try(FileOutputStream out=new FileOutputStream(file)){out.write("1\n00:00:01,000 --> 00:00:04,000\nHello workspace\n\n2\n00:00:05,000 --> 00:00:08,000\nExam chapter\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        try(ActivityScenario<PlayerActivity> scene=launch()){
            SystemClock.sleep(2200);
            scene.onActivity(a->{ExoPlayer p=(ExoPlayer)field(a,"player");p.pause();p.seekTo(1500);SubtitleStudio studio=(SubtitleStudio)field(a,"mSubtitleStudio");studio.load(Uri.fromFile(file),0,false);studio.load(Uri.fromFile(file),1,false);});
            SystemClock.sleep(1800);
            scene.onActivity(a->{SubtitleStudio studio=(SubtitleStudio)field(a,"mSubtitleStudio");studio.update(1500);assertTrue(studio.hasPrimary());assertTrue(a.findViewById(R.id.compact_transport_bar).getHeight() < a.findViewById(R.id.player_root).getHeight()/4);assertEquals("Hello workspace",((TextView)a.findViewById(R.id.secondary_captions)).getText().toString());try{Method method=a.getClass().getDeclaredMethod("showControls");method.setAccessible(true);method.invoke(a);}catch(Exception e){throw new AssertionError(e);}});
            SystemClock.sleep(300);
            MediaTestPermissions.capture("compact-player.png");
            scene.onActivity(a -> ((SubtitleStudio)field(a,"mSubtitleStudio")).show());
            onView(withText("Search transcript")).perform(scrollTo(),click());
            onView(withHint("Find words in this video…")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(replaceText("Exam"),closeSoftKeyboard());
            onView(withText("0:05  ·  Exam chapter")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            scene.onActivity(a->assertEquals(5000,((ExoPlayer)field(a,"player")).getCurrentPosition()));
            MediaTestPermissions.capture("workspace-transcript.png");
            File exported=new File(c.getFilesDir(),"workspace-corrected.srt");
            scene.onActivity(a -> {
                SubtitleStudio studio=(SubtitleStudio)field(a,"mSubtitleStudio");
                try {
                    Field pending=studio.getClass().getDeclaredField("pendingExport");pending.setAccessible(true);
                    pending.set(studio,"1\n00:00:01,100 --> 00:00:04,100\nHello workspace\n\n");
                } catch(Exception error){throw new AssertionError(error);}
                assertTrue(studio.onResult(2503,android.app.Activity.RESULT_OK,new Intent().setData(Uri.fromFile(exported))));
            });
            SystemClock.sleep(800);
            try(FileInputStream input=new FileInputStream(exported)) {
                byte[] bytes=new byte[512];int count=input.read(bytes);
                assertTrue(new String(bytes,0,count,java.nio.charset.StandardCharsets.UTF_8).contains("00:00:01,100"));
            }
        }
    }
    @Test public void precisionControlsApplyToRealPlayer(){
        try(ActivityScenario<PlayerActivity> scene=launch()){
            SystemClock.sleep(2200);
            scene.onActivity(a->{ExoPlayer p=(ExoPlayer)field(a,"player");p.pause();AdvancedPlaybackTools.showTuning(a,p);});
            onView(withContentDescription("Playback speed")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(progress(150));
            onView(withContentDescription("Audio pitch")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(progress(60));
            onView(withText("Apply")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            scene.onActivity(a->{ExoPlayer p=(ExoPlayer)field(a,"player");assertEquals(1.75f,p.getPlaybackParameters().speed,.001f);assertEquals(1.1f,p.getPlaybackParameters().pitch,.001f);});
        }
    }
    @Test public void libraryCanSwitchBetweenRealRowAndGrid(){
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            SystemClock.sleep(1800);
            scene.onActivity(a->{
                com.cinemax.nexus.data.prefs.PrefsManager prefs=com.cinemax.nexus.data.prefs.PrefsManager.getInstance(a);
                int before=prefs.getGridSpan();a.findViewById(R.id.library_view_toggle).performClick();
                assertEquals(before==1?2:1,prefs.getGridSpan());
                if(prefs.getGridSpan()!=1)a.findViewById(R.id.library_view_toggle).performClick();
                assertEquals(1,prefs.getGridSpan());assertNotNull(a.findViewById(R.id.library_tools));
            });
            MediaTestPermissions.capture("workspace-library.png");
        }
    }
    private static ViewAction progress(int value){return new ViewAction(){
        public org.hamcrest.Matcher<View> getConstraints(){return isAssignableFrom(SeekBar.class);}
        public String getDescription(){return "Set precision control";}
        public void perform(UiController ui,View view){((SeekBar)view).setProgress(value);ui.loopMainThreadUntilIdle();}
    };}
}
