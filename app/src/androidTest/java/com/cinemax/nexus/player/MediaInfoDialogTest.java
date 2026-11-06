package com.cinemax.nexus.player;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.cinemax.nexus.R;
import com.cinemax.nexus.util.FormatUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Device E2E proof for bug #13 (Media info dialog), course topics 06.1 (UI testing)
 * tied to 04.5 (the RecyclerView tap that gets you into the player).
 *
 * Why it clicks {@code btn_info} programmatically instead of tapping coordinates:
 * the injected-tap + uiautomator path on this emulator races the player's
 * controls auto-hide and its rotation, so the dialog is opened through the view
 * tree (Espresso talks to the real view roots - no accessibility, no pixels) and
 * the three facts are asserted against what MediaStore says about the file.
 */
@RunWith(AndroidJUnit4.class)
public class MediaInfoDialogTest {
    @org.junit.Before public void grantMediaAccess() { com.cinemax.nexus.MediaTestPermissions.grant(); }

    private static final String FILE_NAME = "nexus_long.mp4";

    @Test
    public void mediaInfoTellsTheTruthAboutNexusLong() {
        Context ctx = ApplicationProvider.getApplicationContext();
        long id = -1, size = -1, durationMs = -1;
        int width = -1, height = -1;
        try (Cursor c = ctx.getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.DURATION,
                        MediaStore.Video.VideoColumns.WIDTH, MediaStore.Video.VideoColumns.HEIGHT},
                MediaStore.Video.Media.DISPLAY_NAME + "=?",
                new String[]{FILE_NAME}, null)) {
            assertNotNull("MediaStore query ran", c);
            if (c.moveToFirst()) {
                id = c.getLong(0);
                size = c.getLong(1);
                durationMs = c.getLong(2);
                width = c.getInt(3);
                height = c.getInt(4);
            }
        }
        if (id < 0) {
            fail(FILE_NAME + " is not in MediaStore on this device - test precondition");
            return;
        }

        Intent i = new Intent(ctx, PlayerActivity.class);
        i.setData(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id));
        i.putExtra(PlayerActivity.EXTRA_TITLE, "nexus_long");
        i.putExtra(PlayerActivity.EXTRA_VIDEO_ID, Long.toString(id));
        i.putExtra(PlayerActivity.EXTRA_DURATION, durationMs);

        try (ActivityScenario<PlayerActivity> scenario = ActivityScenario.launch(i)) {
            SystemClock.sleep(2500); // let ExoPlayer prepare the local file (for the video format)

            String lastFailure = "";
            boolean proven = false;
            for (int attempt = 0; attempt < 12 && !proven; attempt++) {
                scenario.onActivity(a -> {
                    View btn = a.findViewById(R.id.btn_info);
                    assertNotNull("btn_info exists in the player layout", btn);
                    btn.performClick(); // works even while the controls overlay is hidden
                });
                try {
                    onView(withText(containsString("Media info")))
                            .check(matches(isDisplayed()));
                    // Size = the FILE's byte count, not the duration-in-ms (the old bug).
                    onView(withText(containsString(
                            FormatUtils.formatSize(size))))
                            .check(matches(isDisplayed()));
                    // Format = the container MIME, not the URI scheme (the old bug).
                    onView(withText(containsString("video/mp4")))
                            .check(matches(isDisplayed()));
                    // Resolution + label classified by the SHORT edge (the old bug).
                    onView(withText(containsString(width + " \u00D7 " + height)))
                            .check(matches(isDisplayed()));
                    onView(withText(containsString(
                            FormatUtils.formatResolution(width, height))))
                            .check(matches(isDisplayed()));
                    proven = true;
                } catch (Throwable t) { // NoMatchingViewException or assertion failure
                    lastFailure = String.valueOf(t.getMessage());
                    // Dismiss whatever partial dialog is up and try again once more data lands.
                    try {
                        onView(withText("OK")).perform(click());
                    } catch (Throwable ignored) {
                        // Nothing to dismiss - fine.
                    }
                    SystemClock.sleep(700);
                }
            }
            assertTrue("Media info dialog never showed all three corrected facts. Last failure: "
                    + lastFailure, proven);
        }
    }
}

