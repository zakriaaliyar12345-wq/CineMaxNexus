package com.cinemax.nexus;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.MenuItem;
import android.widget.PopupMenu;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.media3.exoplayer.ExoPlayer;
import com.cinemax.nexus.ui.MainActivity;
import com.cinemax.nexus.player.PlayerActivity;
import com.cinemax.nexus.adapter.VideoAdapter;
import com.cinemax.nexus.data.model.VideoItem;
import com.cinemax.nexus.data.MediaRepository;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayList;
import static org.junit.Assert.*;

/** Regression cases for real failures found in the audit, running on an Android device. */
@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class EnhancementRegressionTest {
    @org.junit.Before public void grantMediaAccess() { com.cinemax.nexus.MediaTestPermissions.grant(); }
    private static Object field(Object target, String name) {
        try { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static void set(Object target, String name, Object value) {
        try { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target,value); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args); }
        catch(Exception e) { throw new AssertionError(e); }
    }
    private static VideoItem video(long id,String title) {
        return new VideoItem(id,title,Uri.parse("content://media/external/video/media/"+id),
                60000,1000,1,1,320,240,"Tests","Tests","video/mp4");
    }
    @Test public void sortingKeepsSearchAndSelectAllWorksFromZero() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            SystemClock.sleep(1800);
            scenario.onActivity(a -> {
                set(a,"mAllVideos",new ArrayList<>(Arrays.asList(video(800001,"Exam alpha"),video(800002,"Other beta"))));
                invoke(a,"applyFilterAndSort",new Class<?>[]{String.class},"Exam");
                VideoAdapter adapter=(VideoAdapter)field(a,"mAdapter");
                assertEquals(1,adapter.getItemCount());
                PopupMenu menu=new PopupMenu(a,a.findViewById(R.id.toolbar));
                MenuItem sort=menu.getMenu().add(0,R.id.action_sort_title,0,"Sort");
                a.onOptionsItemSelected(sort);
                assertEquals("Sorting must preserve the search",1,adapter.getItemCount());
                invoke(a,"startSelectionMode",new Class<?>[]{});
                assertEquals(0,adapter.getSelectedCount());
                invoke(a,"handleSelectionAction",new Class<?>[]{int.class},R.id.action_select_all);
                assertEquals(1,adapter.getSelectedCount());
                invoke(a,"handleSelectionAction",new Class<?>[]{int.class},R.id.action_selection_close);
                assertFalse(adapter.isSelectionMode());
                assertNotNull(a.findViewById(R.id.quick_open));
                assertNotNull(a.findViewById(R.id.quick_stream));
                invoke(a,"applyFilterAndSort",new Class<?>[]{String.class},"");
                set(a,"mFavoriteKeys",new java.util.HashSet<>(java.util.Collections.singleton("800001")));
                set(a,"mCollection","favorites");
                invoke(a,"applyFilterAndSort",new Class<?>[]{String.class},(Object)null);
                assertEquals("Favorites collection must use persisted IDs",1,adapter.getItemCount());
                set(a,"mCollection","hd");
                invoke(a,"applyFilterAndSort",new Class<?>[]{String.class},(Object)null);
                assertEquals("Small videos must not appear in HD",0,adapter.getItemCount());
                set(a,"mCollection","all");
            });
        }
    }
    @Test public void searchSurvivesActivityRecreation() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a -> set(a,"mQuery","exam query"));
            scenario.recreate();
            scenario.onActivity(a -> assertEquals("exam query",field(a,"mQuery")));
        }
    }
    @Test public void localPlaybackSeekingSpeedRepeatAndLockWork() {
        Context context=ApplicationProvider.getApplicationContext();
        Intent intent=new Intent(context,PlayerActivity.class)
                .setData(Uri.parse("file:///sdcard/Movies/nexus_long.mp4"))
                .putExtra(PlayerActivity.EXTRA_TITLE,"Regression fixture")
                .putExtra(PlayerActivity.EXTRA_VIDEO_ID,"regression-fixture")
                .putExtra(PlayerActivity.EXTRA_START_POSITION,1000L);
        try(ActivityScenario<PlayerActivity> scenario=ActivityScenario.launch(intent)) {
            SystemClock.sleep(2500);
            scenario.onActivity(a -> {
                ExoPlayer player=(ExoPlayer)field(a,"player");
                assertNull(player.getPlayerError());
                assertTrue("Fixture must be prepared",player.getDuration()>15000);
                player.pause(); player.seekTo(12000);
                assertEquals(12000,player.getCurrentPosition());
                player.setPlaybackSpeed(1.5f);
                assertEquals(1.5f,player.getPlaybackParameters().speed,0.001f);
                invoke(a,"cycleRepeatMode",new Class<?>[]{});
                invoke(a,"toggleAbLoop",new Class<?>[]{});
                assertEquals(12000L,field(a,"mLoopA"));
                invoke(a,"clearAbLoop",new Class<?>[]{});
                invoke(a,"setLocked",new Class<?>[]{boolean.class,boolean.class},true,true);
                assertEquals(true,field(a,"mKidsLock"));
                a.getOnBackPressedDispatcher().onBackPressed();
                assertFalse(a.isFinishing());
                invoke(a,"setLocked",new Class<?>[]{boolean.class,boolean.class},false,false);
                assertEquals(false,field(a,"mLocked"));
                player.setPlaybackSpeed(1f);
                a.onPinch(1.25f);
                assertEquals(1.25f, (Float)field(a,"mZoom"), 0.01f);
                invoke(a,"applySubtitleSize",new Class<?>[]{int.class},125);
                assertNotNull(field(a,"subtitleView"));
                invoke(a,"startSleepTimer",new Class<?>[]{long.class},60000L);
                assertNotNull(field(a,"mSleepTimer"));
                invoke(a,"cancelSleepTimer",new Class<?>[]{});
                assertNull(field(a,"mSleepTimer"));
                invoke(a,"showGoToTimeDialog",new Class<?>[]{});
            });
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers
                    .isAssignableFrom(android.widget.EditText.class))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.replaceText("0:05"));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("OK"))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.click());
            scenario.onActivity(a -> assertEquals(5000,
                    ((ExoPlayer)field(a,"player")).getCurrentPosition()));
            scenario.onActivity(a -> a.findViewById(R.id.btn_more).performClick());
            SystemClock.sleep(800);
            MediaTestPermissions.capture("playback-studio.png");
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Focus mode"))
                    .perform(androidx.test.espresso.action.ViewActions.click());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("OK"))
                    .perform(androidx.test.espresso.action.ViewActions.click());
            scenario.onActivity(a -> {
                assertEquals(true,field(a,"mFocusMode"));
                assertEquals(0.8f,((ExoPlayer)field(a,"player")).getPlaybackParameters().speed,0.001f);
                invoke(a,"endFocusMode",new Class<?>[]{});
                assertEquals(false,field(a,"mFocusMode"));
                assertEquals(1f,((ExoPlayer)field(a,"player")).getPlaybackParameters().speed,0.001f);
            });
        }
    }

    @Test public void roomPlaylistForeignKeyDeletesChildren() {
        Context context=ApplicationProvider.getApplicationContext();
        com.cinemax.nexus.data.db.AppDatabase db=androidx.room.Room.inMemoryDatabaseBuilder(
                context,com.cinemax.nexus.data.db.AppDatabase.class).build();
        try {
            com.cinemax.nexus.data.db.entity.PlaylistEntity list=new com.cinemax.nexus.data.db.entity.PlaylistEntity();
            list.name="Exam playlist"; list.createdAt=1;
            long id=db.playlistDao().insertPlaylist(list);
            com.cinemax.nexus.data.db.entity.PlaylistVideoEntity row=new com.cinemax.nexus.data.db.entity.PlaylistVideoEntity();
            row.playlistId=id; row.videoId="fixture"; row.title="Exam"; row.uri="content://test/video";
            db.playlistDao().insertVideo(row);
            assertEquals(1,db.playlistDao().getVideosForPlaylistSync(id).size());
            db.playlistDao().deletePlaylist(id);
            assertTrue(db.playlistDao().getVideosForPlaylistSync(id).isEmpty());
        } finally { db.close(); }
    }
    @Test public void malformedMediaShowsRecoverableError() {
        Context context=ApplicationProvider.getApplicationContext();
        Intent intent=new Intent(context,PlayerActivity.class)
                .setData(Uri.parse("file:///sdcard/Movies/does-not-exist.mp4"));
        try(ActivityScenario<PlayerActivity> scenario=ActivityScenario.launch(intent)) {
            // The engine is acquired asynchronously from the service. Wait for the real
            // source failure instead of racing decoder/service startup on a busy emulator.
            boolean[] failed={false};
            for(int attempt=0;attempt<50 && !failed[0];attempt++) {
                SystemClock.sleep(200);
                scenario.onActivity(a -> {
                    ExoPlayer p=(ExoPlayer)field(a,"player");
                    failed[0]=p!=null && p.getPlayerError()!=null;
                });
            }
            scenario.onActivity(a -> {
                ExoPlayer player=(ExoPlayer)field(a,"player");
                assertNotNull(player.getPlayerError());
                assertEquals(android.view.View.VISIBLE,((android.view.View)field(a,"mErrorState")).getVisibility());
            });
        }
    }
}

