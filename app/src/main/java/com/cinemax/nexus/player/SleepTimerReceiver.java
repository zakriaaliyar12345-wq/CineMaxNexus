package com.cinemax.nexus.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import androidx.media3.common.Player;
import androidx.media3.session.MediaSession;

import com.cinemax.nexus.R;

/**
 * Course topic 08.2 (Alarms): fires when the player's sleep timer expires.
 *
 * <p>The timer itself is scheduled with {@code AlarmManager.setExactAndAllowWhileIdle}
 * from {@link PlayerActivity}, so the pause still happens when the screen is off or the
 * player UI has been recreated since — the deadline is wall-clock based, not a tick
 * counter that dies with the screen. This receiver is declared in the manifest
 * (exported=false) because a {@code PendingIntent} broadcast targets a manifest
 * receiver, unlike a runtime-registered one.
 *
 * <p>When the UI timer is still alive it may already have paused playback a moment
 * earlier (exact-alarm fallback path); the isPlaying() check keeps that from showing
 * a second toast.
 */
public class SleepTimerReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.cinemax.nexus.action.SLEEP_TIMER";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        PlaybackService service = PlaybackService.active();
        if (service == null) return; // process restarted; nothing is playing
        // An already-queued alarm from a previous session must not pause new playback.
        if (!service.hasSleepTimer() || service.sleepRemaining() > 500) return;
        boolean playing = service.player().getPlayWhenReady();
        service.expireSleepTimer();
        if (playing) {
            Toast.makeText(context.getApplicationContext(),
                    R.string.sleep_timer_done, Toast.LENGTH_LONG).show();
        }
    }
}
