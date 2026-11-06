package com.cinemax.nexus;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.*;
import com.cinemax.nexus.player.*;
import com.cinemax.nexus.music.*;
import com.cinemax.nexus.ui.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.lang.reflect.Method;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.action.ViewActions.*;

@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class BackgroundAndFarsiE2ETest {
    private Context context;
    private int previousRepeat;
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    @Before public void setup() {
        MediaTestPermissions.grant(); context=ApplicationProvider.getApplicationContext();
        previousRepeat=com.cinemax.nexus.data.prefs.PrefsManager.getInstance(context).getRepeatMode();
        main(() -> {
            if(PlaybackService.active()!=null) PlaybackService.active().closePlayback(); MusicPlaybackService.closePlayback();
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en-US"));
        });
        SystemClock.sleep(400);
    }
    @After public void cleanup() {
        main(() -> {
            for(Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                if(a instanceof PlayerActivity || a instanceof MusicActivity) a.finish();
            if(PlaybackService.active()!=null) PlaybackService.active().closePlayback();
            MusicPlaybackService.closePlayback();
            com.cinemax.nexus.data.prefs.PrefsManager.getInstance(context).setRepeatMode(previousRepeat);
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en-US"));
        });
        SystemClock.sleep(500);
    }
    private ActivityScenario<PlayerActivity> video(String id) {
        return ActivityScenario.launch(new Intent(context,PlayerActivity.class).setData(Uri.parse("file:///sdcard/Movies/nexus_long.mp4"))
                .putExtra(PlayerActivity.EXTRA_VIDEO_ID,id).putExtra(PlayerActivity.EXTRA_TITLE,"Background playback")
                .putExtra(PlayerActivity.EXTRA_START_POSITION,1000L));
    }
    @Test public void miniPlayerRetainsEnginePositionSpeedAndPauseWhenExpanded() {
        ExoPlayer[] engine=new ExoPlayer[1]; long[] position=new long[1];
        try(ActivityScenario<PlayerActivity> scene=video("background-dock")) {
            SystemClock.sleep(1800);
            scene.onActivity(a -> {
                engine[0]=PlaybackService.active().player(); engine[0].setPlaybackSpeed(1.25f);
                engine[0].seekTo(1000); engine[0].play(); position[0]=1000;
            });
        }
        SystemClock.sleep(700);
        try(ActivityScenario<MainActivity> library=ActivityScenario.launch(MainActivity.class)) {
            SystemClock.sleep(1000);
            library.onActivity(a -> {
                assertSame(engine[0],PlaybackService.active().player());
                assertTrue(engine[0].getCurrentPosition()>position[0]+500);
                assertTrue("Video publishes its own media notification",hasNotification(7201));
                assertEquals(View.VISIBLE,a.findViewById(R.id.global_mini_player).getVisibility());
                a.findViewById(R.id.mini_play).performClick(); assertFalse(engine[0].getPlayWhenReady());
                position[0]=engine[0].getCurrentPosition();
            });
            MediaTestPermissions.capture("mini-player-library.png");
            onView(withId(R.id.mini_open)).perform(click()); SystemClock.sleep(1200);
            main(() -> {
                Activity resumed=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).iterator().next();
                assertTrue(resumed instanceof PlayerActivity); assertSame(engine[0],PlaybackService.active().player());
                assertFalse("Expanding must preserve pause",engine[0].getPlayWhenReady());
                assertEquals(position[0],engine[0].getCurrentPosition(),180);
                assertEquals(1.25f,engine[0].getPlaybackParameters().speed,.01f);
                resumed.findViewById(R.id.btn_back).performClick();
            });
            SystemClock.sleep(600);
            library.onActivity(a -> {
                a.findViewById(R.id.mini_close).performClick();
                if (PlaybackService.active()!=null) assertEquals(0,PlaybackService.active().player().getMediaItemCount());
                assertEquals(View.GONE,a.findViewById(R.id.global_mini_player).getVisibility());
            });
        }
    }
    @Test public void backgroundLoopAndSleepDeadlineSurviveClosingScreen() throws Exception {
        try(ActivityScenario<PlayerActivity> scene=video("background-loop")) {
            SystemClock.sleep(1800);
            scene.onActivity(a -> { PlaybackService.active().setLoop(1000,2500); PlaybackService.active().player().seekTo(1100); PlaybackService.active().player().play(); });
        }
        // A loop seek briefly buffers; observe a real wrap rather than sampling isPlaying
        // at one arbitrary instant, which can land inside that normal buffering interval.
        long[] last={0}; boolean[] wrapped={false};
        for(int sample=0;sample<50 && !wrapped[0];sample++) {
            SystemClock.sleep(100);
            main(() -> {
                ExoPlayer engine=PlaybackService.active().player();
                assertTrue("Playback remains requested while loop seeks buffer",engine.getPlayWhenReady());
                assertNull(engine.getPlayerError());
                long position=engine.getCurrentPosition();
                if(last[0]>1800 && position<last[0]-400) wrapped[0]=true;
                last[0]=position;
            });
        }
        assertTrue("The service seeks back into the loop without an Activity",wrapped[0]);
        main(() -> {
            Intent stale=new Intent(SleepTimerReceiver.ACTION);
            new SleepTimerReceiver().onReceive(context,stale);
            assertTrue("An obsolete alarm cannot pause a new session",PlaybackService.active().player().getPlayWhenReady());
            PlaybackService.active().setSleepTimer(1100);
            new SleepTimerReceiver().onReceive(context,stale);
            assertTrue("An early alarm cannot expire a later timer",PlaybackService.active().player().getPlayWhenReady());
        });
        boolean[] paused={false};
        for(int sample=0;sample<40 && !paused[0];sample++) {
            SystemClock.sleep(100);
            main(() -> paused[0]=!PlaybackService.active().player().getPlayWhenReady());
        }
        assertTrue("The service applies its background sleep deadline",paused[0]);
        main(() -> assertEquals(0,PlaybackService.active().sleepRemaining()));
    }
    @Test public void miniPlayerNextChangesQueueItemAndNotificationReturnTarget() {
        try(ActivityScenario<PlayerActivity> video=video("101")) { SystemClock.sleep(1500); }
        main(() -> {
            com.cinemax.nexus.data.prefs.PrefsManager.getInstance(context).setRepeatMode(0);
            java.util.List<com.cinemax.nexus.data.model.VideoItem> queue=java.util.Arrays.asList(
                new com.cinemax.nexus.data.model.VideoItem(101,"First test video",Uri.parse("file:///sdcard/Movies/nexus_long.mp4"),16275,0,0,0,0,0,null,null,null),
                new com.cinemax.nexus.data.model.VideoItem(102,"Second test video",Uri.parse("file:///sdcard/Movies/nexus_test.mp4"),9558,0,0,0,0,0,null,null,null));
            PlaybackService.active().setQueue(queue);
        });
        try(ActivityScenario<MainActivity> library=ActivityScenario.launch(MainActivity.class)) {
            SystemClock.sleep(700);
            library.onActivity(a -> { assertTrue(a.findViewById(R.id.mini_next).isEnabled()); a.findViewById(R.id.mini_next).performClick(); });
            SystemClock.sleep(800);
            main(() -> {
                assertEquals("102",PlaybackService.active().player().getCurrentMediaItem().mediaId);
                assertEquals("Second test video",PlaybackService.active().player().getMediaMetadata().title);
                assertEquals("102",PlaybackService.active().returnIntent().getStringExtra(PlayerActivity.EXTRA_VIDEO_ID));
                assertFalse(PlaybackService.active().canSkip());
            });
        }
    }
    @Test public void musicAndVideoYieldAudioAndMusicDockWorksInLibrary() {
        try(ActivityScenario<PlayerActivity> scene=video("background-audio-focus")) { SystemClock.sleep(1500); }
        try(ActivityScenario<MusicActivity> music=ActivityScenario.launch(MusicActivity.class)) {
            SystemClock.sleep(900);
            main(() -> {
                PlaybackService.active().player().seekTo(1000); PlaybackService.active().player().play();
                assertTrue("Browsing music must not pause video",PlaybackService.active().player().getPlayWhenReady());
                ExoPlayer audio=MusicPlaybackService.currentPlayer();
                audio.setMediaItem(new MediaItem.Builder().setUri("file:///sdcard/Movies/nexus_long.mp4")
                        .setMediaMetadata(new MediaMetadata.Builder().setTitle("Music dock test").build()).build());
                audio.prepare(); audio.play();
                assertFalse("Starting music pauses video",PlaybackService.active().player().getPlayWhenReady());
            });
            SystemClock.sleep(900);
            assertTrue("Music publishes a separate media notification",hasNotification(7200));
        }
        try(ActivityScenario<MainActivity> library=ActivityScenario.launch(MainActivity.class)) {
            SystemClock.sleep(900);
            library.onActivity(a -> {
                assertEquals("Music dock test",((TextView)a.findViewById(R.id.mini_title)).getText().toString());
                a.findViewById(R.id.mini_play).performClick(); assertFalse(MusicPlaybackService.currentPlayer().getPlayWhenReady());
                a.findViewById(R.id.mini_play).performClick(); assertTrue(MusicPlaybackService.currentPlayer().getPlayWhenReady());
            });
            MediaTestPermissions.capture("mini-player-music.png");
            onView(withId(R.id.mini_open)).perform(click()); SystemClock.sleep(1000);
            onView(withId(R.id.music_play)).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()));
        }
    }
    private boolean hasNotification(int id) {
        android.app.NotificationManager notifications=context.getSystemService(android.app.NotificationManager.class);
        for(android.service.notification.StatusBarNotification notification:notifications.getActiveNotifications())
            if(notification.getId()==id) return true;
        return false;
    }
    @Test public void languagePickerRecreatesRtlScreensWithoutReplacingPlayback() {
        ExoPlayer[] engine=new ExoPlayer[1];
        try(ActivityScenario<PlayerActivity> video=video("farsi-playback")) {
            SystemClock.sleep(1700);
            video.onActivity(a -> { engine[0]=PlaybackService.active().player(); engine[0].pause(); engine[0].seekTo(5000); });
        }
        try(ActivityScenario<SettingsActivity> settings=ActivityScenario.launch(SettingsActivity.class)) {
            settings.onActivity(a -> a.findViewById(R.id.row_language).performClick());
            onView(withText("فارسی (دری)")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click()); SystemClock.sleep(1600);
            settings.onActivity(a -> {
                assertEquals("fa",a.getResources().getConfiguration().getLocales().get(0).getLanguage());
                assertEquals(View.LAYOUT_DIRECTION_RTL,a.getWindow().getDecorView().getLayoutDirection());
                assertTrue(a.getString(R.string.study_model_note).contains("انگلیسی"));
                assertTrue(a.getString(R.string.diagnostics_note).contains("دستگاه"));
                assertSame(engine[0],PlaybackService.active().player()); assertFalse(engine[0].getPlayWhenReady());
                assertEquals(5000,engine[0].getCurrentPosition(),180);
            });
            MediaTestPermissions.capture("farsi-settings.png");
        }
        try(ActivityScenario<MainActivity> library=ActivityScenario.launch(MainActivity.class)) {
            SystemClock.sleep(700);
            library.onActivity(a -> {
                assertEquals(View.LAYOUT_DIRECTION_RTL,a.findViewById(R.id.global_mini_player).getLayoutDirection());
                assertEquals(View.LAYOUT_DIRECTION_LTR,a.findViewById(R.id.mini_progress).getLayoutDirection());
            });
            MediaTestPermissions.capture("farsi-library.png");
            onView(withId(R.id.mini_open)).perform(click()); SystemClock.sleep(1000);
            main(() -> {
                Activity resumed=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).iterator().next();
                assertTrue(resumed instanceof PlayerActivity);
                assertEquals(View.LAYOUT_DIRECTION_LTR,resumed.findViewById(R.id.seek_bar).getLayoutDirection());
                assertTrue(resumed.findViewById(R.id.position_text).getX()<resumed.findViewById(R.id.duration_text).getX());
                assertTrue(resumed.findViewById(R.id.btn_prev).getX()<resumed.findViewById(R.id.btn_next).getX());
                try { Method show=PlayerActivity.class.getDeclaredMethod("showControls"); show.setAccessible(true); show.invoke(resumed); }
                catch(Exception error) { throw new AssertionError(error); }
            });
            SystemClock.sleep(400); // Capture after the controls' fade-in reaches a rendered frame.
            MediaTestPermissions.capture("farsi-player.png");
        }
    }
}
