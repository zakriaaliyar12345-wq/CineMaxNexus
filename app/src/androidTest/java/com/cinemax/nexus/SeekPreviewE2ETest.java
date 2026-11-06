package com.cinemax.nexus;

import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.media3.exoplayer.ExoPlayer;
import com.cinemax.nexus.player.PlayerActivity;
import org.junit.*;
import org.junit.runner.RunWith;
import java.lang.reflect.*;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.action.ViewActions.*;

@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class SeekPreviewE2ETest {
    private static Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static void set(Object object, String name, Object value) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    @Test public void actualDragShowsFrameThenSeeksAndClearsLoop() throws Exception {
        MediaTestPermissions.grant(); Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, PlayerActivity.class).setData(Uri.parse("file:///sdcard/Movies/nexus_long.mp4"))
                .putExtra(PlayerActivity.EXTRA_VIDEO_ID, "seek-preview-test").putExtra(PlayerActivity.EXTRA_START_POSITION, 1000L);
        try (ActivityScenario<PlayerActivity> scene = ActivityScenario.launch(intent)) {
            SystemClock.sleep(2200); int[] bounds = new int[4];
            scene.onActivity(activity -> {
                ExoPlayer player = (ExoPlayer) field(activity, "player"); player.pause(); player.seekTo(1000);
                set(activity, "mLoopA", 1000L); set(activity, "mLoopB", 3000L);
                try { Method show = activity.getClass().getDeclaredMethod("showControls"); show.setAccessible(true); show.invoke(activity); }
                catch (Exception error) { throw new AssertionError(error); }
                SeekBar bar = activity.findViewById(R.id.seek_bar); int[] location = new int[2]; bar.getLocationOnScreen(location);
                bounds[0] = location[0]; bounds[1] = location[1] + bar.getHeight()/2; bounds[2] = bar.getWidth(); bounds[3] = bar.getHeight();
                assertTrue("The touch target remains comfortable", bar.getHeight() >= 36 * activity.getResources().getDisplayMetrics().density);
            });
            long down = SystemClock.uptimeMillis();
            inject(down, MotionEvent.ACTION_DOWN, bounds[0] + bounds[2] * .2f, bounds[1]);
            inject(down, MotionEvent.ACTION_MOVE, bounds[0] + bounds[2] * .62f, bounds[1]);
            SystemClock.sleep(1600);
            scene.onActivity(activity -> {
                assertEquals(View.VISIBLE, activity.findViewById(R.id.scrub_preview).getVisibility());
                assertNotNull("Real video frame should be decoded", ((ImageView)activity.findViewById(R.id.scrub_frame)).getDrawable());
                ImageView image = activity.findViewById(R.id.scrub_frame);
                assertTrue(image.getWidth() > 0 && image.getHeight() > 0);
                assertEquals(((TextView)activity.findViewById(R.id.scrub_time)).getText().toString(),
                        ((TextView)activity.findViewById(R.id.position_text)).getText().toString());
                android.graphics.Bitmap bitmap = ((android.graphics.drawable.BitmapDrawable)image.getDrawable()).getBitmap();
                assertEquals(255, android.graphics.Color.alpha(bitmap.getPixel(bitmap.getWidth()/2, bitmap.getHeight()/2)));
            });
            SystemClock.sleep(350); // Let the GPU draw the newly assigned thumbnail before evidence capture.
            MediaTestPermissions.capture("seekbar-frame-preview.png");
            inject(down, MotionEvent.ACTION_UP, bounds[0] + bounds[2] * .62f, bounds[1]);
            SystemClock.sleep(700);
            scene.onActivity(activity -> {
                assertEquals(View.GONE, activity.findViewById(R.id.scrub_preview).getVisibility());
                ExoPlayer player = (ExoPlayer) field(activity, "player");
                assertTrue("Release commits the requested seek", player.getCurrentPosition() > 9000 && player.getCurrentPosition() < 11000);
                assertEquals(com.cinemax.nexus.player.BufferedSeekBar.C_UNSET, field(activity, "mLoopA"));
            });
            MediaTestPermissions.capture("seekbar-resting.png");
        }
    }
    @Test public void workspaceLoopAndCaptionsReturnToRealPlayer() throws Exception {
        MediaTestPermissions.grant(); Context context = ApplicationProvider.getApplicationContext();
        String key = "study-player-integration";
        com.cinemax.nexus.study.TranscriptStore.save(context, key,
                "1\n00:00:05,000 --> 00:00:07,000\nStudy playback integration\n");
        Intent intent = new Intent(context, PlayerActivity.class).setData(Uri.parse("file:///sdcard/Movies/nexus_long.mp4"))
                .putExtra(PlayerActivity.EXTRA_VIDEO_ID, key);
        try (ActivityScenario<PlayerActivity> scene = ActivityScenario.launch(intent)) {
            SystemClock.sleep(2000);
            scene.onActivity(activity -> {
                ((ExoPlayer) field(activity, "player")).pause();
                reveal(activity);
            });
            SystemClock.sleep(250);
            onView(withId(R.id.btn_more)).perform(click());
            onView(withText("Study workspace")).perform(click());
            SystemClock.sleep(800);
            onView(withText("Study playback integration")).perform(click());
            onView(withText("Loop this sentence")).perform(click());
            SystemClock.sleep(500);
            scene.onActivity(activity -> {
                assertEquals(5000L, field(activity, "mLoopA")); assertEquals(7000L, field(activity, "mLoopB"));
                ExoPlayer player = (ExoPlayer)field(activity, "player");
                assertTrue(player.getPlayWhenReady()); player.pause(); reveal(activity);
            });
            SystemClock.sleep(250);
            onView(withId(R.id.btn_more)).perform(click());
            onView(withText("Study workspace")).perform(click());
            SystemClock.sleep(500);
            onView(withId(R.id.study_use_captions)).perform(click());
            SystemClock.sleep(1400);
            scene.onActivity(activity -> assertTrue(((com.cinemax.nexus.subtitle.SubtitleStudio)field(activity, "mSubtitleStudio")).hasPrimary()));
        }
    }
    private static void reveal(PlayerActivity activity) {
        try { Method show = activity.getClass().getDeclaredMethod("showControls"); show.setAccessible(true); show.invoke(activity); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static void inject(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(event, true)); }
        finally { event.recycle(); }
    }
}
