package com.cinemax.nexus.player;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertNotNull;
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

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Device E2E for course topic 08.2 (alarms): starts the sleep timer from the player's
 * {@code btn_timer}, asserts the countdown badge appears, then <b>holds the player open
 * for 60 seconds</b> so a concurrent {@code adb shell dumpsys alarm} can observe the
 * pending RTC_WAKEUP broadcast for this package. The service retains the deadline when
 * this screen closes; stopping playback or expiration cancels the alarm. The dedicated
 * BackgroundAndFarsiE2ETest verifies that the deadline pauses playback without an Activity.
 *
 * Run together with the other instrumented tests: {@code gradlew connectedDebugAndroidTest}
 */
@RunWith(AndroidJUnit4.class)
public class SleepTimerE2ETest {
    @org.junit.Before public void grantMediaAccess() { com.cinemax.nexus.MediaTestPermissions.grant(); }

    @Test
    public void sleepTimerShowsBadgeAndHoldsPendingAlarm() {
        Context ctx = ApplicationProvider.getApplicationContext();
        long id = -1;
        try (Cursor c = ctx.getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Video.Media._ID},
                null, null, MediaStore.Video.Media._ID + " ASC")) {
            assertNotNull("MediaStore query ran", c);
            if (c.moveToFirst()) id = c.getLong(0);
        }
        if (id < 0) {
            fail("No video in MediaStore - test precondition");
            return;
        }

        Intent i = new Intent(ctx, PlayerActivity.class);
        Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
        i.setData(uri);
        i.putExtra(PlayerActivity.EXTRA_TITLE, "sleep_timer_probe");
        i.putExtra(PlayerActivity.EXTRA_VIDEO_ID, Long.toString(id));

        try (ActivityScenario<PlayerActivity> scenario = ActivityScenario.launch(i)) {
            SystemClock.sleep(1500);

            scenario.onActivity(a -> {
                a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);
                if (PlaybackService.getSession() != null) PlaybackService.getSession().getPlayer().pause();
                View timer = a.findViewById(R.id.btn_timer);
                assertNotNull("btn_timer exists in the player layout", timer);
                timer.performClick(); // works regardless of the controls overlay's visibility
            });
            // The dialog's options come from R.string.minutes_short ("%1$d min" / "%1$d Ø¯Ù‚ÛŒÙ‚Ù‡").
            onView(withText("5 min")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            // Badge flips to VISIBLE with a 5:00 countdown.
            onView(withId(R.id.timer_badge)).check(matches(isDisplayed()));
            SystemClock.sleep(2000);
            onView(withId(R.id.timer_badge)).check(matches(isDisplayed()));

            try (android.os.ParcelFileDescriptor descriptor=androidx.test.platform.app.InstrumentationRegistry
                    .getInstrumentation().getUiAutomation().executeShellCommand("dumpsys alarm");
                 java.io.InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                byte[] buffer=new byte[8192]; int count;
                while((count=input.read(buffer))!=-1) bytes.write(buffer,0,count);
                String alarms=bytes.toString("UTF-8");
                org.junit.Assert.assertTrue("Android holds the actual wake-up alarm",
                    java.util.regex.Pattern.compile("RTC_WAKEUP #[^\\n]*com\\.cinemax\\.nexus[^\\n]*\\n\\s*tag=\\*walarm\\*:"
                        +java.util.regex.Pattern.quote(SleepTimerReceiver.ACTION)).matcher(alarms).find());
            } catch (java.io.IOException error) { throw new AssertionError(error); }
        }
        // Scenario closed: the service retains the timer for background playback.
    }
}

