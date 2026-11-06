package com.cinemax.nexus.player;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Rational;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import com.cinemax.nexus.R;
import com.cinemax.nexus.data.MediaRepository;
import com.cinemax.nexus.data.ThumbnailLoader;
import com.cinemax.nexus.data.model.VideoItem;
import com.cinemax.nexus.data.prefs.PrefsManager;
import com.cinemax.nexus.util.FormatUtils;
import com.cinemax.nexus.util.PermissionUtils;
import com.cinemax.nexus.util.PrivateFolder;
import com.cinemax.nexus.util.VideoTrimmer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The playback screen.
 *
 * <p>Built around Media3's {@link ExoPlayer} but with a fully custom control layer so
 * the app can offer the interaction model people expect from a top-tier local player:
 * brightness/volume/scrub gestures, pinch zoom, A–B looping, external subtitles, per-video
 * aspect and speed memory, decoder switching, sleep timer, PiP, background audio through
 * a {@link MediaSession}, and frame capture.
 */
@UnstableApi
public class PlayerActivity extends AppCompatActivity implements GestureCatcherView.Host {

    private static final String TAG = "PlayerActivity";

    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_TITLE = "extra_title";
    /** Stable per-video key (MediaStore id) used for history / state / bookmarks. */
    public static final String EXTRA_VIDEO_ID = "extra_video_id";
    /** Known duration, lets us resume without waiting for the player to prepare. */
    public static final String EXTRA_DURATION = "extra_duration";
    /** Explicit start position that overrides the stored resume point. */
    public static final String EXTRA_START_POSITION = "extra_start_position";
    /** Bucket the video came from, so the queue stays inside one folder. */
    public static final String EXTRA_FOLDER = "extra_folder";

    // Aspect modes, mirroring the values persisted in PrefsManager.
    // Anything beyond these three is reachable by pinching.
    private static final int ASPECT_FIT = 0;
    private static final int ASPECT_FILL = 1;
    private static final int ASPECT_STRETCH = 2;

    private static final float MAX_ZOOM = 5f;
    private static final float MIN_ZOOM = 0.5f;
    private static final float MAX_AUDIO_BOOST = 2f;

    // Repeat modes, cycled by the transport bar. The ints are persisted verbatim.
    private static final int REPEAT_OFF = 0;
    private static final int REPEAT_ALL = 1;
    private static final int REPEAT_ONE = 2;

    /**
     * How often the clock, seek bar and loop guard redraw while playing.
     *
     * <p>Deliberately fast so the fill glides instead of stepping (250 ms = four updates
     * per second of one small view - trivial next to video decode), while progress
     * writes are throttled on their own inside {@link #saveProgressThrottled}, so this
     * cadence only decides how smooth the readout looks. Tied to a three-second save
     * interval (as it was before) the counter visibly leapt ahead in chunks - 0:05,
     * 0:09 - instead of running second by second, and the A-B loop guard reacted just
     * as late.
     */
    private static final long UI_TICK_MS = 250L;
    private static final int REQUEST_SUBTITLE = 4401;
    /** Pre-Android 10 needs a runtime grant before a trimmed clip can be written out. */
    private static final int REQUEST_TRIM_STORAGE = 4402;

    // ---- Player ----
    @Nullable private ExoPlayer player;
    @Nullable private MediaSession session;
    @Nullable private PlayerView playerView;
    @Nullable private SubtitleView subtitleView;

    // ---- Media identity ----
    @Nullable private Uri mediaUri;
    @NonNull private String mTitle = "";
    @NonNull private String mVideoId = "";
    @Nullable private String mFolder;

    /** Cached poster for the current file, published on the notification and lock screen. */
    @Nullable private Uri mArtworkUri;
    private long mKnownDurationMs;
    private long mExplicitStartMs = C.TIME_UNSET;

    // ---- Queue ----
    private final List<VideoItem> mQueue = new ArrayList<>();
    private int mQueueIndex = -1;
    /** Un-shuffled queue order, kept so switching shuffle off restores the original list. */
    private final List<VideoItem> mQueueOrder = new ArrayList<>();

    // ---- UI ----
    private TextView mPositionText;
    private TextView mDurationText;
    private TextView mSpeedBadge;
    private TextView mAbBadge;
    private TextView mDecoderBadge;
    private View mHud;
    private TextView mHudText;
    private BufferedSeekBar mHudProgress;
    private TextView mSeekHud;
    private TextView mTimerBadge;
    private TextView mSubtitleToast;
    private BufferedSeekBar mSeekBar;
    private View mControlOverlay;
    private View mBuffering;
    private View mErrorState;
    private ImageButton mPlayPause;
    private ImageButton mShuffleBtn;
    private ImageButton mRepeatBtn;
    /** Shown while a trimmed clip is being written out. */
    @Nullable private AlertDialog mTrimDialog;
    private GestureCatcherView mCatcher;

    // ---- Session / prefs ----
    private PrefsManager mPrefs;
    private MediaRepository mRepository;

    // ---- Runtime state ----
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private static final int REQUEST_STUDY = 4600;
    private ScrubPreview mScrubPreview;
    private long mUndoSeek = C.TIME_UNSET;
    private final Runnable mTicker = this::tick;
    private com.cinemax.nexus.subtitle.SubtitleStudio mSubtitleStudio;
    private final AdvancedPlaybackTools mAdvancedTools = new AdvancedPlaybackTools();
    private volatile java.util.Map<String,String> mRequestHeaders = java.util.Collections.emptyMap();
    private volatile String mRequestOrigin = "";

