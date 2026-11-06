package com.cinemax.nexus.player;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.media.audiofx.Equalizer;
import android.os.*;
import androidx.annotation.Nullable;
import androidx.media3.common.*;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import com.cinemax.nexus.data.MediaRepository;
import com.cinemax.nexus.data.model.VideoItem;
import com.cinemax.nexus.data.prefs.PrefsManager;
import com.cinemax.nexus.music.MusicPlaybackService;
import java.util.*;

/** Owns video playback independently of any screen. Player access stays on the main thread. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlaybackService extends MediaSessionService {
    public static final String LOCAL_BIND = "com.cinemax.nexus.player.LOCAL_BIND";
    public static final String RESUME_SESSION = "resume_playback_session";
    @Nullable private static PlaybackService active;
    private ExoPlayer player;
    private MediaSession session;
    private boolean software;
    private int screens;
    private VideoItem identity;
    private String videoId;
    private final List<VideoItem> queue = new ArrayList<>();
    private Map<String,String> headers = Collections.emptyMap();
    private String origin = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastSave, sleepDeadline;
    private long loopA = BufferedSeekBar.C_UNSET, loopB = BufferedSeekBar.C_UNSET;
    private final AudioBoostController boost = new AudioBoostController();
    private float boostLevel = 1f;
    private Equalizer equalizer;
    private int equalizerSession;

    public final class LocalBinder extends Binder {
        public PlaybackService service() { return PlaybackService.this; }
    }
    @Override public void onCreate() {
        super.onCreate();
        // A live deadline belongs to this engine; discard alarms left by a dead process.
        cancelSleepAlarm();
        setMediaNotificationProvider(new androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(7201).setChannelId("nexus_video_playback")
                .setChannelName(com.cinemax.nexus.R.string.video_notification_channel).build());
        active = this; acquirePlayer(false); main.post(tick);
    }
    @Nullable @Override public IBinder onBind(@Nullable Intent intent) {
        // Activity binds explicitly inside this process. Remote clients use the Media3 binder.
        if (intent != null && LOCAL_BIND.equals(intent.getAction())) return new LocalBinder();
        return super.onBind(intent);
    }
    @Nullable public static PlaybackService active() { return active; }
    @Nullable public static MediaSession getSession() { return active == null ? null : active.session; }
    public ExoPlayer player() { return player; }
    public boolean isSoftware() { return software; }
    public AudioBoostController boost() { return boost; }
    public float boostLevel() { return boostLevel; }
    public void setBoostLevel(float level) { boostLevel = level; }
    public void attachScreen() { screens++; }
    public void detachScreen() { screens = Math.max(0, screens - 1); }

    /** A decoder change is the only operation that replaces the service-owned engine. */
    public ExoPlayer acquirePlayer(boolean useSoftware) {
        if (player != null && software == useSoftware) return player;
        if (player != null) {
            saveProgress(); removeSession(session); session.release(); player.release(); releaseEffects();
        }
        software = useSoftware;
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this).setEnableDecoderFallback(true)
                .setMediaCodecSelector((mime, secure, tunnel) -> {
                    List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> codecs = new ArrayList<>(
                            androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunnel));
                    if (software && mime.startsWith("video/")) codecs.removeIf(codec -> !codec.softwareOnly);
                    return codecs;
                }).setExtensionRendererMode(software ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                        : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON).setAllowedVideoJoiningTimeMs(0);
        androidx.media3.datasource.ResolvingDataSource.Factory requests = new androidx.media3.datasource.ResolvingDataSource.Factory(
                new androidx.media3.datasource.DefaultDataSource.Factory(this), spec -> spec.withRequestHeaders(
                        origin(spec.uri).equals(origin) ? headers : Collections.emptyMap()));
        PrefsManager prefs = PrefsManager.getInstance(this);
        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(requests))
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL)
                .setSeekBackIncrementMs(prefs.getSeekIntervalShort()).setSeekForwardIncrementMs(prefs.getSeekIntervalLong()).build();
        player.addListener(new Player.Listener() {
            @Override public void onPlayWhenReadyChanged(boolean ready, int reason) {
                if (ready) { MiniPlayerDock.selectVideo(); MusicPlaybackService.pauseForVideo(); }
            }
            @Override public void onAudioSessionIdChanged(int id) {
                attachEqualizer(id);
                if (!boost.apply(id, boostLevel)) boostLevel = 1f;
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (state != Player.STATE_ENDED || screens > 0) return;
                int repeat = PrefsManager.getInstance(PlaybackService.this).getRepeatMode();
                if (repeat == 2) { player.seekTo(0); player.play(); }
                else if (repeat == 1) skip(1);
            }
        });
        session = new MediaSession.Builder(this, player).setId("video").setSessionActivity(returnPendingIntent()).build();
        addSession(session);
        return player;
    }
    private static String origin(android.net.Uri uri) { return uri.getScheme()+"://"+uri.getHost()+":"+uri.getPort(); }
    public void identify(VideoItem item, String id, Map<String,String> requestHeaders) {
        identity = item; videoId = id;
        headers = Collections.unmodifiableMap(new HashMap<>(requestHeaders)); origin = origin(item.uri);
        session.setSessionActivity(returnPendingIntent()); MiniPlayerDock.selectVideo();
    }
    public boolean matches(String id, android.net.Uri uri) {
        MediaItem item = player.getCurrentMediaItem();
        return item != null && id.equals(videoId) && item.localConfiguration != null
                && uri.equals(item.localConfiguration.uri) && player.getPlaybackState() != Player.STATE_IDLE;
    }
    public Intent returnIntent() {
        Intent intent = new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (identity != null) intent.setData(identity.uri)
                .putExtra(PlayerActivity.EXTRA_TITLE, identity.title).putExtra(PlayerActivity.EXTRA_VIDEO_ID, videoId)
                .putExtra(PlayerActivity.EXTRA_DURATION, identity.durationMs).putExtra(PlayerActivity.EXTRA_FOLDER, identity.folderName)
                .putExtra(RESUME_SESSION, true);
        return intent;
    }
    private PendingIntent returnPendingIntent() {
        return PendingIntent.getActivity(this, 7101, returnIntent(), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    public void setQueue(List<VideoItem> items) { queue.clear(); queue.addAll(items); }
    public List<VideoItem> queue() { return new ArrayList<>(queue); }
    @Nullable public VideoItem currentVideo() { return identity; }
    public Map<String,String> requestHeaders() { return headers; }
    private int index() {
        for(int i=0;i<queue.size();i++) if(queue.get(i).key().equals(videoId)) return i;
        return -1;
    }
    public boolean canSkip() {
        int index = index();
        return index >= 0 && (index < queue.size()-1 || (queue.size()>1 && PrefsManager.getInstance(this).getRepeatMode()==1));
    }
    public void skip(int delta) {
        if (queue.isEmpty() || index()<0) return;
        int target=index()+delta;
        if (target < 0 || target >= queue.size()) {
            if (PrefsManager.getInstance(this).getRepeatMode()!=1) return;
            target=(target+queue.size())%queue.size();
        }
        saveProgress(); VideoItem next=queue.get(target); identify(next,next.key(),Collections.emptyMap());
        setLoop(BufferedSeekBar.C_UNSET,BufferedSeekBar.C_UNSET);
        player.setMediaItem(new MediaItem.Builder().setMediaId(next.key()).setUri(next.uri)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(next.title).setArtist(next.folderName).build()).build());
        player.prepare(); player.play();
    }
    public void setLoop(long a,long b) { loopA=a; loopB=b; }
    public long loopA() { return loopA; }
    public long loopB() { return loopB; }
    public void setSleepTimer(long remaining) { sleepDeadline=remaining>0 ? SystemClock.elapsedRealtime()+remaining : 0; }
    public long sleepRemaining() { return Math.max(0,sleepDeadline-SystemClock.elapsedRealtime()); }
    public boolean hasSleepTimer() { return sleepDeadline>0; }
    public void expireSleepTimer() { sleepDeadline=0; player.pause(); cancelSleepAlarm(); }
    private void cancelSleepAlarm() {
        AlarmManager alarms=(AlarmManager)getSystemService(ALARM_SERVICE);
        if(alarms!=null) alarms.cancel(PendingIntent.getBroadcast(this,0x51EE,new Intent(this,SleepTimerReceiver.class)
                .setAction(SleepTimerReceiver.ACTION),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
    }
    @Nullable public Equalizer attachEqualizer(int id) {
        if(equalizer!=null && equalizerSession==id) {
            int preset=PrefsManager.getInstance(this).getEqPreset();
            if(preset>=0) { try { equalizer.usePreset((short)preset); return equalizer; } catch(RuntimeException ignored) {} }
        }
        if(equalizer!=null) { try { equalizer.release(); } catch(RuntimeException ignored) {} equalizer=null; }
        int preset=PrefsManager.getInstance(this).getEqPreset();
        if(id>0 && preset>=0) try {
            equalizer=new Equalizer(0,id); equalizer.usePreset((short)preset); equalizer.setEnabled(true); equalizerSession=id;
            if(!equalizer.getEnabled()) { equalizer.release(); equalizer=null; }
        } catch(RuntimeException unavailable) {
            if(equalizer!=null) { try { equalizer.release(); } catch(RuntimeException ignored) {} } equalizer=null;
        }
        return equalizer;
    }
    private void releaseEffects() {
        boost.release();
        if(equalizer!=null) { try { equalizer.release(); } catch(RuntimeException ignored) {} equalizer=null; }
    }
    public void saveProgress() {
        if(identity==null || player.getMediaItemCount()==0 || player.getPlaybackState()==Player.STATE_IDLE) return;
        long duration=player.getDuration()>0 ? player.getDuration() : identity.durationMs;
        MediaRepository repository=MediaRepository.getInstance(this);
        repository.saveProgress(identity,player.getCurrentPosition(),duration);
        repository.saveVideoState(videoId,player.getPlaybackParameters().speed,PrefsManager.getInstance(this).getAspectRatioMode());
    }
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if(loopA>=0 && loopB>loopA && player.getCurrentPosition()>=loopB) player.seekTo(loopA);
            if(sleepDeadline>0 && sleepRemaining()==0) expireSleepTimer();
            long now=SystemClock.elapsedRealtime();
            if(now-lastSave>=5000) { saveProgress(); lastSave=now; }
            main.postDelayed(this,250);
        }
    };
    public void closePlayback() {
        saveProgress(); setSleepTimer(0); cancelSleepAlarm();
        player.stop(); player.clearMediaItems(); identity=null; queue.clear();
        setLoop(BufferedSeekBar.C_UNSET,BufferedSeekBar.C_UNSET); releaseEffects(); stopSelf();
    }
    @Nullable @Override public MediaSession onGetSession(MediaSession.ControllerInfo caller) {
        return getPackageName().equals(caller.getPackageName()) || caller.isTrusted() ? session : null;
    }
    @Override public int onStartCommand(@Nullable Intent intent,int flags,int id) {
        super.onStartCommand(intent,flags,id); return START_NOT_STICKY;
    }
    @Override public void onTaskRemoved(@Nullable Intent intent) {
        if(!player.getPlayWhenReady() || player.getMediaItemCount()==0) stopSelf();
    }
    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null); saveProgress(); cancelSleepAlarm(); releaseEffects();
        if(active==this) active=null;
        session.release(); player.release(); super.onDestroy();
    }
}
