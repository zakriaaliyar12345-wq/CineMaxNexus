package com.cinemax.nexus;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.cinemax.nexus.player.PlaybackService;
import com.cinemax.nexus.player.PlayerActivity;
import com.cinemax.nexus.ui.SettingsActivity;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Method;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class RotationControlE2ETest {
    private String previousLocales;

    @Before public void setup() {
        MediaTestPermissions.grant();
        main(() -> { if (PlaybackService.active() != null) PlaybackService.active().closePlayback(); });
        // Initialize AppCompat's application context before reading/changing API 33+ locales.
        try (ActivityScenario<SettingsActivity> settings = ActivityScenario.launch(SettingsActivity.class)) {
            settings.onActivity(activity -> previousLocales = AppCompatDelegate.getApplicationLocales().toLanguageTags());
        }
    }

    @After public void cleanup() {
        main(() -> {
            if (PlaybackService.active() != null) PlaybackService.active().closePlayback();
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(previousLocales));
        });
    }

    @Test public void visibleButtonRotatesBothWaysWithoutRestartingVideo() {
        checkRotation("en-US", "rotation-en");
    }

    @Test public void farsiButtonRotatesBothWaysAndPreservesTimelineDirection() {
        checkRotation("fa-AF", "rotation-fa");
    }

    private void checkRotation(String language, String id) {
        try (ActivityScenario<SettingsActivity> settings = ActivityScenario.launch(SettingsActivity.class)) {
            settings.onActivity(activity -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language)));
            long deadline = SystemClock.elapsedRealtime() + 10000;
            boolean[] applied = new boolean[1];
            while (!applied[0] && SystemClock.elapsedRealtime() < deadline) {
                settings.onActivity(activity -> applied[0] = activity.getResources().getConfiguration()
                        .getLocales().get(0).getLanguage().equals(language.substring(0, 2)));
                SystemClock.sleep(100);
            }
            assertTrue("The selected language is applied before opening the video", applied[0]);
        }
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, PlayerActivity.class)
                .setData(Uri.parse("file:///sdcard/Movies/nexus_long.mp4"))
                .putExtra(PlayerActivity.EXTRA_VIDEO_ID, id)
                .putExtra(PlayerActivity.EXTRA_TITLE, "Rotation control")
                .putExtra(PlayerActivity.EXTRA_START_POSITION, 5000L);
        try (ActivityScenario<PlayerActivity> scene = ActivityScenario.launch(intent)) {
            awaitReady(scene);
            ExoPlayer[] engine = new ExoPlayer[1];
            int[] firstOrientation = new int[1];
            scene.onActivity(activity -> {
                engine[0] = PlaybackService.active().player();
                engine[0].pause(); engine[0].seekTo(5000); engine[0].setPlaybackSpeed(1.25f);
                firstOrientation[0] = activity.getResources().getConfiguration().orientation;
                assertEquals(language.substring(0, 2), activity.getResources().getConfiguration().getLocales().get(0).getLanguage());
                reveal(activity);
            });
            for (int i = 0; i < 2; i++) {
                int before = i == 0 ? firstOrientation[0] : opposite(firstOrientation[0]);
                int expected = opposite(before);
                scene.onActivity(activity -> {
                    reveal(activity);
                    View button = activity.findViewById(R.id.btn_rotate);
                    assertEquals(R.id.compact_transport_bar, ((View) button.getParent()).getId());
                    assertEquals(activity.getString(before == Configuration.ORIENTATION_LANDSCAPE
                            ? R.string.switch_to_portrait : R.string.switch_to_landscape), button.getContentDescription());
                });
                onView(withId(R.id.btn_rotate)).check(matches(isDisplayed())).perform(click());
                awaitOrientation(scene, expected);
                scene.onActivity(activity -> {
                    assertSame("Rotation retains the playback engine", engine[0], PlaybackService.active().player());
                    assertFalse("A paused video stays paused", engine[0].getPlayWhenReady());
                    assertEquals(5000, engine[0].getCurrentPosition(), 180);
                    assertEquals(1.25f, engine[0].getPlaybackParameters().speed, .01f);
                    View button = activity.findViewById(R.id.btn_rotate);
                    assertEquals(activity.getString(expected == Configuration.ORIENTATION_LANDSCAPE
                            ? R.string.switch_to_portrait : R.string.switch_to_landscape), button.getContentDescription());
                    Rect visible = new Rect();
                    assertTrue(button.getGlobalVisibleRect(visible));
                    assertEquals(button.getWidth(), visible.width());
                    assertEquals(button.getHeight(), visible.height());
                    assertTrue(button.getWidth() >= 48 * activity.getResources().getDisplayMetrics().density);
                    assertEquals(View.LAYOUT_DIRECTION_LTR, activity.findViewById(R.id.seek_bar).getLayoutDirection());
                    assertTrue(activity.findViewById(R.id.position_text).getX() < activity.findViewById(R.id.duration_text).getX());
                    if (language.startsWith("fa"))
                        assertEquals(View.LAYOUT_DIRECTION_RTL, activity.getWindow().getDecorView().getLayoutDirection());
                    reveal(activity);
                });
                SystemClock.sleep(250);
                MediaTestPermissions.capture(id + (expected == Configuration.ORIENTATION_LANDSCAPE ? "-landscape.png" : "-portrait.png"));
            }
        }
    }

    private static void awaitReady(ActivityScenario<PlayerActivity> scene) {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        boolean[] ready = new boolean[1];
        while (!ready[0] && SystemClock.elapsedRealtime() < deadline) {
            scene.onActivity(activity -> ready[0] = PlaybackService.active() != null
                    && PlaybackService.active().player() != null
                    && PlaybackService.active().player().getPlaybackState() == Player.STATE_READY);
            SystemClock.sleep(100);
        }
        assertTrue("Video prepares before the rotation check", ready[0]);
        SystemClock.sleep(350);
    }

    private static void awaitOrientation(ActivityScenario<PlayerActivity> scene, int orientation) {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        boolean[] ready = new boolean[1];
        while (!ready[0] && SystemClock.elapsedRealtime() < deadline) {
            scene.onActivity(activity -> ready[0] = activity.getResources().getConfiguration().orientation == orientation);
            SystemClock.sleep(100);
        }
        scene.onActivity(activity -> assertTrue("The displayed window switches orientation: expected=" + orientation
                + ", actual=" + activity.getResources().getConfiguration().orientation
                + ", requested=" + activity.getRequestedOrientation(), ready[0]));
        SystemClock.sleep(250);
    }

    private static int opposite(int orientation) {
        return orientation == Configuration.ORIENTATION_LANDSCAPE
                ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
    }

    private static void reveal(PlayerActivity activity) {
        try {
            Method method = PlayerActivity.class.getDeclaredMethod("showControls");
            method.setAccessible(true); method.invoke(activity);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void main(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
}
