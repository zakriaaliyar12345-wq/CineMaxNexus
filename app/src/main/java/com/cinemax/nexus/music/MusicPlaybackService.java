package com.cinemax.nexus.music;

import android.app.PendingIntent;
import android.content.Intent;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/** The service owns the music engine so screen changes do not stop a song. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MusicPlaybackService extends MediaSessionService {
    private ExoPlayer player;
    private MediaSession session;
    @Nullable private static MusicPlaybackService active;

    @Override public void onCreate() {
        super.onCreate();
        setMediaNotificationProvider(new androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(7200).setChannelId("nexus_music_playback")
                .setChannelName(com.cinemax.nexus.R.string.music_notification_channel).build());
        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build();
        Intent show = new Intent(this, MusicActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent activity = PendingIntent.getActivity(this, 7100, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        session = new MediaSession.Builder(this, player).setId("music").setSessionActivity(activity).build();
        active = this;
        player.addListener(new androidx.media3.common.Player.Listener() {
            @Override public void onPlayWhenReadyChanged(boolean ready, int reason) {
                if (ready) {
                    com.cinemax.nexus.player.MiniPlayerDock.selectMusic();
                    if (com.cinemax.nexus.player.PlaybackService.getSession() != null)
                        com.cinemax.nexus.player.PlaybackService.getSession().getPlayer().pause();
                }
            }
        });
    }

    @Nullable @Override public MediaSession onGetSession(MediaSession.ControllerInfo caller) {
        return getPackageName().equals(caller.getPackageName()) || caller.isTrusted() ? session : null;
    }

    /** Video and music share audio focus; explicitly pause music before opening video. */
    public static void pauseForVideo() {
        if (active != null && active.player != null) active.player.pause();
    }

    @Nullable public static ExoPlayer currentPlayer() { return active == null ? null : active.player; }
    public static void closePlayback() {
        if (active == null) return;
        active.player.stop(); active.player.clearMediaItems(); active.stopSelf();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (player == null || !player.getPlayWhenReady() || player.getMediaItemCount() == 0) stopSelf();
    }

    @Override public void onDestroy() {
        if (active == this) active = null;
        if (session != null) session.release();
        if (player != null) player.release();
        session = null;
        player = null;
        super.onDestroy();
    }
}
