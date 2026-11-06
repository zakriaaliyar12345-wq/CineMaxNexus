package com.cinemax.nexus;

import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.widget.*;
import androidx.lifecycle.Lifecycle;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.cinemax.nexus.music.*;
import com.cinemax.nexus.ui.MainActivity;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.action.ViewActions.*;

@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class MusicE2ETest {
    private static final List<Uri> fixtures = new ArrayList<>();
    private static Context context;
    private static Set<String> originalFavorites;
    @BeforeClass public static void prepareAudio() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        originalFavorites = new HashSet<>(context.getSharedPreferences("music",0).getStringSet("favorites",Collections.emptySet()));
        MediaTestPermissions.grant();
        String grant = "pm grant " + context.getPackageName() + " " + (android.os.Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_AUDIO" : "android.permission.READ_EXTERNAL_STORAGE");
        try(android.os.ParcelFileDescriptor output=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(grant);
            InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(output)) { while(input.read()!=-1) {} }
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29);
        for(int number=1;number<=2;number++) {
            ContentValues values=new ContentValues();
            values.put(MediaStore.Audio.Media.DISPLAY_NAME,"CineMax Music Test "+number+".wav");
            values.put(MediaStore.Audio.Media.TITLE,"CineMax Music Test "+number);
            values.put(MediaStore.Audio.Media.ARTIST,"CineMax Test Artist");
            values.put(MediaStore.Audio.Media.ALBUM,"CineMax Test Album");
            values.put(MediaStore.Audio.Media.MIME_TYPE,"audio/wav");
            values.put(MediaStore.Audio.Media.RELATIVE_PATH,"Music/CineMaxTests");
            values.put(MediaStore.Audio.Media.IS_PENDING,1);
            Uri uri=context.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values);
            assertNotNull(uri);fixtures.add(uri);
            try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(context.getContentResolver().openOutputStream(uri)))) {
                int samples=8000*30,bytes=samples*2;
                out.writeBytes("RIFF");out.writeInt(Integer.reverseBytes(bytes+36));out.writeBytes("WAVEfmt ");out.writeInt(Integer.reverseBytes(16));
                out.writeShort(Short.reverseBytes((short)1));out.writeShort(Short.reverseBytes((short)1));out.writeInt(Integer.reverseBytes(8000));
                out.writeInt(Integer.reverseBytes(16000));out.writeShort(Short.reverseBytes((short)2));out.writeShort(Short.reverseBytes((short)16));
                out.writeBytes("data");out.writeInt(Integer.reverseBytes(bytes));
                for(int sample=0;sample<samples;sample++)out.writeShort(Short.reverseBytes((short)(1000*Math.sin(2*Math.PI*(220+number*110)*sample/8000))));
            }
            values.clear();values.put(MediaStore.Audio.Media.IS_PENDING,0);values.put(MediaStore.Audio.Media.DURATION,30000);
            context.getContentResolver().update(uri,values,null,null);
        }
    }
    @Before public void wake(){MediaTestPermissions.grant();}
    @AfterClass public static void cleanAudio() {
        context.stopService(new Intent(context,MusicPlaybackService.class));
        for(Uri uri:fixtures)context.getContentResolver().delete(uri,null,null);
        context.getSharedPreferences("music",0).edit().putStringSet("favorites",originalFavorites).apply();
    }
    private Object field(Object object,String name) {try{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(Exception e){throw new AssertionError(e);}}
    private void invoke(Object object,String name){try{Method m=object.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(object);}catch(Exception e){throw new AssertionError(e);}}

    @Test public void librarySearchGroupsAndNavigationWork() {
        try(ActivityScenario<MusicActivity> scene=ActivityScenario.launch(MusicActivity.class)) {
            SystemClock.sleep(2000);
            onView(withId(R.id.music_search)).perform(replaceText("CineMax Music Test"),closeSoftKeyboard());
            scene.onActivity(a->assertEquals(2,((List<?>)field(a,"rows")).size()));
            onView(withId(R.id.music_albums)).perform(click());
            scene.onActivity(a->assertTrue(((List<?>)field(a,"rows")).size()>0));
            onView(withId(R.id.music_artists)).perform(click());
            scene.onActivity(a->assertTrue(((List<?>)field(a,"rows")).size()>0));
            onView(withId(R.id.music_songs)).perform(click());
            MediaTestPermissions.capture("music-library.png");
            try {
                android.graphics.drawable.Drawable icon=context.getPackageManager().getApplicationIcon(context.getPackageName());
                assertTrue(icon instanceof android.graphics.drawable.AdaptiveIconDrawable);
                android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(256,256,android.graphics.Bitmap.Config.ARGB_8888);
                icon.setBounds(0,0,256,256);icon.draw(new android.graphics.Canvas(bitmap));
                try(FileOutputStream output=new FileOutputStream(new File(context.getExternalFilesDir(null),"modern-icon.png"))) {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);
                } finally {bitmap.recycle();}
            } catch(Exception error) {throw new AssertionError(error);}
        }
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)) {
            scene.onActivity(a->assertNotNull(a.findViewById(R.id.nav_music)));
        }
    }

    @Test public void importedLyricsFollowSeekAndSurviveRecreation() throws Exception {
        try(ActivityScenario<MusicActivity> scene=ActivityScenario.launch(MusicActivity.class)) {
            SystemClock.sleep(2200);
            onView(withId(R.id.music_search)).perform(replaceText("CineMax Music Test"),closeSoftKeyboard());
            File source = new File(context.getCacheDir(), "immersive-test.lrc");
            try(FileOutputStream output = new FileOutputStream(source)) {
                output.write("[00:00]First line\n[00:05]Second line\n[00:10]سلام دنیا".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            scene.onActivity(a -> {
                try {
                    Method play=a.getClass().getDeclaredMethod("playTrack",AudioTrack.class);play.setAccessible(true);
                    play.invoke(a,((List<?>)field(a,"visibleTracks")).get(0));
                } catch(Exception e){throw new AssertionError(e);}
            });
            SystemClock.sleep(1200);
            scene.onActivity(a -> {
                MediaController player=(MediaController)field(a,"controller");player.pause();
                invoke(a,"showNowPlaying");
                try {
                    Field importing=a.getClass().getDeclaredField("importingLyricsTrack");importing.setAccessible(true);importing.set(a,player.getCurrentMediaItem().mediaId);
                    Method result=a.getClass().getDeclaredMethod("onActivityResult",int.class,int.class,Intent.class);result.setAccessible(true);
                    result.invoke(a,1903,android.app.Activity.RESULT_OK,new Intent().setData(Uri.fromFile(source)));
                } catch(Exception e){throw new AssertionError(e);}
            });
            SystemClock.sleep(1000);
            scene.onActivity(a -> {
                assertEquals(3,((List<?>)field(a,"lyrics")).size());
                android.view.View panel=(android.view.View)field(a,"playerPanel");assertNotNull(panel);
                androidx.recyclerview.widget.RecyclerView lines=panel.findViewById(R.id.music_lyrics_list);
                assertEquals(android.view.View.VISIBLE,lines.getVisibility());
                assertNotNull(lines.findViewHolderForAdapterPosition(1));
                lines.findViewHolderForAdapterPosition(1).itemView.performClick();
                MediaController player=(MediaController)field(a,"controller");assertEquals(5000,player.getCurrentPosition());
                assertEquals(1,((Number)field(a,"activeLyric")).intValue());
            });
            MediaTestPermissions.capture("immersive-lyrics.png");
            scene.recreate();SystemClock.sleep(1800);
            scene.onActivity(a -> {
                assertEquals(3,((List<?>)field(a,"lyrics")).size());
                android.view.View panel=(android.view.View)field(a,"playerPanel");assertNotNull(panel);
                panel.findViewById(R.id.music_lyrics_toggle).performClick();
                assertEquals(android.view.View.VISIBLE,panel.findViewById(R.id.music_cover).getVisibility());
            });
            MediaTestPermissions.capture("immersive-artwork.png");
            scene.onActivity(a -> {
                try { Method file=a.getClass().getDeclaredMethod("lyricsFile",String.class);file.setAccessible(true);((File)file.invoke(a,((MediaController)field(a,"controller")).getCurrentMediaItem().mediaId)).delete(); } catch(Exception e){throw new AssertionError(e);}
            });
            source.delete();
        }
    }

    @Test public void playbackQueueFavoritesSeekAndBackgroundWork() {
        try(ActivityScenario<MusicActivity> scene=ActivityScenario.launch(MusicActivity.class)) {
            SystemClock.sleep(2000);
            onView(withId(R.id.music_search)).perform(replaceText("CineMax Music Test"),closeSoftKeyboard());
            scene.onActivity(a->{
                List<?> tracks=(List<?>)field(a,"visibleTracks");assertEquals(2,tracks.size());
                try{Method m=a.getClass().getDeclaredMethod("playTrack",AudioTrack.class);m.setAccessible(true);m.invoke(a,tracks.get(0));}catch(Exception e){throw new AssertionError(e);}
            });
            SystemClock.sleep(1600);
            scene.onActivity(a->{MediaController player=(MediaController)field(a,"controller");assertNotNull(player);assertNull(player.getPlayerError());assertTrue(player.isPlaying());assertEquals(2,player.getMediaItemCount());invoke(a,"showNowPlaying");});
            onView(withId(R.id.music_shuffle)).perform(scrollTo(),click());
            onView(withId(R.id.music_repeat)).perform(scrollTo(),click());
            onView(withId(R.id.music_favorite)).perform(scrollTo(),click());
            scene.onActivity(a->{MediaController p=(MediaController)field(a,"controller");assertTrue(p.getShuffleModeEnabled());assertEquals(Player.REPEAT_MODE_ONE,p.getRepeatMode());p.seekTo(5000);p.pause();assertTrue(((Set<?>)invokeFavorites(a)).contains(p.getCurrentMediaItem().mediaId));});
            MediaTestPermissions.capture("music-player.png");
            onView(withId(R.id.music_close_player)).perform(scrollTo(),click());
            scene.onActivity(a -> a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            SystemClock.sleep(2000);
            scene.onActivity(a -> invoke(a,"showNowPlaying"));
            onView(withId(R.id.music_play)).check((view,error) -> {
                if(error!=null)throw error;
                android.graphics.Rect bounds=new android.graphics.Rect();
                assertTrue(view.getGlobalVisibleRect(bounds));assertEquals(view.getHeight(),bounds.height());
            });
            MediaTestPermissions.capture("music-landscape.png");
            onView(withId(R.id.music_close_player)).perform(scrollTo(),click());
            scene.onActivity(a->{MediaController p=(MediaController)field(a,"controller");p.setShuffleModeEnabled(false);p.setRepeatMode(Player.REPEAT_MODE_OFF);});
            SystemClock.sleep(500);
            onView(withId(R.id.music_mini_next)).perform(click());
            SystemClock.sleep(500);
            scene.onActivity(a->{MediaController p=(MediaController)field(a,"controller");assertEquals(1,p.getCurrentMediaItemIndex());p.play();});
            scene.moveToState(Lifecycle.State.CREATED);SystemClock.sleep(1200);scene.moveToState(Lifecycle.State.RESUMED);SystemClock.sleep(1000);
            scene.onActivity(a->{MediaController p=(MediaController)field(a,"controller");assertNotNull(p);assertTrue("Music should survive leaving its screen",p.isPlaying());assertTrue(p.getCurrentPosition()>500);p.pause();});
        }
    }
    @Test public void openAudioResultCanPlayAnIndividualFile() {
        try(ActivityScenario<MusicActivity> scene=ActivityScenario.launch(MusicActivity.class)) {
            SystemClock.sleep(1500);
            scene.onActivity(a -> {
                try {
                    Method result=a.getClass().getDeclaredMethod("onActivityResult",int.class,int.class,Intent.class);result.setAccessible(true);
                    result.invoke(a,1902,android.app.Activity.RESULT_OK,new Intent().setData(fixtures.get(0)));
                } catch(Exception error){throw new AssertionError(error);}
            });
            SystemClock.sleep(1600);
            scene.onActivity(a -> {
                MediaController p=(MediaController)field(a,"controller");assertNotNull(p);
                assertNull(p.getPlayerError());assertTrue(p.isPlaying());
                assertTrue(p.getCurrentMediaItem().mediaId.startsWith("document:"));p.pause();
            });
        }
    }
    private Object invokeFavorites(MusicActivity activity){try{Method m=activity.getClass().getDeclaredMethod("favorites");m.setAccessible(true);return m.invoke(activity);}catch(Exception e){throw new AssertionError(e);}}
}