    /**
     * Encodes the notification artwork off the main thread: the thumbnail comes back on
     * the UI thread from {@link ThumbnailLoader}, but writing the JPEG must not stall it.
     */
    private final ExecutorService mArtExecutor = Executors.newSingleThreadExecutor();
    private boolean mControlsVisible;
    private boolean mInPip;
    private boolean mLocked;
    /**
     * Kids-lock variant: same as a normal lock, but it also swallows the back button and
     * only a long press on the lock icon releases it, so a child mashing the screen cannot
     * walk out of the player into the rest of the phone.
     */
    private boolean mKidsLock;
    private boolean mUserSeeking;
    private boolean mUseSoftwareDecoder;
    private boolean mBackgroundPlay = true;
    /**
     * Whether the player should (re)start when the screen comes back. Starts true so the
     * first open auto-plays, and flips to false only when the user (or the sleep timer)
     * deliberately pauses. Without this the onResume guard below could never fire, because
     * {@link #mBackgroundPlay} is on by default.
     */
    private boolean mPlayOnResume = true;
    private boolean mReleased;
    private boolean mPrepared;
    private float mZoom = 1f;
    private float mAudioBoost = 1f;
    private boolean mShowRemaining;
    private boolean mFocusMode;
    private float mBeforeFocusSpeed = 1f;
    private float mBeforeHoldSpeed = 1f;
    private int mBeforeFocusRepeat;
    private AudioBoostController mBoostController = new AudioBoostController();
    private PlaybackService mPlaybackService;
    private boolean mServiceBound;
    private final PlayerListener mPlayerListener = new PlayerListener();
    private final android.content.ServiceConnection mServiceConnection = new android.content.ServiceConnection() {
        @Override public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
            if (mReleased) return;
            mPlaybackService = ((PlaybackService.LocalBinder) binder).service();
            mPlaybackService.attachScreen();
            if (mPlaybackService.matches(mVideoId, mediaUri)) mUseSoftwareDecoder = mPlaybackService.isSoftware();
            initializePlayer();
        }
        @Override public void onServiceDisconnected(android.content.ComponentName name) {
            if (playerView != null) playerView.setPlayer(null);
            player = null; session = null; mPlaybackService = null;
        }
    };
    private int mAspectMode = ASPECT_FIT;
    private boolean mOrientationLocked;
    private boolean mNightMode;

    /**
     * Platform equalizer bound to the player's audio session.
     *
     * <p>Borrowed from PlaybackService. The effect remains attached when the screen closes,
     * and the service releases it with the engine. It is created only after a preset is chosen.
     */
    @Nullable private android.media.audiofx.Equalizer mEqualizer;

    // Seek gesture
    private long mSeekBaseMs;
    private long mSeekTargetMs;

    // A-B loop
    private long mLoopA = BufferedSeekBar.C_UNSET;
    private long mLoopB = BufferedSeekBar.C_UNSET;

    // Sleep timer
    @Nullable private CountDownTimer mSleepTimer;
    private long mSleepRemainingMs;
    /** True while the sleep deadline is held by an exact AlarmManager wakeup. */
    private boolean mAlarmExact;

    // Subtitles
    private final List<MediaItem.SubtitleConfiguration> mSubtitleConfigs = new ArrayList<>();
    private int mSubtitleSizePercent = 100;
    private int mSubtitleBg = 1;

    private boolean mHudVisible;
    private boolean mSeekHudVisible;

    // ---- Lifecycle ----

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.cinemax.nexus.music.MusicPlaybackService.pauseForVideo();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        mPrefs = PrefsManager.getInstance(this);
        mRepository = MediaRepository.getInstance(this);
        // A player recreated while already floating (process death in PiP) must know
        // it is in PiP before the first showControls() call.
        mInPip = isInPipModeCompat();
        readIntent();
        bindViews();
        wireControls();
        mScrubPreview = new ScrubPreview(this);
        mSubtitleStudio = new com.cinemax.nexus.subtitle.SubtitleStudio(this,
                new com.cinemax.nexus.subtitle.SubtitleStudio.Host() {
            public long position() { return player == null ? 0 : player.getCurrentPosition(); }
            public void seek(long ms) { if(player != null) { clearAbLoop(); player.seekTo(ms); } }
            public void loop(long start,long end) {
                mLoopA=start; mLoopB=end; mSeekBar.setLoopMarkers(start,end); updateAbBadge();
                if(player != null) { player.seekTo(start); player.play(); }
            }
            public void captions(String first,String second) {
                if(first != null && subtitleView != null) subtitleView.setCues(first.isEmpty()
                        ? Collections.emptyList() : Collections.singletonList(new Cue.Builder().setText(first).build()));
                else if(subtitleView != null) subtitleView.setCues(Collections.emptyList());
                TextView secondary=findViewById(R.id.secondary_captions);
                secondary.setText(second); secondary.setVisibility(second.isEmpty() ? View.GONE : View.VISIBLE);
            }
        });
        mSubtitleStudio.reset(mVideoId);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // A locked screen must not be escapable - that is the entire point of the
                // kids lock, otherwise a child backs straight out of the player.
                if (mLocked) {
                    Toast.makeText(PlayerActivity.this, R.string.screen_locked,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (mControlsVisible) {
                    hideControls();
                } else {
                    finish();
                }
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        com.cinemax.nexus.music.MusicPlaybackService.pauseForVideo();
        saveProgressNow();
        setIntent(intent);
        readIntent();
        if (getIntent().getBooleanExtra(PlaybackService.RESUME_SESSION, false)
                && mPlaybackService != null && mPlaybackService.matches(mVideoId, mediaUri)) {
            ((TextView) findViewById(R.id.title_text)).setText(mTitle);
            mPlayOnResume = player != null && player.getPlayWhenReady();
            return;
        }
        clearAbLoop();
        mSubtitleConfigs.clear();
        if(mSubtitleStudio != null) mSubtitleStudio.reset(mVideoId);
        ((TextView) findViewById(R.id.title_text)).setText(mTitle);
        mPlayOnResume = true;
        prepareMedia();
        restorePerFileSettings();
        loadQueue();
        if (player != null) player.play();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!mServiceBound) {
            Intent serviceIntent = new Intent(this, PlaybackService.class);
            startService(serviceIntent);
            mServiceBound = bindService(serviceIntent.setAction(PlaybackService.LOCAL_BIND), mServiceConnection, BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        refreshStudyMarkers();
        // Reattaching a screen must respect pause commands from the mini-player or headset.
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateRotateButton();
        hideSystemUi();
        if (mControlsVisible) showControls();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveProgressNow();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player == null) return;
        if (!mBackgroundPlay) {
            player.pause();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (player != null && player.isPlaying()) enterPip();
    }

    /**
     * PiP is a lifecycle state, not a button press - the system can also enter it
     * (and always reports the transition back). Keeping this in one override means the
     * floating window can never show the control overlay: {@link #showControls()} is
     * gated on {@link #mInPip}, and leaving PiP reveals the controls again instead of a
     * frozen blank surface the user has to tap blindly.
     */
    @Override
    @SuppressWarnings("deprecation") // single-arg variant is the one all API levels deliver
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        mInPip = isInPictureInPictureMode;
        if (mControlOverlay == null) return; // transition can only happen post-bind, but never NPE
        if (isInPictureInPictureMode) {
            // Snap the overlay out immediately: hideControls() refuses to run mid-seek
            // (mUserSeeking) and animates for 160 ms, both wrong for a window that just
            // shrank to a few inches.
            mMain.removeCallbacks(mHideControls);
            mControlOverlay.animate().cancel();
            mControlOverlay.setAlpha(0f);
            mControlOverlay.setVisibility(View.INVISIBLE);
            mControlsVisible = false;
            mMain.removeCallbacks(mHideHud);
            mHud.setVisibility(View.GONE);
        } else {
            showControls();
        }
    }

    @Override
    protected void onDestroy() {
        endFocusMode();
        mReleased = true;
        if(mSubtitleStudio != null) mSubtitleStudio.close();
        mAdvancedTools.close();
        if(mScrubPreview != null) mScrubPreview.close();
        // The service owns the deadline; only the visible countdown belongs to this screen.
        if (mSleepTimer != null) { mSleepTimer.cancel(); mSleepTimer = null; }
        mMain.removeCallbacksAndMessages(null);
        mArtExecutor.shutdown();
        dismissTrimProgress();
        saveProgressNow();
        releasePlayer();
        if (mPlaybackService != null) mPlaybackService.detachScreen();
        if (mServiceBound) { unbindService(mServiceConnection); mServiceBound = false; }
        mPlaybackService = null;
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Only the pre-Android 10 write grant arrives here. Once it lands, come back to the
        // range picker so the user does not have to open the menu all over again.
        if (requestCode == PermissionUtils.REQUEST_TRIM_WRITE
                && PermissionUtils.hasTrimWritePermission(this)) {
            showTrimDialog();
        }
    }

    // ---- Setup ----

    private static String requestOrigin(Uri uri) {
        return uri == null ? "" : uri.getScheme()+"://"+uri.getHost()+":"+uri.getPort();
    }

    private void readIntent() {
        mExplicitStartMs = C.TIME_UNSET;
        mUndoSeek = C.TIME_UNSET;
        mediaUri = getIntent().getData();
        if (mediaUri == null) {
            String uri = getIntent().getStringExtra(EXTRA_URI);
            if (uri != null) mediaUri = Uri.parse(uri);
        }
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        mTitle = title != null ? title : getString(R.string.app_name);
        String videoId = getIntent().getStringExtra(EXTRA_VIDEO_ID);
        mVideoId = videoId != null ? videoId : String.valueOf(mediaUri != null ? mediaUri.hashCode() : 0);
        java.util.Map<String,String> headers = new java.util.HashMap<>();
        Bundle headerBundle = getIntent().getBundleExtra("stream_headers");
        if(headerBundle != null) for(String key : headerBundle.keySet()) {
            String value=headerBundle.getString(key); if(value != null) headers.put(key,value);
        }
        mRequestHeaders=java.util.Collections.unmodifiableMap(headers);
        mRequestOrigin=requestOrigin(mediaUri);
        mKnownDurationMs = getIntent().getLongExtra(EXTRA_DURATION, 0L);
        mFolder = getIntent().getStringExtra(EXTRA_FOLDER);
        if (getIntent().hasExtra(EXTRA_START_POSITION)) {
            mExplicitStartMs = getIntent().getLongExtra(EXTRA_START_POSITION, C.TIME_UNSET);
        }
    }

    private void bindViews() {
        playerView = findViewById(R.id.player_view);
        subtitleView = findViewById(R.id.subtitle_view);
        mCatcher = findViewById(R.id.gesture_catcher);
        mControlOverlay = findViewById(R.id.control_overlay);
        mPositionText = findViewById(R.id.position_text);
        mDurationText = findViewById(R.id.duration_text);
        mSpeedBadge = findViewById(R.id.speed_badge);
        mAbBadge = findViewById(R.id.ab_badge);
        mDecoderBadge = findViewById(R.id.decoder_badge);
        mHud = findViewById(R.id.hud);
        mHudText = mHud.findViewById(R.id.hud_text);
        mHudProgress = mHud.findViewById(R.id.hud_progress);
        mSeekHud = findViewById(R.id.seek_hud);
        mTimerBadge = findViewById(R.id.timer_badge);
        mSubtitleToast = findViewById(R.id.subtitle_toast);
        mSeekBar = findViewById(R.id.seek_bar);
        mBuffering = findViewById(R.id.buffering);
        mErrorState = findViewById(R.id.error_state);
        mPlayPause = findViewById(R.id.btn_play_pause);
        mShuffleBtn = findViewById(R.id.btn_shuffle);
        mRepeatBtn = findViewById(R.id.btn_repeat);

        TextView title = findViewById(R.id.title_text);
        title.setText(mTitle);
        title.setSelected(true);

        mCatcher.setHost(this);
        mCatcher.setGesturesEnabled(mPrefs.isGesturesEnabled());

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_pip).setOnClickListener(v -> enterPip());
        findViewById(R.id.btn_rotate).setOnClickListener(v -> rotateScreen());
        updateRotateButton();
        findViewById(R.id.retry_button).setOnClickListener(v -> {
            if (player != null) {
                mErrorState.setVisibility(View.GONE);
                player.prepare();
                player.play();
            }
        });

        configureSubtitleView();
        configureSeekBar();
        applyAspectMode(mPrefs.getAspectRatioMode(), true);
        mAudioBoost = 1f;
        restoreBrightnessIfNeeded();
        refreshTransportButtons();
        showControls();
    }

    private void configureSubtitleView() {
        if (subtitleView == null) return;
        subtitleView.setApplyEmbeddedStyles(false);
        subtitleView.setApplyEmbeddedFontSizes(false);
        applySubtitleSize(100);
        applySubtitleBackground(1);
        // The saved offset is a fraction of the view height, which is still 0 in onCreate.
        subtitleView.post(() -> applySubtitleOffset(mPrefs.getSubtitleOffset()));
    }

    private void configureSeekBar() {
        mSeekBar.setMax(1000);
        mSeekBar.setOnSeekBarChangeListener(new SeekBarListener());
    }

    private void wireControls() {
        mPositionText.setOnClickListener(v -> {
            mShowRemaining = !mShowRemaining;
            if (player != null) updatePositionLabels(player.getCurrentPosition(), player.getDuration());
        });
        mPositionText.setContentDescription(getString(R.string.toggle_remaining));
        mPlayPause.setOnClickListener(v -> togglePlayPause());
        findViewById(R.id.btn_prev).setOnClickListener(v -> playRelative(-1));
        findViewById(R.id.btn_next).setOnClickListener(v -> playRelative(1));
        findViewById(R.id.btn_lock).setOnClickListener(v -> setLocked(true, false));
        findViewById(R.id.btn_lock).setOnLongClickListener(v -> {
            setLocked(true, true);
            return true;
        });
        View lockTarget = findViewById(R.id.lock_button);
        lockTarget.setOnClickListener(v -> {
            if (mKidsLock) return;   // a single tap must not release a kids lock
            setLocked(false, false);
        });
        lockTarget.setOnLongClickListener(v -> {
            if (!mKidsLock) return false;
            setLocked(false, false);
            return true;
        });
        mShuffleBtn.setOnClickListener(v -> toggleShuffle());
        mRepeatBtn.setOnClickListener(v -> cycleRepeatMode());
        findViewById(R.id.btn_more).setOnClickListener(v -> showMoreMenu(v));
        findViewById(R.id.btn_ratio).setOnClickListener(v -> showAspectDialog());
        findViewById(R.id.btn_speed).setOnClickListener(v -> showSpeedDialog());
        findViewById(R.id.btn_audio).setOnClickListener(v -> showAudioDialog());
        findViewById(R.id.btn_subtitles).setOnClickListener(v -> showSubtitleDialog());
        findViewById(R.id.btn_timer).setOnClickListener(v -> showSleepTimerDialog());
        findViewById(R.id.btn_info).setOnClickListener(v -> showInfoDialog());
        findViewById(R.id.btn_queue).setOnClickListener(v -> showQueueDialog());
    }

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ---- Player construction ----

    private void initializePlayer() {
        if (player != null || mediaUri == null || mReleased || mPlaybackService == null) return;
        boolean resume = mPlaybackService.isSoftware() == mUseSoftwareDecoder
                && mPlaybackService.matches(mVideoId, mediaUri) && mExplicitStartMs == C.TIME_UNSET;
        player = mPlaybackService.acquirePlayer(mUseSoftwareDecoder);
        mBoostController = mPlaybackService.boost();
        mAudioBoost = mPlaybackService.boostLevel();
        playerView.setPlayer(player);
        allowSurfaceToOverflow();
        player.addListener(mPlayerListener);
        player.addAnalyticsListener(mAdvancedTools);
        createSession();
        if (resume) {
            mPrepared = true;
            mRequestHeaders = mPlaybackService.requestHeaders();
            mSubtitleConfigs.clear();
            MediaItem current = player.getCurrentMediaItem();
            if (current != null && current.localConfiguration != null)
                mSubtitleConfigs.addAll(current.localConfiguration.subtitleConfigurations);
            mPlayOnResume = player.getPlayWhenReady();
            mLoopA = mPlaybackService.loopA(); mLoopB = mPlaybackService.loopB();
            mSeekBar.setLoopMarkers(mLoopA, mLoopB); updateAbBadge();
            mQueue.clear(); mQueue.addAll(mPlaybackService.queue());
            mQueueOrder.clear(); mQueueOrder.addAll(mQueue);
            if (!mPrefs.isShuffle()) mRepository.sortBy(mQueueOrder, MediaRepository.SortMode.DATE_ADDED, false);
            if (mPlaybackService.sleepRemaining() > 0) startSleepTimer(mPlaybackService.sleepRemaining());
            mNightMode = player.getVolume() < 0.99f;
            updateSpeedBadge(player.getPlaybackParameters().speed);
            if (subtitleView != null) subtitleView.setCues(player.getCurrentCues().cues);
            updateDurationLabels(); refreshTransportButtons(); updateDecoderBadge();
        } else {
            prepareArtwork(); prepareMedia(); restorePerFileSettings(); player.play();
        }
        loadQueue();
        mMain.removeCallbacks(mTicker); mMain.post(mTicker);
    }

    /**
     * Re-applies the playback speed chosen last time this exact file was watched.
     * Aspect ratio deliberately stays global so one setting governs the whole library.
     */
    private void restorePerFileSettings() {
        final String requestedId = mVideoId;
        mRepository.getStateAsync(requestedId, state -> runOnUiThread(() -> {
            if (player == null || mReleased || mFocusMode || state == null || !requestedId.equals(mVideoId)) return;
            if (state.speed > 0f && Math.abs(state.speed - 1f) > 0.01f) {
                player.setPlaybackParameters(
                        new androidx.media3.common.PlaybackParameters(state.speed));
            }
        }));
    }

    private void prepareMedia() {
        if (player == null || mediaUri == null) return;
        mPlaybackService.setLoop(mLoopA, mLoopB);
        player.setMediaItem(buildMediaItem());
        player.setVolume(mNightMode ? 0.7f : 1f);
        player.prepare();
        mPrepared = true;
        applyStartOffset();
    }

    /**
     * Resolves the notification poster <em>before</em> the media item is built.
     *
     * <p>Media3 1.4.1 offers no way to change a session's metadata after the fact (there is
     * no {@code setMediaMetadata} anywhere in the library - metadata can only travel with the
     * {@link MediaItem}), so the artwork has to be known at prepare time. The library grid has
     * normally already decoded this video's thumbnail, in which case
     * {@link ThumbnailLoader#load} calls back synchronously and the poster is written here,
     * immediately. When it is not ready yet, the poster is written in the background and
     * simply takes effect the next time this file is opened - playback is never delayed or
     * re-prepared for a cosmetic value.
     */
    private void prepareArtwork() {
        if (mediaUri == null) return;

        File cached = artworkFile();
        if (cached != null && cached.exists()) {
            mArtworkUri = Uri.fromFile(cached);
            return;
        }

        final boolean[] synchronised = {true};
        ThumbnailLoader.getInstance(this).load(buildQueueItem(0L), (uri, bitmap) -> {
            if (bitmap == null) return;
            if (synchronised[0]) {
                File f = writeArtwork(bitmap);
                if (f != null) mArtworkUri = Uri.fromFile(f);
            } else {
                // Too late to join this prepare: encode for the next playback of this file.
                final Bitmap pending = bitmap;
                mArtExecutor.execute(() -> writeArtwork(pending));
            }
        });
        // Any callback after this point arrives on a later frame and must not block prepare.
        synchronised[0] = false;
    }

    /** Location of the cached poster for the file being opened, whether or not it exists yet. */
    @Nullable
    private File artworkFile() {
        if (mediaUri == null || mVideoId.isEmpty()) return null;
        File dir = new File(getCacheDir(), "art");
        return new File(dir, mVideoId.replaceAll("[^A-Za-z0-9._-]", "_") + ".jpg");
    }

    /**
     * Encodes the poster once per file. Failures are swallowed: artwork is cosmetic and must
     * never disturb playback.
     */
    @Nullable
    private File writeArtwork(@Nullable Bitmap bitmap) {
        File file = artworkFile();
        if (file == null || bitmap == null) return null;
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) return null;
        if (file.exists() && file.length() > 0) return file;
        try (OutputStream os = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 86, os);
            return file;
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
    }

    @NonNull
    private MediaItem buildMediaItem() {
        if (mPlaybackService != null) mPlaybackService.identify(buildQueueItem(mKnownDurationMs), mVideoId, mRequestHeaders);
        MediaItem.Builder builder = new MediaItem.Builder().setMediaId(mVideoId).setUri(mediaUri);
        if (!mSubtitleConfigs.isEmpty()) {
            builder.setSubtitleConfigurations(new ArrayList<>(mSubtitleConfigs));
        }
        // Without this the media notification renders the literal text "null",
        // because the session has no metadata to show.
        MediaMetadata.Builder meta = new MediaMetadata.Builder()
                .setTitle(mTitle)
                .setArtist(mFolder != null && !mFolder.isEmpty()
                        ? mFolder : getString(R.string.app_name))
                .setAlbumTitle(mFolder)
                .setAlbumArtist(mFolder);
        if (mArtworkUri != null) meta.setArtworkUri(mArtworkUri);
        builder.setMediaMetadata(meta.build());
        return builder.build();
    }

    /** Chooses between an explicit start, the stored resume point, or the very start. */
    private void applyStartOffset() {
        if (player == null) return;
        if (mExplicitStartMs != C.TIME_UNSET && mExplicitStartMs >= 0) {
            player.seekTo(mExplicitStartMs);
            return;
        }
        if (!mPrefs.isResumeEnabled()) return;
        final String requestedId = mVideoId;
        mRepository.getResumePositionAsync(requestedId, (positionMs, durationMs) ->
                runOnUiThread(() -> {
                    if (player == null || mReleased || !requestedId.equals(mVideoId)) return;
                    // Decide with the SAVED duration whenever the live one is not known
                    // yet: this callback routinely beats the player's first timeline
                    // update, where getDuration() reports 0. Trusting that 0 made the
                    // near-end guard's `duration <= 0` branch fire for every position
                    // over five seconds, so a video left near its end reopened already
                    // finished - and which branch ran depended on a timing race (0:00
                    // on some runs, 0:16 on others). Both durations unknown now means
                    // "start at the beginning".
                    long duration = player.getDuration();
                    if (duration == C.TIME_UNSET || duration <= 0) duration = durationMs;
                    // Ignore a stale point once the file is nearly finished.
                    if (duration > 0 && positionMs > 5_000L
                            && positionMs < duration - 15_000L) {
                        player.seekTo(positionMs);
                    }
                }));
    }

    private void createSession() {
        session = PlaybackService.getSession();
    }

    /**
     * Pinch-zoom scales the {@code TextureView} past the bounds of its
     * {@code AspectRatioFrameLayout} container, which would be clipped by default.
     */
    private void allowSurfaceToOverflow() {
        if (playerView == null) return;
        playerView.setClipChildren(false);
        playerView.setClipToPadding(false);
        for (int i = 0; i < playerView.getChildCount(); i++) {
            View child = playerView.getChildAt(i);
            if (child instanceof ViewGroup) {
                ((ViewGroup) child).setClipChildren(false);
                ((ViewGroup) child).setClipToPadding(false);
            }
        }
    }

    private void releasePlayer() {
        // The service retains the engine, media session, loop and effects while browsing.
        if (playerView != null) playerView.setPlayer(null);
        if (player != null) {
            player.removeListener(mPlayerListener);
            player.removeAnalyticsListener(mAdvancedTools);
        }
        player = null; session = null; mEqualizer = null;
    }


    // ---- Queue ----

    private void loadQueue() {
        // Reloading on a decoder switch would just churn; the queue is unchanged.
        if (!mQueue.isEmpty()) {
            mQueueIndex = indexOfCurrent();
            refreshQueueButtons();
            return;
        }
        mRepository.loadVideos(new MediaRepository.LoadCallback() {
            @Override
            public void onLoaded(@NonNull List<VideoItem> videos) {
                List<VideoItem> list = videos;
                if (mFolder != null && !mFolder.isEmpty()) {
                    list = mRepository.filterByFolder(list, mFolder);
                }
                // A hidden video must not be reachable by nudging next, or the private
                // folder would hide the grid and leave the queue beside it wide open.
                list = dropPrivate(list);
                mRepository.sortBy(list, MediaRepository.SortMode.DATE_ADDED, false);
                mQueueOrder.clear();
                mQueueOrder.addAll(list);
                mQueue.clear();
                mQueue.addAll(list);
                if (mPrefs.isShuffle()) applyShuffleOrder(mVideoId);
                mQueueIndex = indexOfCurrent();
                runOnUiThread(PlayerActivity.this::refreshQueueButtons);
            }

            @Override
            public void onError(@NonNull Exception e) {
                // Queue is a convenience; a failure here must not break playback.
            }
        });
    }

    private int indexOfCurrent() {
        for (int i = 0; i < mQueue.size(); i++) {
            if (mQueue.get(i).key().equals(mVideoId)) return i;
        }
        return -1;
    }

    /**
     * Mirrors the library's rule: a video the viewer moved into the private folder is not
     * in the queue either. Without this the hide would only cover the grid, and pressing
     * next would walk straight into a video the viewer had hidden.
     */
    @NonNull
    private List<VideoItem> dropPrivate(@NonNull List<VideoItem> source) {
        return PrivateFolder.visibleOnly(this, source);
    }

    private void refreshQueueButtons() {
        if (mReleased) return;
        if (mPlaybackService != null) mPlaybackService.setQueue(mQueue);
        View prev = findViewById(R.id.btn_prev);
        View next = findViewById(R.id.btn_next);
        // "Repeat all" turns both ends of the queue into a door instead of a wall.
        boolean wrap = mPrefs.getRepeatMode() == REPEAT_ALL && mQueue.size() > 1;
        prev.setEnabled(wrap || mQueueIndex > 0 || mQueue.size() > 1);
        next.setEnabled(wrap || (mQueueIndex >= 0 && mQueueIndex < mQueue.size() - 1));
        float dim = 0.35f;
        float full = 1f;
        prev.setAlpha(prev.isEnabled() ? full : dim);
        next.setAlpha(next.isEnabled() ? full : dim);
    }

    private void playRelative(int delta) {
        // The scan is asynchronous; refuse to move rather than jump somewhere random.
        if (mQueueIndex < 0) {
            mQueueIndex = indexOfCurrent();
            if (mQueueIndex < 0) return;
        }
        int target = mQueueIndex + delta;
        if (target < 0 || target >= mQueue.size()) {
            if (mPrefs.getRepeatMode() != REPEAT_ALL || mQueue.isEmpty()) return;
            target = target < 0 ? mQueue.size() - 1 : 0;
        }
        playItem(mQueue.get(target), target);
    }

    /**
     * Shuffles the queue but pins the video playing right now to the front, so toggling
     * shuffle never interrupts what the viewer is in the middle of.
     */
    private void applyShuffleOrder(@NonNull String currentKey) {
        VideoItem current = null;
        List<VideoItem> rest = new ArrayList<>();
        for (VideoItem item : mQueue) {
            if (current == null && item.key().equals(currentKey)) current = item;
            else rest.add(item);
        }
        java.util.Collections.shuffle(rest);
        mQueue.clear();
        if (current != null) mQueue.add(current);
        mQueue.addAll(rest);
    }

    private void toggleShuffle() {
        boolean on = !mPrefs.isShuffle();
        mPrefs.setShuffle(on);
        if (on) {
            applyShuffleOrder(mVideoId);
        } else if (!mQueueOrder.isEmpty()) {
            mQueue.clear();
            mQueue.addAll(mQueueOrder);
        }
        mQueueIndex = indexOfCurrent();
        refreshQueueButtons();
        refreshTransportButtons();
        Toast.makeText(this, on ? R.string.shuffle_on : R.string.shuffle_off,
                Toast.LENGTH_SHORT).show();
    }

    private void cycleRepeatMode() {
        int mode = mPrefs.getRepeatMode();
        mode = (mode == REPEAT_OFF) ? REPEAT_ALL : (mode == REPEAT_ALL ? REPEAT_ONE : REPEAT_OFF);
        mPrefs.setRepeatMode(mode);
        refreshQueueButtons();
        refreshTransportButtons();
        Toast.makeText(this,
                mode == REPEAT_ALL ? R.string.repeat_all
                        : (mode == REPEAT_ONE ? R.string.repeat_one : R.string.repeat_off),
                Toast.LENGTH_SHORT).show();
    }

    /** Paints shuffle/repeat as "on" (accent) or "off" (dimmed) so the state is legible. */
    private void refreshTransportButtons() {
        if (mShuffleBtn != null) {
            boolean shuffle = mPrefs.isShuffle();
            mShuffleBtn.setColorFilter(getColor(shuffle
                    ? R.color.nexus_accent : R.color.nexus_text_secondary));
            mShuffleBtn.setAlpha(shuffle ? 1f : 0.5f);
        }
        if (mRepeatBtn != null) {
            int mode = mPrefs.getRepeatMode();
            mRepeatBtn.setImageResource(mode == REPEAT_ONE
                    ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
            mRepeatBtn.setColorFilter(getColor(mode == REPEAT_OFF
                    ? R.color.nexus_text_secondary : R.color.nexus_accent));
            mRepeatBtn.setAlpha(mode == REPEAT_OFF ? 0.5f : 1f);
        }
    }

    /** Applies the repeat mode when a video runs out. */
    private void advanceOnEnd() {
        int mode = mPrefs.getRepeatMode();
        if (mode == REPEAT_ONE) {
            if (player == null) return;
            mPlayOnResume = true;
            player.seekTo(0);
            player.play();
        } else if (mode == REPEAT_ALL) {
            playRelative(1);
        }
    }

    private void playItem(@NonNull VideoItem item, int index) {
        saveProgressNow();
        mUndoSeek = C.TIME_UNSET;
        if (mScrubPreview != null) mScrubPreview.hide();
        mQueueIndex = index;
        mediaUri = item.uri;
        mRequestHeaders=Collections.emptyMap(); mRequestOrigin=requestOrigin(mediaUri);
        mTitle = item.title;
        mVideoId = item.key();
        refreshStudyMarkers();
        mKnownDurationMs = item.durationMs;
        mExplicitStartMs = C.TIME_UNSET;
        mLoopA = BufferedSeekBar.C_UNSET;
        mLoopB = BufferedSeekBar.C_UNSET;
        mSeekBar.setLoopMarkers(mLoopA, mLoopB);
        updateAbBadge();
        mSubtitleConfigs.clear();
        if(mSubtitleStudio != null) mSubtitleStudio.reset(mVideoId);

        TextView title = findViewById(R.id.title_text);
        title.setText(mTitle);

        if (player != null) {
            player.setMediaItem(buildMediaItem());
            player.prepare();
            mPrepared = true;
            applyStartOffset();
            player.play();
        }
        refreshQueueButtons();
    }

    // ---- Transport ----

    private void togglePlayPause() {
        if (player == null) return;
        if (player.isPlaying()) {
            mPlayOnResume = false;
            player.pause();
        } else {
            mPlayOnResume = true;
            player.play();
        }
    }

    private void updatePlayPauseIcon() {
        boolean playing = player != null && player.isPlaying();
        mPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        mPlayPause.setContentDescription(getString(playing ? R.string.pause : R.string.play));
    }

    /**
     * Reveals the buffering spinner only if buffering actually lasts. A seek on a local
     * file enters {@code STATE_BUFFERING} for a few milliseconds; flashing a 54dp
     * spinner on every scrub made the player feel heavy when it was in fact instant.
     */
    private final Runnable mShowBuffering = () -> {
        if (!mReleased && mBuffering != null) mBuffering.setVisibility(View.VISIBLE);
    };

    private final class PlayerListener implements Player.Listener {
        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updatePlayPauseIcon();
            if (isPlaying) {
                mErrorState.setVisibility(View.GONE);
                mMain.removeCallbacks(mShowBuffering);
                mBuffering.setVisibility(View.GONE);
            }
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            boolean buffering = playbackState == Player.STATE_BUFFERING;
            if (buffering) {
                mMain.removeCallbacks(mShowBuffering);
                mMain.postDelayed(mShowBuffering, 350L);
            } else {
                mMain.removeCallbacks(mShowBuffering);
                mBuffering.setVisibility(View.GONE);
            }
            if (playbackState == Player.STATE_READY) {
                updateDurationLabels();
                // Media3 only hands out the audio session once the audio sink is up; if the
                // effect is still unattached (e.g. re-entering with a saved preset), try now.
                if (mEqualizer == null && player != null) {
                    attachEqualizer(player.getAudioSessionId());
                }
            }
            if (playbackState == Player.STATE_ENDED) {
                // Finished: a later onResume must not restart it from the top.
                mPlayOnResume = false;
                advanceOnEnd();
            }
        }

        /**
         * Media3 creates the audio session asynchronously, so this - not the moment the
         * player is built - is where an effect can be attached for the first time.
         */
        @Override
        public void onAudioSessionIdChanged(int audioSessionId) {
            attachEqualizer(audioSessionId);
            if (mAudioBoost > 1f) applyAudioBoost(false);
        }

        /**
         * Media3's {@link PlayerView} normally owns the subtitle surface, but we run a
         * custom control layer with {@code use_controller=false}, so cues are forwarded
         * to our own {@link SubtitleView} by hand.
         */
        @Override
        public void onCues(@NonNull List<Cue> cues) {
            if (subtitleView != null && (mSubtitleStudio == null || !mSubtitleStudio.hasPrimary())) subtitleView.setCues(cues);
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            mBuffering.setVisibility(View.GONE);
            TextView errorText = findViewById(R.id.error_text);
            errorText.setText(playbackErrorMessage(error));
            mErrorState.setVisibility(View.VISIBLE);
        }

        @Override
        public void onVideoSizeChanged(@androidx.annotation.NonNull androidx.media3.common.VideoSize videoSize) {
            updateOrientationFor(videoSize.width, videoSize.height);
        }

        @Override
        public void onPlaybackParametersChanged(@NonNull androidx.media3.common.PlaybackParameters playbackParameters) {
            updateSpeedBadge(playbackParameters.speed);
        }

        @Override
        public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition,
                                            @NonNull Player.PositionInfo newPosition,
                                            int reason) {
            // A-B loop: snap back to A whenever we pass B.
            if (mLoopA != BufferedSeekBar.C_UNSET && mLoopB != BufferedSeekBar.C_UNSET
                    && player != null && player.getCurrentPosition() >= mLoopB) {
                player.seekTo(mLoopA);
            }
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                playRelative(1);
            }
        }
    }

    private CharSequence playbackErrorMessage(@NonNull PlaybackException error) {
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                || error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION) {
            return getString(R.string.playback_error);
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED) {
            return getString(R.string.decoder_sw_hint);
        }
        return getString(R.string.playback_error);
    }

    // ---- Position / UI ticking ----

    private void tick() {
        if (mReleased) return;
        if (player != null) {
            long position = player.getCurrentPosition();
            long duration = player.getDuration();
            if (duration == C.TIME_UNSET) duration = Math.max(mKnownDurationMs, 0);
            if (!mUserSeeking) updatePositionLabels(position, duration);
            if (!mUserSeeking) updateSeekBar(position, duration);
            updateLoopGuard(position);
            if(mSubtitleStudio != null) mSubtitleStudio.update(position);
            saveProgressThrottled(position, duration);
        }
        mMain.postDelayed(mTicker, UI_TICK_MS);
    }

    private void updatePositionLabels(long position, long duration) {
        mPositionText.setText(mShowRemaining && duration > 0
                ? "-" + FormatUtils.formatDuration(Math.max(0, duration - position))
                : FormatUtils.formatDuration(position));
        if (duration > 0) {
            mDurationText.setText(FormatUtils.formatDuration(duration));
        } else {
            mDurationText.setText("--:--");
        }
    }

    private void updateDurationLabels() {
        if (player == null || mUserSeeking) return;
        long duration = player.getDuration();
        if (duration == C.TIME_UNSET) duration = mKnownDurationMs;
        updatePositionLabels(player.getCurrentPosition(), duration);
    }

    private void updateSeekBar(long position, long duration) {
        if (duration <= 0) return;
        if (!mUserSeeking) {
            mSeekBar.setProgress((int) (position * 1000L / duration));
        }
        long buffered = 0;
        if (player != null && player.getBufferedPosition() > 0) {
            buffered = player.getBufferedPosition();
        }
        mSeekBar.setBuffered(buffered, duration);
    }

    private void updateLoopGuard(long position) {
        if (mLoopA != BufferedSeekBar.C_UNSET && mLoopB != BufferedSeekBar.C_UNSET
                && player != null && position >= mLoopB) {
            player.seekTo(mLoopA);
        }
    }

    private long lastSaveAt;

    private void saveProgressThrottled(long position, long duration) {
        long now = System.currentTimeMillis();
        if (now - lastSaveAt < 5_000L) return;
        lastSaveAt = now;
        writeProgress(position, duration);
    }

    private void saveProgressNow() {
        if (player == null) return;
        long duration = player.getDuration();
        if (duration == C.TIME_UNSET) duration = mKnownDurationMs;
        writeProgress(player.getCurrentPosition(), duration);
    }

    private void writeProgress(long position, long duration) {
        if (mediaUri == null || !mPrepared) return;
        if (duration <= 0) duration = mKnownDurationMs;
        final long dur = duration;
        mRepository.saveProgress(buildQueueItem(dur), position, dur);
        mRepository.saveVideoState(mVideoId,
                player != null ? player.getPlaybackParameters().speed : 1f, mAspectMode);
    }

    /** Reconstructs a minimal VideoItem for repository writes. */
    @NonNull
    private VideoItem buildQueueItem(long durationMs) {
        long id;
        try {
            id = Long.parseLong(mVideoId);
        } catch (NumberFormatException e) {
            id = Math.abs((long) mVideoId.hashCode());
        }
        return new VideoItem(id, mTitle, mediaUri, durationMs, 0L, 0L, 0L, 0, 0,
                mFolder, null, null);
    }

    // ---- Seek bar interaction ----

    private final class SeekBarListener implements android.widget.SeekBar.OnSeekBarChangeListener {
        @Override
        public void onProgressChanged(android.widget.SeekBar seekBar, int progress,
                                      boolean fromUser) {
            if (!fromUser || player == null) return;
            long duration = player.getDuration();
            if (duration == C.TIME_UNSET) duration = mKnownDurationMs;
            if (duration <= 0) return;
            long position = progress * duration / 1000L;
            if(mScrubPreview != null && mediaUri != null) mScrubPreview.show(mediaUri, position, progress / 1000f);
            mPositionText.setText(mShowRemaining && duration > 0
                ? "-" + FormatUtils.formatDuration(Math.max(0, duration - position))
                : FormatUtils.formatDuration(position));
        }

        @Override
        public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
            if(player != null) mUndoSeek = player.getCurrentPosition();
            mUserSeeking = true;
            showControls();
        }

        @Override
        public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
            if (player != null) {
                long duration = player.getDuration();
                if (duration == C.TIME_UNSET) duration = mKnownDurationMs;
                if (duration > 0) {
                    clearAbLoop();
                    player.seekTo(seekBar.getProgress() * duration / 1000L);
                }
            }
            mUserSeeking = false;
            if(mScrubPreview != null) mScrubPreview.hide();
            showControls();
        }
    }

    // ---- GestureCatcherView.Host ----

    private void refreshStudyMarkers() {
        if (mSeekBar == null || mVideoId == null) return;
        final String requestedId = mVideoId;
        mSeekBar.setChapters(null);
        mArtExecutor.execute(() -> {
            java.util.List<com.cinemax.nexus.study.StudyNote> notes =
                    com.cinemax.nexus.study.StudyDatabase.get(getApplicationContext()).notes().getSync(requestedId);
            long[] positions = notes.stream().mapToLong(note -> note.positionMs).distinct().sorted().limit(200).toArray();
            mMain.post(() -> {
                if (!isFinishing() && !isDestroyed() && requestedId.equals(mVideoId)) mSeekBar.setChapters(positions);
            });
        });
    }

    @Override
    public void onToggleControls() {
        if (mLocked) return;
        if (mControlsVisible) hideControls();
        else showControls();
    }

    @Override
    public void onDoubleTap(float xFraction) {
        if (mLocked || player == null) return;
        if (xFraction > 0.35f && xFraction < 0.65f) {
            togglePlayPause();
            return;
        }
        long step = mPrefs.getSeekIntervalShort();
        long duration = player.getDuration();
        if (duration == C.TIME_UNSET) duration = mKnownDurationMs;
        long target = player.getCurrentPosition() + (xFraction >= 0.65f ? step : -step);
        target = FormatUtils.clampPosition(target, duration);
        player.seekTo(target);
        showSeekHud(target, duration, xFraction >= 0.65f ? step : -step);
        showControls();
    }

    @Override
    public void onLongPressStart() {
        if (mLocked || player == null) return;
        mBeforeHoldSpeed = player.getPlaybackParameters().speed;
        player.setPlaybackParameters(new androidx.media3.common.PlaybackParameters(2f));
        Toast.makeText(this, R.string.fast_forward, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onLongPressEnd() {
        if (player == null) return;
        player.setPlaybackParameters(
                new androidx.media3.common.PlaybackParameters(mBeforeHoldSpeed));
    }

    @Override
    public void onDragStart() {
        // Brightness/volume gesture began; nothing to prime.
    }

    @Override
    public void onVerticalDrag(boolean leftHalf, float fractionDelta) {
        // "Volume swipe side" setting (MX parity): the chosen half drives volume,
        // the other half drives brightness. Default (volume on the right) keeps the
        // original left=brightness / right=volume mapping.
        boolean volume = leftHalf != mPrefs.isVolumeOnRight();
        if (volume) {
            adjustVolume(fractionDelta);
            showHud(getString(R.string.volume), volumeFraction(), true);
        } else {
            adjustBrightness(fractionDelta);
            showHud(getString(R.string.brightness), mBrightness, true);
        }
    }

    @Override
    public void onSeekDragStart() {
        if (player == null) return;
        mSeekBaseMs = player.getCurrentPosition();
        mSeekTargetMs = mSeekBaseMs;
        showControls();
    }

    @Override
    public void onSeekDrag(float fractionDelta, boolean commit) {
        if (player == null) return;
        long duration = player.getDuration();
        if (duration == C.TIME_UNSET) duration = mKnownDurationMs;
        if (duration <= 0) return;
        mSeekTargetMs = FormatUtils.clampPosition(
                mSeekTargetMs + (long) (fractionDelta * duration), duration);
        if (commit) {
            player.seekTo(mSeekTargetMs);
        } else {
            long delta = mSeekTargetMs - mSeekBaseMs;
            showSeekHud(mSeekTargetMs, duration, delta);
        }
    }

    @Override
    public void onPinch(float scaleFactor) {
        if (mLocked) return;
        applyZoom(mZoom * scaleFactor);
    }

    @Override
    public void onGestureEnd() {
        hideHudDelayed();
    }

    // ---- Brightness / volume / zoom ----

    private float mBrightness = -1f;
    private AudioManager mAudioManager;

    private void restoreBrightnessIfNeeded() {
        float stored = mPrefs.getBrightness();
        if (stored > 0f) {
            mBrightness = stored;
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = mBrightness;
            getWindow().setAttributes(lp);
        }
    }

    private void adjustBrightness(float fractionDelta) {
        if (mBrightness <= 0f) {
            float current = getWindow().getAttributes().screenBrightness;
            mBrightness = current > 0f ? current : 0.5f;
        }
        mBrightness = clamp(mBrightness + fractionDelta, 0.01f, 1f);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = mBrightness;
        getWindow().setAttributes(lp);
        mPrefs.setBrightness(mBrightness);
    }

    private void adjustVolume(float fractionDelta) {
        AudioManager am = audioManager();
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int current = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int target = Math.round(current + fractionDelta * max);
        target = (int) clamp(target, 0, max);
        if (target != current) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        }
    }

    private float volumeFraction() {
        AudioManager am = audioManager();
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max > 0 ? am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max : 0f;
    }

    private AudioManager audioManager() {
        if (mAudioManager == null) {
            mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        return mAudioManager;
    }

    private void applyZoom(float factor) {
        mZoom = clamp(factor, MIN_ZOOM, MAX_ZOOM);
        if (playerView == null) return;
        View surface = playerView.getVideoSurfaceView();
        if (surface == null) return;
        surface.setScaleX(mZoom);
        surface.setScaleY(mZoom);
    }

    // ---- HUD ----

    private void showHud(@NonNull String label, float fraction, boolean withProgress) {
        mSeekHud.setVisibility(View.GONE);
        mHud.setVisibility(View.VISIBLE);
        mHudVisible = true;
        mHudText.setText(String.format(Locale.US, "%s  %d%%", label,
                Math.round(fraction * 100)));
        mHudProgress.setVisibility(withProgress ? View.VISIBLE : View.GONE);
        if (withProgress) {
            mHudProgress.setProgress(Math.round(fraction * 100));
        }
    }

    private void showSeekHud(long targetMs, long durationMs, long deltaMs) {
        mHud.setVisibility(View.GONE);
        mHudVisible = false;
        mSeekHud.setVisibility(View.VISIBLE);
        mSeekHudVisible = true;
        String arrow = deltaMs >= 0 ? "\u25B6" : "\u25C0";
        String delta = String.format(Locale.US, "%s%s",
                deltaMs >= 0 ? "+" : "-",
                FormatUtils.formatDuration(Math.abs(deltaMs)));
        mSeekHud.setText(String.format(Locale.US, "%s  %s   %s",
                arrow, FormatUtils.formatDuration(targetMs), delta));
    }

    private final Runnable mHideHud = () -> {
        if (!mUserSeeking) {
            mHud.setVisibility(View.GONE);
            mSeekHud.setVisibility(View.GONE);
            mHudVisible = false;
            mSeekHudVisible = false;
        }
    };

    private void hideHudDelayed() {
        mMain.removeCallbacks(mHideHud);
        mMain.postDelayed(mHideHud, 700L);
    }

    // ---- Controls visibility ----

    private final Runnable mHideControls = this::hideControls;

    private void showControls() {
        // Never draw chrome over the floating window: in PiP the controls would cover
        // the whole (tiny) surface and could not be dismissed.
        if (mInPip) return;
        if (mLocked) return;
        // A hide animation already in flight would otherwise slam the overlay back to
        // INVISIBLE via its end action right after we make it visible again.
        mControlOverlay.animate().cancel();
        mControlOverlay.setVisibility(View.VISIBLE);
        mControlOverlay.setAlpha(0f);
        mControlOverlay.animate().alpha(1f).setDuration(160).start();
        mControlsVisible = true;
        updatePlayPauseIcon();
        updateDurationLabels();
        mMain.removeCallbacks(mHideControls);
        if (!mFocusMode) mMain.postDelayed(mHideControls, 2_500L);
    }

    private void hideControls() {
        if (mUserSeeking) return;
        mControlOverlay.animate().alpha(0f).setDuration(160)
                .withEndAction(() -> mControlOverlay.setVisibility(View.INVISIBLE))
                .start();
        mControlsVisible = false;
        mMain.removeCallbacks(mHideControls);
    }

    private void setLocked(boolean locked, boolean kidsLock) {
        mLocked = locked;
        mKidsLock = locked && kidsLock;
        findViewById(R.id.lock_button).setVisibility(locked ? View.VISIBLE : View.GONE);
        mCatcher.setGesturesEnabled(!locked && mPrefs.isGesturesEnabled());
        if (locked) {
            hideControls();
            Toast.makeText(this, R.string.locked, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.unlocked, Toast.LENGTH_SHORT).show();
            showControls();
        }
    }

    // ---- Aspect ratio & orientation ----

    private void applyAspectMode(int mode, boolean persist) {
        mAspectMode = mode;
        if (playerView == null) return;
        switch (mode) {
            case ASPECT_FILL:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                break;
            case ASPECT_STRETCH:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                break;
            case ASPECT_FIT:
            default:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                break;
        }
        applyZoom(1f);
        if (persist) mPrefs.setAspectRatioMode(mode);
    }

    private void updateOrientationFor(int width, int height) {
        if (mOrientationLocked || width <= 0 || height <= 0) return;
        int target = width > height
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
        if (getRequestedOrientation() != target) {
            setRequestedOrientation(target);
        }
    }

    // ---- Dialogs ----

    private void showSpeedDialog() {
        if (player == null) return;
        final float[] speeds = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f};
        String[] labels = new String[speeds.length];
        float current = player.getPlaybackParameters().speed;
        int checked = 0;
        for (int i = 0; i < speeds.length; i++) {
            labels[i] = FormatUtils.formatSpeed(speeds[i]);
            if (Math.abs(speeds[i] - current) < 0.01f) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.player_speed_dialog)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    player.setPlaybackParameters(
                            new androidx.media3.common.PlaybackParameters(speeds[which]));
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updateSpeedBadge(float speed) {
        if (Math.abs(speed - 1f) < 0.01f) {
            mSpeedBadge.setVisibility(View.GONE);
        } else {
            mSpeedBadge.setVisibility(View.VISIBLE);
            mSpeedBadge.setText(FormatUtils.formatSpeed(speed));
        }
    }

    private void showAspectDialog() {
        final int[] modes = {ASPECT_FIT, ASPECT_FILL, ASPECT_STRETCH};
        final String[] labels = {
                getString(R.string.aspect_fit),
                getString(R.string.aspect_fill),
                getString(R.string.aspect_stretch)
        };
        int checked = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == mAspectMode) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.player_aspect_dialog)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    applyAspectMode(modes[which], true);
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showAudioDialog() {
        if (player == null) return;
        List<Tracks.Group> groups = audioGroups();
        if (groups.isEmpty()) {
            Toast.makeText(this, R.string.no_audio_tracks, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[groups.size() + 1];
        labels[0] = getString(R.string.auto_track);
        int checked = 0;
        for (int i = 0; i < groups.size(); i++) {
            Format f = groups.get(i).getMediaTrackGroup().getFormat(0);
            labels[i + 1] = trackLabel(f, getString(R.string.audio_track));
            if (groups.get(i).isSelected()) checked = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.player_audio_dialog)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    if (which == 0) {
                        clearOverride(C.TRACK_TYPE_AUDIO);
                    } else {
                        selectGroup(C.TRACK_TYPE_AUDIO, groups.get(which - 1), 0);
                    }
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showSubtitleDialog() {
        if (player == null) return;
        List<Tracks.Group> groups = textGroups();
        // Combined list: Off + embedded tracks + the styling entries.
        final List<String> rows = new ArrayList<>();
        final List<Integer> kinds = new ArrayList<>();
        rows.add(getString(R.string.sub_none));
        kinds.add(0);
        for (Tracks.Group g : groups) {
            Format f = g.getMediaTrackGroup().getFormat(0);
            rows.add(trackLabel(f, getString(R.string.subtitles)));
            kinds.add(1);
        }
        rows.add(getString(R.string.sub_load_external));
        kinds.add(2);
        rows.add(getString(R.string.sub_size));
        kinds.add(3);
        rows.add(getString(R.string.sub_bg));
        kinds.add(4);
        rows.add(getString(R.string.subtitle_position));
        kinds.add(5);
        rows.add(getString(R.string.subtitle_studio));
        kinds.add(6);

        int checked = 0;
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).isSelected()) {
                checked = i + 1;
                break;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.player_subtitle_dialog)
                .setSingleChoiceItems(rows.toArray(new String[0]), checked, (d, which) -> {
                    int kind = kinds.get(which);
                    if (kind == 0) {
                        disableSubtitles();
                        d.dismiss();
                    } else if (kind == 1) {
                        mSubtitleStudio.useEmbedded();
                        selectGroup(C.TRACK_TYPE_TEXT, groups.get(which - 1), 0);
                        d.dismiss();
                    } else if (kind == 2) {
                        pickExternalSubtitle();
                    } else if (kind == 3) {
                        showSubtitleSizeDialog();
                    } else if (kind == 4) {
                        showSubtitleBackgroundDialog();
                    } else if (kind == 5) {
                        showSubtitlePositionDialog();
                    } else if(kind == 6) {
                        d.dismiss(); mSubtitleStudio.show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @NonNull
    private List<Tracks.Group> audioGroups() {
        return groupsOf(C.TRACK_TYPE_AUDIO);
    }

    @NonNull
    private List<Tracks.Group> textGroups() {
        return groupsOf(C.TRACK_TYPE_TEXT);
    }

    @NonNull
    private List<Tracks.Group> groupsOf(int trackType) {
        List<Tracks.Group> out = new ArrayList<>();
        if (player == null) return out;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() == trackType) out.add(group);
        }
        return out;
    }

    private void selectGroup(int trackType, @NonNull Tracks.Group group, int trackIndex) {
        if (player == null) return;
        TrackSelectionParameters params = player.getTrackSelectionParameters()
                .buildUpon()
                .clearOverridesOfType(trackType)
                .setTrackTypeDisabled(trackType, false)
                .setOverrideForType(new TrackSelectionOverride(
                        group.getMediaTrackGroup(), trackIndex))
                .build();
        player.setTrackSelectionParameters(params);
    }

    private void clearOverride(int trackType) {
        if (player == null) return;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon()
                .clearOverridesOfType(trackType)
                .build());
    }

    private void disableSubtitles() {
        if(mSubtitleStudio != null) mSubtitleStudio.disableAll();
        if (player == null) return;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setDisabledTrackTypes(Collections.singleton(C.TRACK_TYPE_TEXT))
                .build());
        // The last rendered cue would otherwise linger on screen.
        if (subtitleView != null) subtitleView.setCues(Collections.emptyList());
    }

    @NonNull
    private String trackLabel(@NonNull Format format, @NonNull String fallback) {
        if (format.label != null && !format.label.isEmpty()) return format.label;
        StringBuilder sb = new StringBuilder();
        if (format.language != null && !format.language.isEmpty()) {
            sb.append(format.language.toUpperCase(Locale.US));
        }
        if (format.channelCount > 0) {
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append(format.channelCount).append("ch");
        }
        if (format.bitrate > 0) {
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append(format.bitrate / 1000).append("kbps");
        }
        return sb.length() > 0 ? sb.toString() : fallback;
    }

    // ---- External subtitles ----

    private void pickExternalSubtitle() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/x-subrip", "text/vtt", "text/x-ssa", "application/ttml+xml",
                "text/plain", "*/*"
        });
        try {
            startActivityForResult(intent, REQUEST_SUBTITLE);
        } catch (Exception e) {
            Toast.makeText(this, R.string.sub_sync_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == REQUEST_STUDY && resultCode == RESULT_OK && data != null && player != null) {
            String action=data.getStringExtra("study_action");
            if("captions".equals(action)) {
                final String requestedStudyId = mVideoId;
                mArtExecutor.execute(() -> {
                    try {
                        com.cinemax.nexus.subtitle.SubtitleTimeline timeline=com.cinemax.nexus.study.TranscriptStore.read(this,requestedStudyId);
                        if(timeline != null) {
                            java.io.File file=new java.io.File(getCacheDir(),"study-captions-"+Integer.toHexString(requestedStudyId.hashCode())+".srt");
                            try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){out.write(timeline.toSrt(0).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                            runOnUiThread(() -> {if(!mReleased && requestedStudyId.equals(mVideoId))mSubtitleStudio.load(Uri.fromFile(file),0,true);});
                        }
                    } catch(Exception ignored) {}
                });
            } else {
                long start=data.getLongExtra("start",0),end=data.getLongExtra("end",0);mUndoSeek=player.getCurrentPosition();clearAbLoop();
                if("loop".equals(action) && end>start){mLoopA=start;mLoopB=end;mSeekBar.setLoopMarkers(start,end);updateAbBadge();}
                player.seekTo(start);mPlayOnResume=true;player.play();
            }
            return;
        }
        if(mSubtitleStudio != null && mSubtitleStudio.onResult(requestCode,resultCode,data)) return;
        if (requestCode != REQUEST_SUBTITLE || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            // Best-effort so the URI survives a rotation / process restart.
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Not all providers grant persistable access; playback still works.
        }
        addExternalSubtitle(uri);
    }

    private void addExternalSubtitle(@NonNull Uri uri) {
        if (player == null) return;
        MediaItem.SubtitleConfiguration config =
                new MediaItem.SubtitleConfiguration.Builder(uri)
                        .setMimeType(mimeForUri(uri))
                        .setLabel(labelForUri(uri))
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build();
        mSubtitleConfigs.add(config);
        long position = player.getCurrentPosition();
        boolean wasPlaying = player.isPlaying();
        player.setMediaItem(buildMediaItem());
        player.prepare();
        player.seekTo(position);
        mPlayOnResume = wasPlaying;
        if (wasPlaying) player.play();
        showSubtitleToast(getString(R.string.sub_sync_success));
    }

    @NonNull
    private String mimeForUri(@NonNull Uri uri) {
        String path = uri.getPath();
        String lower = path != null ? path.toLowerCase(Locale.US) : "";
        if (lower.endsWith(".srt")) return MimeTypes.APPLICATION_SUBRIP;
        if (lower.endsWith(".vtt") || lower.endsWith(".webvtt")) return MimeTypes.TEXT_VTT;
        if (lower.endsWith(".ass") || lower.endsWith(".ssa")) return MimeTypes.TEXT_SSA;
        if (lower.endsWith(".ttml") || lower.endsWith(".dfxp")) {
            return MimeTypes.APPLICATION_TTML;
        }
        // Fall back to what the provider itself reports.
        String resolved = getContentResolver().getType(uri);
        return resolved != null && !resolved.isEmpty()
                ? resolved : MimeTypes.APPLICATION_SUBRIP;
    }

    @NonNull
    private String labelForUri(@NonNull Uri uri) {
        String path = uri.getLastPathSegment();
        if (path == null) return getString(R.string.subtitles);
        int cut = path.lastIndexOf('/');
        return cut >= 0 ? path.substring(cut + 1) : path;
    }

    private void showSubtitleSizeDialog() {
        final int[] values = {70, 100, 140, 190};
        String[] labels = {
                getString(R.string.subtitle_size_small),
                getString(R.string.subtitle_size_normal),
                getString(R.string.subtitle_size_large),
                getString(R.string.subtitle_size_huge)
        };
        int checked = mSubtitleSizePercent == 70 ? 0
                : mSubtitleSizePercent == 140 ? 2
                : mSubtitleSizePercent == 190 ? 3 : 1;
        new AlertDialog.Builder(this)
                .setTitle(R.string.sub_size)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    mSubtitleSizePercent = values[which];
                    applySubtitleSize(values[which]);
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void applySubtitleSize(int percent) {
        if (subtitleView == null) return;
        // Fraction of the video view height; 0.06f is roughly 18sp on a phone.
        subtitleView.setFractionalTextSize(0.06f * (percent / 100f));
    }

    private void showSubtitleBackgroundDialog() {
        final int[] values = {0, 1, 2};
        String[] labels = {
                getString(R.string.subtitle_bg_none),
                getString(R.string.subtitle_bg_semi),
                getString(R.string.subtitle_bg_black)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.sub_bg)
                .setSingleChoiceItems(labels, mSubtitleBg, (d, which) -> {
                    mSubtitleBg = which;
                    applySubtitleBackground(which);
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void applySubtitleBackground(int mode) {
        if (subtitleView == null) return;
        mSubtitleBg = mode;
        int window;
        switch (mode) {
            case 0:
                window = Color.TRANSPARENT;
                break;
            case 2:
                window = Color.BLACK;
                break;
            case 1:
            default:
                window = 0x99000000;
                break;
        }
        // Foreground stays white with a hard outline so it remains legible over any
        // frame regardless of the chosen window.
        subtitleView.setStyle(new CaptionStyleCompat(
                Color.WHITE,
                Color.TRANSPARENT,
                window,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                Typeface.DEFAULT_BOLD));
    }

    private void showSubtitleToast(@NonNull String message) {
        mSubtitleToast.setText(message);
        mSubtitleToast.setVisibility(View.VISIBLE);
        mMain.postDelayed(() -> mSubtitleToast.setVisibility(View.GONE), 2_000L);
    }

    private void showSubtitlePositionDialog() {
        // 0 = Media3's own placement, up to 40% of the view height lifted off the bottom
        // edge. Steps of 1% keep the seek bar honest without an input field.
        final float[] value = {mPrefs.getSubtitleOffset()};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView label = new TextView(this);
        label.setTextColor(getColor(R.color.nexus_text));
        box.addView(label);

        SeekBar bar = new SeekBar(this);
        bar.setMax(40);
        bar.setProgress(Math.round(value[0] * 100f));
        bar.setOnSeekBarChangeListener(new ValueSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value[0] = progress / 100f;
                label.setText(progress + "%");
                applySubtitleOffset(value[0]);
            }
        });
        label.setText(Math.round(value[0] * 100f) + "%");
        box.addView(bar);

        new AlertDialog.Builder(this)
                .setTitle(R.string.subtitle_position)
                .setView(box)
                .setPositiveButton(R.string.done, (d, w) -> {
                    mPrefs.setSubtitleOffset(value[0]);
                })
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    applySubtitleOffset(mPrefs.getSubtitleOffset());
                })
                .setOnCancelListener(d -> applySubtitleOffset(mPrefs.getSubtitleOffset()))
                .show();
    }

    /**
     * Lifts the subtitle block off the bottom edge. Only the rendering view moves - the
     * cues themselves are untouched, so nothing about the timing or styling changes.
     */
    private void applySubtitleOffset(float fraction) {
        if (subtitleView == null) return;
        subtitleView.setTranslationY(-subtitleView.getHeight() * fraction);
    }

    // ---- Sleep timer ----

    private void showSleepTimerDialog() {
        if (mSleepTimer != null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.sleep_timer)
                    .setItems(new String[]{
                            getString(R.string.cancel),
                            getString(R.string.sleep_timer_cancelled)
                    }, (d, which) -> {
                        if (which == 0) d.dismiss();
                        else cancelSleepTimer();
                    })
                    .show();
            return;
        }
        final long[] minutes = {5L, 10L, 15L, 20L, 30L, 45L, 60L};
        String[] labels = new String[minutes.length];
        for (int i = 0; i < minutes.length; i++) {
            labels[i] = getString(R.string.minutes_short, minutes[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.player_sleep_dialog)
                .setItems(labels, (d, which) -> startSleepTimer(minutes[which] * 60_000L))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void startSleepTimer(long totalMs) {
        cancelSleepTimer();
        mSleepRemainingMs = totalMs;
        if (mPlaybackService != null) mPlaybackService.setSleepTimer(totalMs);
        mTimerBadge.setVisibility(View.VISIBLE);
        // Wall-clock alarm (course topic 08.2): fires even with the screen off and doze
        // active. The CountDownTimer below only drives the visible badge.
        scheduleSleepAlarm(totalMs);
        mSleepTimer = new CountDownTimer(totalMs, 1_000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                mSleepRemainingMs = millisUntilFinished;
                mTimerBadge.setText(FormatUtils.formatDuration(millisUntilFinished));
            }

            @Override
            public void onFinish() {
                mSleepTimer = null;
                mTimerBadge.setVisibility(View.GONE);
                mPlayOnResume = false;
                // With an exact alarm scheduled, SleepTimerReceiver performs the pause and
                // toast a beat later. When exact alarms are unavailable (permission
                // revoked), the alarm is only a best-effort backstop, so act here too —
                // the service also watches the deadline while this screen is closed.
                if (!mAlarmExact) {
                    if (player != null) player.pause();
                    hideControls();
                    Toast.makeText(PlayerActivity.this, R.string.sleep_timer_done,
                            Toast.LENGTH_LONG).show();
                }
            }
        }.start();
    }

    private void cancelSleepTimer() {
        if (mPlaybackService != null) mPlaybackService.setSleepTimer(0);
        if (mSleepTimer != null) {
            mSleepTimer.cancel();
            mSleepTimer = null;
        }
        cancelSleepAlarm();
        if (mTimerBadge != null) mTimerBadge.setVisibility(View.GONE);
    }

    private static final int SLEEP_ALARM_REQ = 0x51EE; // stable request code

    private void scheduleSleepAlarm(long delayMs) {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) {
            mAlarmExact = false;
            return;
        }
        PendingIntent pi = sleepAlarmPendingIntent();
        long at = System.currentTimeMillis() + delayMs;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                mAlarmExact = true;
            } else {
                // Inexact but still Doze-aware; the UI timer covers the foreground case.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                mAlarmExact = false;
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            mAlarmExact = false;
        }
    }

    private void cancelSleepAlarm() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) {
            try {
                am.cancel(sleepAlarmPendingIntent());
            } catch (SecurityException ignored) {
                // Cancelling never requires exact-alarm permission on any API level.
            }
        }
    }

    @NonNull
    private PendingIntent sleepAlarmPendingIntent() {
        Intent intent = new Intent(this, SleepTimerReceiver.class)
                .setAction(SleepTimerReceiver.ACTION);
        return PendingIntent.getBroadcast(this, SLEEP_ALARM_REQ, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---- A-B loop / bookmarks / night mode / boost ----

    private void toggleAbLoop() {
        long position = player != null ? player.getCurrentPosition() : 0L;
        if (mLoopA == BufferedSeekBar.C_UNSET) {
            mLoopA = position;
            mLoopB = BufferedSeekBar.C_UNSET;
            mSeekBar.setLoopMarkers(mLoopA, mLoopB);
            updateAbBadge();
            Toast.makeText(this, getString(R.string.ab_point_set,
                    FormatUtils.formatDuration(mLoopA)), Toast.LENGTH_SHORT).show();
        } else if (mLoopB == BufferedSeekBar.C_UNSET) {
            if (position <= mLoopA + 500L) {
                Toast.makeText(this, R.string.ab_loop, Toast.LENGTH_SHORT).show();
                return;
            }
            mLoopB = position;
            mSeekBar.setLoopMarkers(mLoopA, mLoopB);
            updateAbBadge();
            if (player != null) player.seekTo(mLoopA);
            Toast.makeText(this, getString(R.string.ab_loop_active,
                    FormatUtils.formatDuration(mLoopA),
                    FormatUtils.formatDuration(mLoopB)), Toast.LENGTH_SHORT).show();
        } else {
            clearAbLoop();
        }
    }

    private void clearAbLoop() {
        mLoopA = BufferedSeekBar.C_UNSET;
        mLoopB = BufferedSeekBar.C_UNSET;
        mSeekBar.setLoopMarkers(mLoopA, mLoopB);
        updateAbBadge();
        Toast.makeText(this, R.string.ab_loop_cleared, Toast.LENGTH_SHORT).show();
    }

    private void updateAbBadge() {
        if (mPlaybackService != null) mPlaybackService.setLoop(mLoopA, mLoopB);
        if (mLoopA != BufferedSeekBar.C_UNSET && mLoopB != BufferedSeekBar.C_UNSET) {
            mAbBadge.setVisibility(View.VISIBLE);
            mAbBadge.setText(String.format(Locale.US, "A %s \u2013 B %s",
                    FormatUtils.formatDuration(mLoopA),
                    FormatUtils.formatDuration(mLoopB)));
        } else if (mLoopA != BufferedSeekBar.C_UNSET) {
            mAbBadge.setVisibility(View.VISIBLE);
            mAbBadge.setText("A \u2013 " + FormatUtils.formatDuration(mLoopA));
        } else {
            mAbBadge.setVisibility(View.GONE);
        }
    }

    /** Seek bar listener that only cares about the value, not about tracking transitions. */
    private abstract static class ValueSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }

    // ---- Trim ----

    /**
     * Cuts a copy of the current range out to Movies/CineMaxNexus. The source file is only
     * ever read, so there is no way to lose the original by trimming it.
     *
     * <p>An A-B loop already marks the exact range the viewer cares about, so it becomes the
     * default when one is set; otherwise the window opens at the play head.
     */
    private void showTrimDialog() {
        if (player == null || mediaUri == null) return;
        if (PermissionUtils.needsTrimWritePermission()
                && !PermissionUtils.hasTrimWritePermission(this)) {
            // Android 9 and below write the file by hand and so need a real grant. Asking
            // before the range picker appears avoids a save that is doomed to fail.
            PermissionUtils.requestTrimWritePermission(this);
            return;
        }
        long durationMs = player.getDuration();
        if (durationMs <= 0 || durationMs == C.TIME_UNSET) {
            Toast.makeText(this, R.string.trim_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        final int maxSec = (int) Math.min(durationMs / 1000L, 86_400L);
        if (maxSec < 1) {
            Toast.makeText(this, R.string.trim_too_short, Toast.LENGTH_SHORT).show();
            return;
        }

        long from = player.getCurrentPosition();
        long to;
        if (mLoopA != BufferedSeekBar.C_UNSET && mLoopB != BufferedSeekBar.C_UNSET) {
            from = Math.min(mLoopA, mLoopB);
            to = Math.max(mLoopA, mLoopB);
        } else {
            to = Math.min(durationMs, from + 30_000L);
        }
        final int[] startSec = {Math.max(0, Math.min((int) (from / 1000L), maxSec - 1))};
        final int[] endSec = {Math.max(startSec[0] + 1, Math.min((int) (to / 1000L), maxSec))};

        View box = LayoutInflater.from(this).inflate(R.layout.dialog_trim, null, false);
        TextView summary = box.findViewById(R.id.trim_summary);
        TextView startLabel = box.findViewById(R.id.trim_start_label);
        TextView endLabel = box.findViewById(R.id.trim_end_label);
        SeekBar startBar = box.findViewById(R.id.trim_start);
        SeekBar endBar = box.findViewById(R.id.trim_end);
        startBar.setMax(maxSec);
        endBar.setMax(maxSec);
        startBar.setProgress(startSec[0]);
        endBar.setProgress(endSec[0]);

        Runnable repaint = () -> {
            long s = startSec[0] * 1000L;
            long e = endSec[0] * 1000L;
            startLabel.setText(getString(R.string.trim_start) + "   " + FormatUtils.formatDuration(s));
            endLabel.setText(getString(R.string.trim_end) + "   " + FormatUtils.formatDuration(e));
            summary.setText(String.format(Locale.US, "%s → %s · %s",
                    FormatUtils.formatDuration(s), FormatUtils.formatDuration(e),
                    FormatUtils.formatDuration(e - s)));
        };

        // The two ends are tied together: overlapping them would ask for an inside-out clip.
        startBar.setOnSeekBarChangeListener(new ValueSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                startSec[0] = progress;
                if (endSec[0] <= startSec[0]) {
                    endSec[0] = Math.min(maxSec, startSec[0] + 1);
                    endBar.setProgress(endSec[0]);
                }
                repaint.run();
            }
        });
        endBar.setOnSeekBarChangeListener(new ValueSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (progress <= startSec[0]) {
                    endSec[0] = Math.min(maxSec, startSec[0] + 1);
                    seekBar.setProgress(endSec[0]);
                } else {
                    endSec[0] = progress;
                }
                repaint.run();
            }
        });
        repaint.run();

        new AlertDialog.Builder(this)
                .setTitle(R.string.trim_video)
                .setView(box)
                .setPositiveButton(R.string.trim_save,
                        (d, w) -> runTrim(startSec[0] * 1000L, endSec[0] * 1000L))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void runTrim(long startMs, long endMs) {
        final Uri source = mediaUri;
        if (source == null) return;
        long startUs = startMs * 1000L;
        long endUs = endMs * 1000L;
        if (!VideoTrimmer.isSupportedRange(startUs, endUs)) {
            Toast.makeText(this, R.string.trim_too_short, Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressBar bar = new ProgressBar(this);
        bar.setIndeterminate(true);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        bar.setPadding(pad, pad, pad, pad);
        mTrimDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.trim_running)
                .setView(bar)
                .setCancelable(false)
                .create();
        mTrimDialog.show();

        VideoTrimmer.trim(this, source, startUs, endUs, new VideoTrimmer.Callback() {
            @Override
            public void onFinished(@NonNull Uri output, boolean needsScan) {
                dismissTrimProgress();
                Toast.makeText(PlayerActivity.this, R.string.trim_saved,
                        Toast.LENGTH_LONG).show();
                try {
                    startActivity(VideoTrimmer.viewIntent(output));
                } catch (ActivityNotFoundException e) {
                    // Nothing else can open it, but the clip is safely on disk either way.
                }
            }

            @Override
            public void onFailed(@NonNull Exception error) {
                dismissTrimProgress();
                Toast.makeText(PlayerActivity.this, R.string.trim_failed,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /** The trim callbacks arrive on the worker thread, so dismissal is marshalled home. */
    private void dismissTrimProgress() {
        runOnUiThread(() -> {
            if (mTrimDialog != null) {
                try {
                    mTrimDialog.dismiss();
                } catch (Exception ignored) {
                    // The window can already be gone when the activity is finishing.
                }
                mTrimDialog = null;
            }
        });
    }

    private void addBookmark() {
        if (player == null) return;
        long position = player.getCurrentPosition();
        final String videoId = mVideoId;
        final String fallbackTitle = mTitle;
        android.widget.EditText note = new android.widget.EditText(this);
        note.setHint(R.string.bookmark_note_hint);
        note.setSingleLine(true);
        note.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        note.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this).setTitle(R.string.named_bookmark)
                .setMessage(FormatUtils.formatDuration(position)).setView(note)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    String label = note.getText().toString().trim();
                    mRepository.addBookmark(videoId, label.isEmpty() ? fallbackTitle : label, position);
                    showSubtitleToast(getString(R.string.bookmark_added, FormatUtils.formatDuration(position)));
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void showBookmarksDialog() {
        new Thread(() -> {
            List<com.cinemax.nexus.data.db.entity.BookmarkEntity> items =
                    mRepository.getBookmarksSync(mVideoId);
            runOnUiThread(() -> {
                if (items.isEmpty()) {
                    Toast.makeText(this, R.string.bookmarks_empty,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] labels = new String[items.size()];
                for (int i = 0; i < items.size(); i++) {
                    labels[i] = FormatUtils.formatDuration(items.get(i).positionMs)
                            + (items.get(i).title != null
                            ? "  \u00B7  " + items.get(i).title : "");
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.bookmarks_list)
                        .setItems(labels, (d, which) -> {
                            if (player != null) {
                                player.seekTo(items.get(which).positionMs);
                            }
                        })
                        .setNeutralButton(R.string.delete, (d, which) -> {
                            new AlertDialog.Builder(this)
                                    .setTitle(R.string.delete_bookmark)
                                    .setItems(labels, (dd, w) -> {
                                        mRepository.deleteBookmark(items.get(w).id);
                                        Toast.makeText(this, R.string.delete,
                                                Toast.LENGTH_SHORT).show();
                                    })
                                    .setNegativeButton(R.string.cancel, null)
                                    .show();
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            });
        }).start();
    }

    private void toggleNightMode() {
        mNightMode = !mNightMode;
        // Night mode dims and warms the picture; the closest honest approximation
        // without a shader is lowering brightness plus a slight audio cut.
        if (player != null) {
            player.setVolume(mNightMode ? 0.7f : 1f);
        }
        adjustBrightness(mNightMode ? -0.25f : 0.25f);
        Toast.makeText(this, mNightMode ? R.string.night_mode_on : R.string.night_mode_off,
                Toast.LENGTH_SHORT).show();
    }

    private void showAudioBoostDialog() {
        final int[] values = {100, 125, 150, 175, 200};
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] + "%";
        }
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] / 100f - mAudioBoost) < 0.01f) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.audio_boost)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    mAudioBoost = values[which] / 100f;
                    if (applyAudioBoost(true)) {
                        showSubtitleToast(getString(R.string.audio_boost_on, values[which]));
                    }
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private boolean applyAudioBoost(boolean notify) {
        boolean supported = player != null && mBoostController.apply(player.getAudioSessionId(), mAudioBoost);
        if (!supported) {
            mAudioBoost = 1f;
            mBoostController.release();
            if (notify) Toast.makeText(this, R.string.boost_unavailable, Toast.LENGTH_LONG).show();
        }
        if (mPlaybackService != null) mPlaybackService.setBoostLevel(mAudioBoost);
        return supported;
    }

    // ---- Equalizer ----

    /**
     * Binds (or re-binds) the platform equalizer to an audio session.
     *
     * <p>Does nothing until the user has actually picked a preset, so playback never pays
     * for an effect nobody switched on.
     */
    private void attachEqualizer(int audioSessionId) {
        mEqualizer = mPlaybackService == null ? null : mPlaybackService.attachEqualizer(audioSessionId);
    }

    /**
     * Preset picker. The preset list and its names come from the device's own audio HAL -
     * there is no hard-coded catalogue that can drift out of date.
     */
    private void showEqualizerDialog() {
        final int sessionId = (player != null) ? player.getAudioSessionId() : 0;
        if (sessionId <= 0) {
            Toast.makeText(this, R.string.equalizer_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        int current = mPrefs.getEqPreset();
        final int[] indices;
        final String[] labels;
        android.media.audiofx.Equalizer probe = null;
        try {
            probe = new android.media.audiofx.Equalizer(0, sessionId);
            short count = probe.getNumberOfPresets();
            indices = new int[count + 1];
            labels = new String[count + 1];
            indices[0] = -1;
            labels[0] = getString(R.string.equalizer_off);
            for (int i = 0; i < count; i++) {
                indices[i + 1] = i;
                String name = probe.getPresetName((short) i);
                labels[i + 1] = (name == null || name.isEmpty())
                        ? getString(R.string.equalizer_preset_n, i) : name;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "equalizer probe failed", e);
            Toast.makeText(this, R.string.equalizer_failed, Toast.LENGTH_SHORT).show();
            return;
        } finally {
            if (probe != null) {
                try {
                    probe.release();
                } catch (RuntimeException ignored) {
                }
            }
        }

        int checked = 0;
        for (int i = 0; i < indices.length; i++) {
            if (indices[i] == current) checked = i;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.equalizer)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    final int preset = indices[which];
                    mPrefs.setEqPreset(preset);
                    attachEqualizer(sessionId);
                    showSubtitleToast(labels[which]);
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Manual portrait/landscape toggle beside the timeline.
     *
     * <p>The player normally picks orientation from the video's own shape, which is right
     * most of the time but offers no way out when the display or window cannot follow it:
     * the picture then appears turned on its side and the top bar - back arrow included -
     * lands along the bottom edge of the window. This hands the choice back to the viewer
     * and locks it, so the video-size rule cannot immediately undo the tap.
     */
    private void rotateScreen() {
        // True when we are landscape right now, so the tap always asks for the OTHER
        // orientation - deriving the flag the other way round re-asserted the current
        // one and the button appeared to do nothing.
        boolean nowLandscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        mOrientationLocked = true;
        setRequestedOrientation(nowLandscape
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        Toast.makeText(this, nowLandscape ? R.string.switched_portrait : R.string.switched_landscape,
                Toast.LENGTH_SHORT).show();
    }

    private void updateRotateButton() {
        View button = findViewById(R.id.btn_rotate);
        if (button == null) return;
        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        String action = getString(landscape ? R.string.switch_to_portrait : R.string.switch_to_landscape);
        button.setContentDescription(action);
        androidx.appcompat.widget.TooltipCompat.setTooltipText(button, action);
    }

    private void toggleOrientationLock() {
        mOrientationLocked = !mOrientationLocked;
        if (mOrientationLocked) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            Toast.makeText(this, R.string.orientation_locked, Toast.LENGTH_SHORT).show();
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
            if (player != null) {
                Format f = player.getVideoFormat();
                if (f != null) updateOrientationFor(f.width, f.height);
            }
            Toast.makeText(this, R.string.orientation_unlocked, Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleBackgroundPlay() {
        mBackgroundPlay = !mBackgroundPlay;
        Toast.makeText(this, mBackgroundPlay
                        ? R.string.background_play_enabled
                        : R.string.background_play_disabled,
                Toast.LENGTH_SHORT).show();
    }

    // ---- Decoder ----

    private void showDecoderDialog() {
        String[] labels = {
                getString(R.string.decoder_hw),
                getString(R.string.decoder_sw)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.player_decoder_dialog)
                .setSingleChoiceItems(labels, mUseSoftwareDecoder ? 1 : 0, (d, which) -> {
                    boolean wantSoftware = which == 1;
                    d.dismiss();
                    if (wantSoftware != mUseSoftwareDecoder) {
                        mUseSoftwareDecoder = wantSoftware;
                        switchDecoder();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Rebuilds the player with a different renderer set, preserving the position. */
    private void switchDecoder() {
        long position = player != null ? player.getCurrentPosition() : 0L;
        boolean wasPlaying = player != null && player.isPlaying();
        releasePlayer();
        initializePlayer();
        if (player != null) {
            player.seekTo(position);
            mPlayOnResume = wasPlaying;
            if (wasPlaying) player.play();
        }
        updateDecoderBadge();
        showSubtitleToast(getString(mUseSoftwareDecoder
                ? R.string.decoder_sw : R.string.decoder_hw));
    }

    private void updateDecoderBadge() {
        mDecoderBadge.setText(mUseSoftwareDecoder
                ? getString(R.string.decoder_sw)
                : getString(R.string.decoder_hw));
    }

    // ---- Queue / info / screenshot ----

    private void showQueueDialog() {
        if (mQueue.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_play, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[mQueue.size()];
        for (int i = 0; i < mQueue.size(); i++) {
            labels[i] = mQueue.get(i).title;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.queue)
                .setSingleChoiceItems(labels, mQueueIndex, (d, which) -> {
                    playItem(mQueue.get(which), which);
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showInfoDialog() {
        if (player == null || mediaUri == null) return;
        Format video = player.getVideoFormat();
        long duration = player.getDuration();
        if (duration == C.TIME_UNSET || duration <= 0) duration = mKnownDurationMs;

        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.video_info)).append("\n\n");
        sb.append(mTitle).append("\n\n");
        if (video != null && video.width > 0) {
            sb.append(getString(R.string.info_resolution)).append(": ")
                    .append(video.width).append(" \u00D7 ").append(video.height)
                    .append("  (").append(FormatUtils.formatResolution(
                            video.width, video.height)).append(")\n");
        }
        sb.append(getString(R.string.info_duration)).append(": ")
                .append(FormatUtils.formatDuration(duration)).append("\n");
        // Real byte count: MediaStore's value from the queue item, else the file
        // descriptor for one-off opens. This line used to pass mKnownDurationMs to
        // formatSize - i.e. the duration in milliseconds rendered as bytes ("15.9 KB").
        long sizeBytes = -1;
        if (mQueueIndex >= 0 && mQueueIndex < mQueue.size()) {
            sizeBytes = mQueue.get(mQueueIndex).sizeBytes;
        }
        if (sizeBytes <= 0) {
            try (AssetFileDescriptor afd =
                         getContentResolver().openAssetFileDescriptor(mediaUri, "r")) {
                if (afd != null) sizeBytes = afd.getLength();
            } catch (Exception ignored) {
                // Unknown length (pipe / remote) - omit the line rather than invent one.
            }
        }
        if (sizeBytes > 0) {
            sb.append(getString(R.string.info_size)).append(": ")
                    .append(FormatUtils.formatSize(sizeBytes)).append("\n");
        }
        // The container's real MIME (video/mp4 ...). This used to print the URI
        // *scheme*, which read "content" for every MediaStore file.
        sb.append(getString(R.string.info_mime)).append(": ")
                .append(resolveContainerMime(mediaUri)).append("\n");
        sb.append(getString(R.string.info_path)).append(": ")
                .append(mediaUri.toString());

        new AlertDialog.Builder(this)
                .setTitle(R.string.info_title)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    /**
     * Container MIME for the playing file: the resolver's answer (MediaStore knows it),
     * then the file extension, then the decoder's sample type, else an em dash.
     */
    @NonNull
    private String resolveContainerMime(@NonNull Uri uri) {
        try {
            String mime = getContentResolver().getType(uri);
            if (mime != null) return mime;
        } catch (Exception ignored) {
            // Fall through to the extension.
        }
        String path = uri.getPath();
        if (path != null) {
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot < path.length() - 1) {
                String ext = path.substring(dot + 1).toLowerCase(Locale.US);
                String mapped = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (mapped != null) return mapped;
            }
        }
        Format f = player != null ? player.getVideoFormat() : null;
        if (f != null && f.sampleMimeType != null) return f.sampleMimeType;
        return "\u2014";
    }

    private void captureScreenshot() {
        if (playerView == null) return;
        View surface = playerView.getVideoSurfaceView();
        if (!(surface instanceof TextureView) || !((TextureView) surface).isAvailable()) {
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap frame = ((TextureView) surface).getBitmap();
        if (frame == null) {
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = "CineMax_" + System.currentTimeMillis() + ".png";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveFrameToMediaStore(frame, name);
        } else {
            saveFrameToAppDir(frame, name);
        }
    }

    private void saveFrameToMediaStore(@NonNull Bitmap frame, @NonNull String name) {
        try {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/CineMaxNexus");
            Uri out = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (out == null) throw new IllegalStateException("insert failed");
            try (OutputStream os = resolver.openOutputStream(out)) {
                if (os == null) throw new IllegalStateException("no stream");
                frame.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            Toast.makeText(this, getString(R.string.screenshot_saved_to,
                    Environment.DIRECTORY_PICTURES + "/CineMaxNexus"),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show();
        } finally {
            frame.recycle();
        }
    }

    private void saveFrameToAppDir(@NonNull Bitmap frame, @NonNull String name) {
        try {
            java.io.File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (dir == null) dir = getFilesDir();
            java.io.File out = new java.io.File(dir, name);
            try (OutputStream os = new java.io.FileOutputStream(out)) {
                frame.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            Toast.makeText(this, getString(R.string.screenshot_saved_to, out.getAbsolutePath()),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show();
        } finally {
            frame.recycle();
        }
    }

    // ---- PiP ----

    private void enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            Format f = player != null ? player.getVideoFormat() : null;
            Rational ratio;
            if (f != null && f.width > 0 && f.height > 0) {
                float aspect = (float) f.width / f.height;
                ratio = aspect < 1f / 2.39f ? new Rational(100, 239)
                        : aspect > 2.39f ? new Rational(239, 100) : new Rational(f.width, f.height);
            } else {
                ratio = new Rational(16, 9);
            }
            hideControls();
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(ratio)
                    .build();
            enterPictureInPictureMode(params);
        } catch (Exception ignored) {
            Toast.makeText(this, R.string.player_pip, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isInPipModeCompat() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        return isInPictureInPictureMode();
    }

    /** Validated exact seeking; invalid input keeps the dialog open for correction. */
    private void showGoToTimeDialog() {
        if (player == null || !player.isCurrentMediaItemSeekable()) {
            Toast.makeText(this, R.string.seek_unavailable, Toast.LENGTH_SHORT).show(); return;
        }
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.time_hint);
        input.setText(FormatUtils.formatDuration(player.getCurrentPosition()));
        input.setSelectAllOnFocus(true);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.go_to_time)
                .setView(input).setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.cancel, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                long position = com.cinemax.nexus.util.PlaybackTime.parseMillis(input.getText().toString());
                if (player == null || !player.isCurrentMediaItemSeekable()
                        || (player.getDuration() != C.TIME_UNSET && position > player.getDuration()))
                    throw new IllegalArgumentException();
                clearAbLoop();
                player.seekTo(position);
                dialog.dismiss();
                showControls();
            } catch (IllegalArgumentException error) { input.setError(getString(R.string.invalid_time)); }
        }));
        dialog.show();
    }

    // ---- More menu ----

    private void showMoreMenu(@NonNull View anchor) {
        mMain.removeCallbacks(mHideControls);
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.setOnDismissListener(ignored -> showControls());
        menu.getMenuInflater().inflate(R.menu.menu_player, menu.getMenu());
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if(id == R.id.tool_queue) { findViewById(R.id.btn_queue).performClick(); return true; }
            if(id == R.id.tool_lock) { findViewById(R.id.btn_lock).performClick(); return true; }
            if(id == R.id.tool_shuffle) { findViewById(R.id.btn_shuffle).performClick(); return true; }
            if(id == R.id.tool_repeat) { findViewById(R.id.btn_repeat).performClick(); return true; }
            if(id == R.id.tool_ratio) { findViewById(R.id.btn_ratio).performClick(); return true; }
            if(id == R.id.tool_audio) { findViewById(R.id.btn_audio).performClick(); return true; }
            if(id == R.id.tool_timer) { findViewById(R.id.btn_timer).performClick(); return true; }
            if(id == R.id.tool_pip) { findViewById(R.id.btn_pip).performClick(); return true; }
            if(id == R.id.tool_rotate) { findViewById(R.id.btn_rotate).performClick(); return true; }
            if(id == R.id.action_study_workspace) {
                if(player != null && mediaUri != null) {
                    player.pause(); mPlayOnResume = false;
                    startActivityForResult(new Intent(this,com.cinemax.nexus.study.StudyActivity.class)
                            .putExtra("source",mediaUri.toString()).putExtra("key",mVideoId).putExtra("title",mTitle)
                            .putExtra("position",player.getCurrentPosition()).putExtra("duration",Math.max(mKnownDurationMs,player.getDuration())),REQUEST_STUDY);
                }
            } else if(id == R.id.action_undo_seek) {
                if(player != null && mUndoSeek != C.TIME_UNSET) {long current=player.getCurrentPosition();clearAbLoop();player.seekTo(mUndoSeek);mUndoSeek=current;}
            } else if (id == R.id.action_subtitle_studio) {
                mSubtitleStudio.show();
            } else if(id == R.id.action_precision) {
                if(player != null) AdvancedPlaybackTools.showTuning(this,player);
            } else if(id == R.id.action_diagnostics) {
                if(player != null) mAdvancedTools.showDiagnostics(this,player);
            } else if (id == R.id.action_focus_mode) {
                toggleFocusMode();
            } else if (id == R.id.action_go_to_time) {
                showGoToTimeDialog();
            } else if (id == R.id.action_ab_loop) {
                toggleAbLoop();
            } else if (id == R.id.action_bookmark) {
                addBookmark();
            } else if (id == R.id.action_bookmarks) {
                showBookmarksDialog();
            } else if (id == R.id.action_screenshot) {
                captureScreenshot();
            } else if (id == R.id.action_audio_boost) {
                showAudioBoostDialog();
            } else if (id == R.id.action_equalizer) {
                showEqualizerDialog();
            } else if (id == R.id.action_night_mode) {
                toggleNightMode();
            } else if (id == R.id.action_kids_lock) {
                setLocked(true, true);
            } else if (id == R.id.action_trim) {
                showTrimDialog();
            } else if (id == R.id.action_background_play) {
                toggleBackgroundPlay();
            } else if (id == R.id.action_orientation_lock) {
                toggleOrientationLock();
            } else if (id == R.id.action_decoder) {
                showDecoderDialog();
            } else if (id == R.id.action_info) {
                showInfoDialog();
            } else {
                return false;
            }
            return true;
        });
        // A labelled grid makes advanced controls discoverable without a long overflow list.
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        android.widget.LinearLayout content = new android.widget.LinearLayout(this);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        int dp = (int) (getResources().getDisplayMetrics().density * 16);
        content.setPadding(dp, dp, dp, dp);
        content.setBackgroundColor(getColor(R.color.nexus_black));
        TextView heading = new TextView(this);
        heading.setText(R.string.studio_title);
        heading.setTextSize(24);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setTextColor(getColor(R.color.nexus_text));
        content.addView(heading);
        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.studio_subtitle);
        subtitle.setTextColor(getColor(R.color.nexus_text_secondary));
        subtitle.setPadding(0, dp / 2, 0, dp);
        content.addView(subtitle);
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(3);
        for (int i = 0; i < menu.getMenu().size(); i++) {
            final android.view.MenuItem action = menu.getMenu().getItem(i);
            TextView button = new TextView(this);
            button.setText(action.getItemId() == R.id.action_focus_mode && mFocusMode
                    ? getString(R.string.study_mode_off) : action.getTitle());
            button.setTextColor(getColor(R.color.nexus_text));
            button.setTextSize(12);
            button.setGravity(android.view.Gravity.CENTER);
            button.setMaxLines(2);
            button.setPadding(dp / 2, dp / 2, dp / 2, dp / 2);
            button.setBackgroundResource(R.drawable.bg_studio_panel);
            int icon = toolIcon(action.getItemId());
            android.graphics.drawable.Drawable drawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, icon);
            if (drawable != null) {
                drawable = drawable.mutate(); drawable.setTint(getColor(R.color.nexus_accent));
                drawable.setBounds(0, 0, dp * 3 / 2, dp * 3 / 2);
                button.setCompoundDrawables(null, drawable, null, null);
                button.setCompoundDrawablePadding(dp / 2);
            }
            android.widget.GridLayout.LayoutParams params = new android.widget.GridLayout.LayoutParams();
            params.width = 0; params.height = dp * 5;
            params.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);
            params.setMargins(dp / 4, dp / 4, dp / 4, dp / 4);
            button.setLayoutParams(params);
            button.setOnClickListener(v -> {
                sheet.dismiss();
                menu.getMenu().performIdentifierAction(action.getItemId(), 0);
            });
            button.setFocusable(true);
            grid.addView(button);
        }
        content.addView(grid);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(content);
        sheet.setContentView(scroll);
        sheet.show();
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
    }

    private int toolIcon(int id) {
        if (id == R.id.action_focus_mode) return R.drawable.ic_play;
        if (id == R.id.action_go_to_time) return R.drawable.ic_history;
        if (id == R.id.action_ab_loop) return R.drawable.ic_ab_loop;
        if (id == R.id.action_bookmark || id == R.id.action_bookmarks) return R.drawable.ic_bookmark;
        if (id == R.id.action_screenshot) return R.drawable.ic_camera;
        if (id == R.id.action_audio_boost || id == R.id.action_background_play) return R.drawable.ic_audio;
        if (id == R.id.action_equalizer) return R.drawable.ic_equalizer;
        if (id == R.id.action_night_mode) return R.drawable.ic_night;
        if (id == R.id.action_kids_lock) return R.drawable.ic_lock;
        if (id == R.id.action_orientation_lock) return R.drawable.ic_screen_rotation;
        return R.drawable.ic_tune;
    }

    private void toggleFocusMode() {
        if (player == null) return;
        if (mFocusMode) { endFocusMode(); showControls(); return; }
        new AlertDialog.Builder(this).setTitle(R.string.study_mode).setMessage(R.string.study_mode_note)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    if (player == null) return;
                    mBeforeFocusSpeed = player.getPlaybackParameters().speed;
                    mBeforeFocusRepeat = mPrefs.getRepeatMode();
                    mFocusMode = true;
                    player.setPlaybackSpeed(0.8f);
                    mPrefs.setRepeatMode(REPEAT_ONE);
                    refreshTransportButtons(); showControls();
                    showSubtitleToast(getString(R.string.study_mode_on));
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void endFocusMode() {
        if (!mFocusMode) return;
        mFocusMode = false;
        if (player != null) player.setPlaybackSpeed(mBeforeFocusSpeed);
        mPrefs.setRepeatMode(mBeforeFocusRepeat);
        refreshTransportButtons();
    }

    /** Hardware keyboards and remotes use the same transport actions as touch controls. */
    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        boolean transport = keyCode == android.view.KeyEvent.KEYCODE_SPACE
                || keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!transport) return super.onKeyDown(keyCode, event);
        if (mLocked || player == null) return true;
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) player.seekBack();
        else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) player.seekForward();
        else if (event.getRepeatCount() == 0) togglePlayPause();
        showControls();
        return true;
    }

    // ---- Small helpers ----

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
