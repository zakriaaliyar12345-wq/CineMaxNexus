package com.cinemax.nexus.music;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.*;
import androidx.media3.common.*;
import androidx.media3.session.*;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.*;
import com.cinemax.nexus.R;
import com.cinemax.nexus.player.PlaybackService;
import com.cinemax.nexus.ui.StudioSheet;
import com.cinemax.nexus.util.FormatUtils;
import com.cinemax.nexus.util.PermissionUtils;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.*;
import java.util.concurrent.*;

/** Audio library UI. The controller connects to the service-owned music engine. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MusicActivity extends AppCompatActivity {
    private static final int REQUEST_AUDIO = 1901, REQUEST_FILE = 1902, REQUEST_LYRICS = 1903;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<AudioTrack> tracks = new ArrayList<>(), visibleTracks = new ArrayList<>();
    private final Map<String, AudioTrack> knownTracks = new HashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private ListenableFuture<MediaController> connection;
    private MediaController controller;
    private AudioArtwork artwork;
    private AudioAdapter adapter;
    private String mode = "songs", group, query = "", lastTrack = "";
    private boolean destroyed, seeking;
    private int generation;
    private List<MediaItem> pendingItems;
    private int pendingIndex;
    private BottomSheetDialog nowPlaying;
    private androidx.appcompat.app.AlertDialog queueDialog;
    private View playerPanel;
    private boolean lyricsVisible, restorePlayer;
    private String lyricsTrack = "", importingLyricsTrack;
    private List<TimedLyrics.Line> lyrics = Collections.emptyList();
    private int lyricsGeneration, activeLyric = -1;
    private LyricsAdapter lyricsAdapter;
    private int artworkColor = android.graphics.Color.rgb(43, 82, 93);
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final Runnable ticker = new Runnable() {
        @Override public void run() { refreshPlayer(); main.postDelayed(this, 500); }
    };
    private final Player.Listener listener = new Player.Listener() {
        @Override public void onEvents(Player player, Player.Events events) { refreshPlayer(); }
        @Override public void onPlayerError(PlaybackException error) {
            Toast.makeText(MusicActivity.this, R.string.music_play_error, Toast.LENGTH_LONG).show();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_music);
        if (getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            findViewById(R.id.music_headline).setVisibility(View.GONE);
            findViewById(R.id.music_status).setVisibility(View.GONE);
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.music_root), (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        artwork = new AudioArtwork(this);
        if (state != null) {
            lyricsVisible = state.getBoolean("lyricsVisible");
            importingLyricsTrack = state.getString("importingLyricsTrack");
            if (state.getBoolean("playerOpen")) getIntent().putExtra("open_now_playing", true);
            mode = state.getString("mode", "songs"); group = state.getString("group"); query = state.getString("query", "");
        }
        findViewById(R.id.music_back).setOnClickListener(v -> goBack());
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { goBack(); }
        });
        findViewById(R.id.music_open).setOnClickListener(v -> openFile());
        findViewById(R.id.music_grant).setOnClickListener(v -> requestAudio());
        findViewById(R.id.music_refresh).setOnClickListener(v -> loadLibrary());
        int[] ids = {R.id.music_songs, R.id.music_albums, R.id.music_artists, R.id.music_favorites};
        String[] modes = {"songs", "albums", "artists", "favorites"};
        for (int i = 0; i < ids.length; i++) {
            final String selected = modes[i];
            findViewById(ids[i]).setOnClickListener(v -> { mode = selected; group = null; renderLibrary(); });
        }
        EditText search = findViewById(R.id.music_search);
        search.setText(query);
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { query = s.toString(); renderLibrary(); }
            public void afterTextChanged(android.text.Editable text) {}
        });
        RecyclerView list = findViewById(R.id.music_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AudioAdapter(); list.setAdapter(adapter);
        View mini = findViewById(R.id.music_mini);
        mini.setOnClickListener(v -> showNowPlaying());
        mini.setOnTouchListener(new View.OnTouchListener() {
            float startY;
            public boolean onTouch(View view, android.view.MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { startY = event.getY(); return true; }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (startY - event.getY() > dp(24)) showNowPlaying(); else view.performClick();
                    return true;
                }
                return true;
            }
        });
        findViewById(R.id.music_mini_play).setOnClickListener(v -> togglePlay());
        findViewById(R.id.music_mini_next).setOnClickListener(v -> next());
        if (hasAudioPermission()) loadLibrary(); else showAccessState();
    }

    @Override protected void onStart() {
        super.onStart();
        final ListenableFuture<MediaController> requested = new MediaController.Builder(this,
                new SessionToken(this, new ComponentName(this, MusicPlaybackService.class))).buildAsync();
        connection = requested;
        requested.addListener(() -> {
            if (connection != requested || destroyed) return;
            try {
                controller = requested.get(); controller.addListener(listener);
                if (pendingItems != null) {
                    List<MediaItem> items = pendingItems; pendingItems = null; playItems(items, pendingIndex);
                }
                refreshPlayer();
                if ((restorePlayer || getIntent().getBooleanExtra("open_now_playing", false)) && controller.getMediaItemCount() > 0) {
                    restorePlayer = false;
                    getIntent().removeExtra("open_now_playing");
                    showNowPlaying();
                }
            } catch (Exception error) { Toast.makeText(this, R.string.music_connect_error, Toast.LENGTH_LONG).show(); }
        }, ContextCompat.getMainExecutor(this));
        main.post(ticker);
    }

    @Override protected void onStop() {
        main.removeCallbacks(ticker);
        restorePlayer = nowPlaying != null && nowPlaying.isShowing();
        if (nowPlaying != null) nowPlaying.dismiss();
        if (queueDialog != null) queueDialog.dismiss();
        if (controller != null) controller.removeListener(listener);
        controller = null;
        ListenableFuture<MediaController> previous = connection;
        connection = null;
        if (previous != null) MediaController.releaseFuture(previous);
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true; generation++; main.removeCallbacksAndMessages(null);
        worker.shutdownNow(); artwork.close(); super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("lyricsVisible", lyricsVisible);
        state.putString("importingLyricsTrack", importingLyricsTrack);
        state.putBoolean("playerOpen", restorePlayer || (nowPlaying != null && nowPlaying.isShowing()));
        state.putString("mode", mode); state.putString("group", group); state.putString("query", query);
        super.onSaveInstanceState(state);
    }

    private void goBack() { if (group != null) { group = null; renderLibrary(); } else finish(); }
    private String audioPermission() { return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE; }
    private boolean hasAudioPermission() { return ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED; }
    private void requestAudio() { requestPermissions(new String[]{audioPermission()}, REQUEST_AUDIO); }
    @Override public void onRequestPermissionsResult(int code, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQUEST_AUDIO) { if (hasAudioPermission()) loadLibrary(); else showAccessState(); }
    }

    private void showAccessState() {
        findViewById(R.id.music_status).setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.music_status)).setText(R.string.music_permission_note);
        findViewById(R.id.music_grant).setVisibility(View.VISIBLE);
        tracks.clear(); renderLibrary();
    }

    private void loadLibrary() {
        if (!hasAudioPermission()) { showAccessState(); return; }
        int requested = ++generation;
        findViewById(R.id.music_progress).setVisibility(View.VISIBLE);
        findViewById(R.id.music_grant).setVisibility(View.GONE);
        worker.execute(() -> {
            try {
                List<AudioTrack> result = AudioLibrary.query(getApplicationContext());
                runOnUiThread(() -> {
                    if (destroyed || requested != generation) return;
                    tracks.clear(); tracks.addAll(result);
                    for (AudioTrack track : result) knownTracks.put(track.id, track);
                    // A controller can reconnect before MediaStore finishes loading. Retry
                    // artwork after the track-to-URI mapping becomes available.
                    findViewById(R.id.music_mini_art).setTag(null);
                    if (playerPanel != null) playerPanel.findViewById(R.id.music_cover).setTag(null);
                    findViewById(R.id.music_progress).setVisibility(View.GONE);
                    ((TextView) findViewById(R.id.music_status)).setText(getString(R.string.music_track_count, result.size()));
                    renderLibrary();
                    refreshPlayer();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (destroyed || requested != generation) return;
                    findViewById(R.id.music_progress).setVisibility(View.GONE);
                    findViewById(R.id.music_status).setVisibility(View.VISIBLE);
                    ((TextView) findViewById(R.id.music_status)).setText(R.string.music_load_error);
                });
            }
        });
    }

    private Set<String> favorites() { return new HashSet<>(getSharedPreferences("music", 0).getStringSet("favorites", Collections.emptySet())); }
    private void renderLibrary() {
        if (adapter == null) return;
        rows.clear(); visibleTracks.clear(); Set<String> saved = favorites();
        String needle = query.toLowerCase(Locale.ROOT).trim();
        Map<String, List<AudioTrack>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (AudioTrack track : tracks) {
            if (!(track.title + " " + track.artist + " " + track.album).toLowerCase(Locale.ROOT).contains(needle)) continue;
            if ("favorites".equals(mode) && !saved.contains(track.id)) continue;
            String category = "artists".equals(mode) ? track.artist : track.album;
            if (group != null && !group.equalsIgnoreCase(category)) continue;
            visibleTracks.add(track);
            if (group == null && ("albums".equals(mode) || "artists".equals(mode))) {
                if (!groups.containsKey(category)) groups.put(category, new ArrayList<>());
                groups.get(category).add(track);
            } else rows.add(new Row(track.title, track.artist + " Â· " + FormatUtils.formatDuration(track.durationMs), track, false));
        }
        for (Map.Entry<String, List<AudioTrack>> entry : groups.entrySet()) {
            rows.add(new Row(entry.getKey(), getString(R.string.music_track_count, entry.getValue().size()), entry.getValue().get(0), true));
        }
        int[] ids = {R.id.music_songs, R.id.music_albums, R.id.music_artists, R.id.music_favorites};
        String[] modes = {"songs", "albums", "artists", "favorites"};
        for (int i = 0; i < ids.length; i++) findViewById(ids[i]).setSelected(mode.equals(modes[i]));
        ((TextView) findViewById(R.id.music_collection)).setText(group != null ? group : getString(R.string.music_collection_title));
        ((TextView) findViewById(R.id.music_empty)).setText(query.isEmpty() ? R.string.music_empty : R.string.music_no_matches);
        findViewById(R.id.music_empty).setVisibility(rows.isEmpty() && hasAudioPermission() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void playTrack(AudioTrack track) {
        List<MediaItem> items = new ArrayList<>(); int selected = 0;
        for (AudioTrack item : visibleTracks) { if (item.id.equals(track.id)) selected = items.size(); items.add(item.mediaItem()); }
        if (items.isEmpty()) items.add(track.mediaItem());
        playItems(items, selected);
    }

    private void playItems(List<MediaItem> items, int index) {
        if (controller == null) { pendingItems = items; pendingIndex = index; return; }
        if (PlaybackService.getSession() != null) PlaybackService.getSession().getPlayer().pause();
        PermissionUtils.requestNotificationPermission(this);
        controller.setMediaItems(items, index, 0); controller.prepare(); controller.play(); refreshPlayer();
    }
    private void togglePlay() { if (controller != null) { if (controller.isPlaying()) controller.pause(); else { controller.prepare(); controller.play(); } } }
    private void next() { if (controller != null && controller.hasNextMediaItem()) controller.seekToNextMediaItem(); }

    private void refreshPlayer() {
        if (controller == null || destroyed) return;
        MediaItem item = controller.getCurrentMediaItem();
        findViewById(R.id.music_mini).setVisibility(item == null ? View.GONE : View.VISIBLE);
        if (item == null) return;
        MediaMetadata metadata = item.mediaMetadata;
        ((TextView) findViewById(R.id.music_mini_title)).setText(metadata.title);
        ((TextView) findViewById(R.id.music_mini_artist)).setText(metadata.artist);
        ((ImageButton) findViewById(R.id.music_mini_play)).setImageResource(controller.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        findViewById(R.id.music_mini_play).setContentDescription(getString(controller.isPlaying() ? R.string.pause : R.string.play));
        findViewById(R.id.music_mini_next).setEnabled(controller.hasNextMediaItem());
        if (!item.mediaId.equals(lastTrack)) { lastTrack = item.mediaId; adapter.notifyDataSetChanged(); }
        art((ImageView) findViewById(R.id.music_mini_art), item.mediaId);
        if (!item.mediaId.equals(lyricsTrack)) loadLyrics(item.mediaId);
        if (playerPanel != null) {
            refreshLyrics();
            ((TextView) playerPanel.findViewById(R.id.music_track_title)).setText(metadata.title);
            ((TextView) playerPanel.findViewById(R.id.music_track_artist)).setText(metadata.artist);
            ((TextView) playerPanel.findViewById(R.id.music_track_album)).setText(metadata.albumTitle);
            ((TextView) playerPanel.findViewById(R.id.music_position)).setText(FormatUtils.formatDuration(controller.getCurrentPosition()));
            long duration = Math.max(0, controller.getDuration());
            ((TextView) playerPanel.findViewById(R.id.music_duration)).setText(FormatUtils.formatDuration(duration));
            if (!seeking) ((SeekBar) playerPanel.findViewById(R.id.music_seek)).setProgress(duration > 0 ? (int) (controller.getCurrentPosition() * 1000 / duration) : 0);
            ((ImageButton) playerPanel.findViewById(R.id.music_play)).setImageResource(controller.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
            playerPanel.findViewById(R.id.music_play).setContentDescription(getString(controller.isPlaying() ? R.string.pause : R.string.play));
            playerPanel.findViewById(R.id.music_previous).setEnabled(controller.hasPreviousMediaItem());
            playerPanel.findViewById(R.id.music_next).setEnabled(controller.hasNextMediaItem());
            tint(playerPanel.findViewById(R.id.music_shuffle), controller.getShuffleModeEnabled());
            tint(playerPanel.findViewById(R.id.music_favorite), favorites().contains(item.mediaId));
            int repeat = controller.getRepeatMode();
            ((ImageButton) playerPanel.findViewById(R.id.music_repeat)).setImageResource(repeat == Player.REPEAT_MODE_ONE ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
            tint(playerPanel.findViewById(R.id.music_repeat), repeat != Player.REPEAT_MODE_OFF);
            playerPanel.findViewById(R.id.music_repeat).setContentDescription(getString(repeat == Player.REPEAT_MODE_ONE ? R.string.music_repeat_one : repeat == Player.REPEAT_MODE_ALL ? R.string.music_repeat_all : R.string.music_repeat_off));
            art((ImageView) playerPanel.findViewById(R.id.music_cover), item.mediaId);
        }
    }

    private void tint(View view, boolean enabled) { ((ImageButton) view).setColorFilter(getColor(enabled ? R.color.nexus_accent : R.color.nexus_text_secondary)); }
    private void art(ImageView view, String id) {
        if (id.equals(view.getTag())) return;
        view.setTag(id); view.setImageResource(R.drawable.ic_album_orbit);
        if (view.getId() == R.id.music_cover) {
            artworkColor = android.graphics.Color.HSVToColor(new float[]{Math.floorMod(id.hashCode(), 360), .45f, .38f});
            applyAmbience();
        }
        AudioTrack track = knownTracks.get(id);
        if (track != null) artwork.load(track.uri, bitmap -> {
            if (bitmap == null || !id.equals(view.getTag())) return;
            view.setImageBitmap(bitmap);
            if (view.getId() == R.id.music_cover && playerPanel != null && playerPanel.findViewById(R.id.music_cover) == view) {
                // A small fixed sample avoids scanning a full image on the UI thread.
                long red = 0, green = 0, blue = 0;
                for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                    int color = bitmap.getPixel(x * bitmap.getWidth() / 8, y * bitmap.getHeight() / 8);
                    red += android.graphics.Color.red(color); green += android.graphics.Color.green(color); blue += android.graphics.Color.blue(color);
                }
                artworkColor = android.graphics.Color.rgb((int)(red / 64), (int)(green / 64), (int)(blue / 64));
                applyAmbience();
            }
        });
    }

    private void showNowPlaying() {
        if (controller == null || controller.getCurrentMediaItem() == null) return;
        if (nowPlaying != null && nowPlaying.isShowing()) return;
        nowPlaying = new BottomSheetDialog(this);
        playerPanel = getLayoutInflater().inflate(R.layout.sheet_music_player, null);
        nowPlaying.setContentView(playerPanel);
        configureImmersiveLayout();
        RecyclerView lyricList = playerPanel.findViewById(R.id.music_lyrics_list);
        lyricList.setLayoutManager(new LinearLayoutManager(this));
        lyricsAdapter = new LyricsAdapter(); lyricList.setAdapter(lyricsAdapter);
        playerPanel.findViewById(R.id.music_lyrics_toggle).setOnClickListener(v -> { lyricsVisible = !lyricsVisible; updateLyricsVisibility(); });
        playerPanel.findViewById(R.id.music_lyrics_import).setOnClickListener(v -> importLyrics());
        playerPanel.findViewById(R.id.music_ambience).setOnClickListener(v -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.music_ambience)
                .setSingleChoiceItems(new String[]{getString(R.string.ambience_artwork), getString(R.string.ambience_midnight), getString(R.string.ambience_amoled)},
                    getSharedPreferences("music", 0).getInt("ambience", 0), (dialog, index) -> {
                        getSharedPreferences("music", 0).edit().putInt("ambience", index).apply(); applyAmbience(); dialog.dismiss();
                    }).show());
        updateLyricsVisibility(); applyAmbience();
        playerPanel.findViewById(R.id.music_close_player).setOnClickListener(v -> nowPlaying.dismiss());
        playerPanel.findViewById(R.id.music_play).setOnClickListener(v -> togglePlay());
        playerPanel.findViewById(R.id.music_next).setOnClickListener(v -> next());
        playerPanel.findViewById(R.id.music_previous).setOnClickListener(v -> { if (controller != null && controller.hasPreviousMediaItem()) controller.seekToPreviousMediaItem(); });
        playerPanel.findViewById(R.id.music_shuffle).setOnClickListener(v -> { if (controller != null) controller.setShuffleModeEnabled(!controller.getShuffleModeEnabled()); });
        playerPanel.findViewById(R.id.music_repeat).setOnClickListener(v -> { if (controller != null) controller.setRepeatMode((controller.getRepeatMode() + 1) % 3); });
        playerPanel.findViewById(R.id.music_favorite).setOnClickListener(v -> {
            if (controller == null || controller.getCurrentMediaItem() == null) return;
            Set<String> values = favorites(); String id = controller.getCurrentMediaItem().mediaId;
            if (!values.remove(id)) values.add(id);
            getSharedPreferences("music", 0).edit().putStringSet("favorites", values).apply(); renderLibrary(); refreshPlayer();
        });
        playerPanel.findViewById(R.id.music_queue).setOnClickListener(v -> showQueue());
        ((SeekBar) playerPanel.findViewById(R.id.music_seek)).setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) {}
            public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            public void onStopTrackingTouch(SeekBar bar) {
                seeking = false; if (controller != null && controller.getDuration() > 0) controller.seekTo(controller.getDuration() * bar.getProgress() / 1000); refreshPlayer();
            }
        });
        nowPlaying.setOnDismissListener(dialog -> { playerPanel = null; lyricsAdapter = null; seeking = false; });
        nowPlaying.getBehavior().setMaxWidth(getResources().getDisplayMetrics().widthPixels);
        nowPlaying.show();
        View sheet = nowPlaying.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) {
            sheet.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
            sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT); sheet.requestLayout();
        }
        nowPlaying.getBehavior().setSkipCollapsed(true);
        nowPlaying.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        refreshPlayer();
    }

    private void configureImmersiveLayout() {
        boolean landscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout body = playerPanel.findViewById(R.id.music_body);
        View artSpace = playerPanel.findViewById(R.id.music_art_space);
        View controls = playerPanel.findViewById(R.id.music_controls_column);
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        if (landscape) {
            body.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(Math.min(width / 3, dp(260)), Math.min(height - dp(120), dp(260)));
            artParams.setMarginEnd(dp(24)); artSpace.setLayoutParams(artParams);
            controls.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            ((TextView)playerPanel.findViewById(R.id.music_track_title)).setTextSize(20);
        } else {
            int side = Math.max(dp(140), Math.min(width - dp(64), (int)(height * .34f)));
            LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(side, side);
            artParams.gravity = Gravity.CENTER_HORIZONTAL; artParams.topMargin = dp(20); artParams.bottomMargin = dp(24);
            artSpace.setLayoutParams(artParams);
        }
    }

    private void applyAmbience() {
        if (playerPanel == null) return;
        int mode = getSharedPreferences("music", 0).getInt("ambience", 0);
        int top = mode == 0 ? androidx.core.graphics.ColorUtils.blendARGB(artworkColor, 0xff080f19, .55f) : mode == 1 ? 0xff253651 : 0xff000000;
        int bottom = mode == 2 ? 0xff000000 : 0xff080f19;
        playerPanel.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{top, bottom}));
    }

    private java.io.File lyricsFile(String id) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder(); for (byte b : hash) key.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        java.io.File folder = new java.io.File(getFilesDir(), "lyrics");
        if (!folder.exists() && !folder.mkdirs()) throw new java.io.IOException("lyrics directory");
        return new java.io.File(folder, key + ".lrc");
    }

    private static String readLyrics(java.io.InputStream input) throws java.io.IOException {
        if (input == null) throw new java.io.IOException("missing input");
        try (java.io.InputStream stream = input; java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) != -1) {
                if (bytes.size() + count > 1024 * 1024) throw new java.io.IOException("lyrics too large");
                bytes.write(buffer, 0, count);
            }
            return bytes.toString("UTF-8");
        }
    }

    private void loadLyrics(String id) {
        lyricsTrack = id; lyrics = Collections.emptyList(); activeLyric = -1;
        if (lyricsAdapter != null) lyricsAdapter.notifyDataSetChanged();
        updateLyricsVisibility();
        int requested = ++lyricsGeneration;
        worker.execute(() -> {
            List<TimedLyrics.Line> found = Collections.emptyList();
            try { java.io.File file = lyricsFile(id); if (file.exists()) found = TimedLyrics.parse(readLyrics(new java.io.FileInputStream(file))); } catch (Exception ignored) {}
            final List<TimedLyrics.Line> result = found;
            main.post(() -> {
                if (destroyed || requested != lyricsGeneration) return;
                lyrics = result; activeLyric = -1;
                if (lyricsAdapter != null) lyricsAdapter.notifyDataSetChanged();
                updateLyricsVisibility(); refreshLyrics();
            });
        });
    }

    private void importLyrics() {
        if (controller == null || controller.getCurrentMediaItem() == null) return;
        importingLyricsTrack = controller.getCurrentMediaItem().mediaId;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), REQUEST_LYRICS);
    }

    private void saveImportedLyrics(Uri uri, String id) {
        worker.execute(() -> {
            boolean success = false;
            try {
                String source = readLyrics(getContentResolver().openInputStream(uri));
                if (TimedLyrics.parse(source).isEmpty()) throw new java.io.IOException("no timed lines");
                android.util.AtomicFile destination = new android.util.AtomicFile(lyricsFile(id));
                java.io.FileOutputStream output = null;
                try { output = destination.startWrite(); output.write(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)); destination.finishWrite(output); }
                catch (Exception error) { if (output != null) destination.failWrite(output); throw error; }
                success = true;
            } catch (Exception ignored) {}
            final boolean saved = success;
            main.post(() -> {
                if (destroyed) return;
                Toast.makeText(this, saved ? R.string.lyrics_loaded : R.string.lyrics_error, Toast.LENGTH_LONG).show();
                if (saved && controller != null && controller.getCurrentMediaItem() != null && id.equals(controller.getCurrentMediaItem().mediaId)) {
                    lyricsVisible = true; loadLyrics(id);
                }
            });
        });
    }

    private void updateLyricsVisibility() {
        if (playerPanel == null) return;
        playerPanel.findViewById(R.id.music_cover).setVisibility(lyricsVisible ? View.GONE : View.VISIBLE);
        playerPanel.findViewById(R.id.music_lyrics_list).setVisibility(lyricsVisible && !lyrics.isEmpty() ? View.VISIBLE : View.GONE);
        playerPanel.findViewById(R.id.music_lyrics_empty).setVisibility(lyricsVisible && lyrics.isEmpty() ? View.VISIBLE : View.GONE);
        playerPanel.findViewById(R.id.music_lyrics_import).setVisibility(lyricsVisible ? View.VISIBLE : View.GONE);
        ((TextView)playerPanel.findViewById(R.id.music_lyrics_toggle)).setText(lyricsVisible ? R.string.music_artwork : R.string.lyrics);
        if (lyricsVisible) { activeLyric = -1; refreshLyrics(); }
    }

    private void refreshLyrics() {
        if (controller == null || playerPanel == null || lyricsAdapter == null || !lyricsVisible) return;
        int active = TimedLyrics.activeLine(lyrics, controller.getCurrentPosition());
        if (active == activeLyric) return;
        int previous = activeLyric; activeLyric = active;
        if (previous >= 0) lyricsAdapter.notifyItemChanged(previous);
        if (active >= 0) {
            lyricsAdapter.notifyItemChanged(active);
            RecyclerView list = playerPanel.findViewById(R.id.music_lyrics_list);
            ((LinearLayoutManager)list.getLayoutManager()).scrollToPositionWithOffset(active, list.getHeight() / 3);
        }
    }

    private final class LyricsAdapter extends RecyclerView.Adapter<LyricsAdapter.Holder> {
        @NonNull public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            TextView text = new TextView(parent.getContext());
            text.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            text.setPadding(dp(12), dp(12), dp(12), dp(12)); text.setMinHeight(dp(48)); text.setTextSize(22);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            text.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG); text.setGravity(Gravity.START);
            text.setFocusable(true); return new Holder(text);
        }
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            TimedLyrics.Line line = lyrics.get(position);
            holder.text.setText(line.text); holder.text.setTextColor(getColor(position == activeLyric ? R.color.nexus_accent : R.color.nexus_text_secondary));
            holder.text.setSelected(position == activeLyric);
            holder.text.setOnClickListener(v -> { if (controller != null) { controller.seekTo(line.timeMs); refreshLyrics(); } });
        }
        public int getItemCount() { return lyrics.size(); }
        final class Holder extends RecyclerView.ViewHolder { final TextView text; Holder(TextView text) { super(text); this.text = text; } }
    }

    private void showQueue() {
        if (controller == null) return;
        String[] titles = new String[controller.getMediaItemCount()];
        for (int i = 0; i < titles.length; i++) titles[i] = String.valueOf(controller.getMediaItemAt(i).mediaMetadata.title);
        queueDialog = new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.queue).setSingleChoiceItems(titles,
                controller.getCurrentMediaItemIndex(), (dialog, index) -> { if (controller != null) controller.seekTo(index, 0); dialog.dismiss(); })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void openFile() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*")
                .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), REQUEST_FILE);
    }
    @Override protected void onActivityResult(int code, int result, @Nullable Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQUEST_LYRICS) {
            getIntent().putExtra("open_now_playing", true);
            if (result == RESULT_OK && data != null && data.getData() != null && importingLyricsTrack != null) saveImportedLyrics(data.getData(), importingLyricsTrack);
            importingLyricsTrack = null;
            if (controller != null) { getIntent().removeExtra("open_now_playing"); showNowPlaying(); }
            return;
        }
        if (code != REQUEST_FILE || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) {}
        worker.execute(() -> {
            String title = getString(R.string.untitled_audio);
            try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) title = cursor.getString(0);
            } catch (Exception ignored) { /* The URI may still be playable without a title query. */ }
            final AudioTrack track = new AudioTrack("document:" + uri, uri, title,
                    getString(R.string.unknown_artist), getString(R.string.open_audio), 0, null);
            runOnUiThread(() -> { if (!destroyed) { knownTracks.put(track.id, track); playItems(Collections.singletonList(track.mediaItem()), 0); } });
        });
    }

    private static final class Row {
        final String title, detail; final AudioTrack track; final boolean group;
        Row(String title, String detail, AudioTrack track, boolean group) { this.title = title; this.detail = detail; this.track = track; this.group = group; }
    }
    private final class AudioAdapter extends RecyclerView.Adapter<AudioAdapter.Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) { return new Holder(getLayoutInflater().inflate(R.layout.item_audio, parent, false)); }
        @Override public void onBindViewHolder(@NonNull Holder holder, int index) {
            Row row = rows.get(index); holder.title.setText(row.title); holder.detail.setText(row.detail);
            holder.title.setTextColor(getColor(!row.group && row.track.id.equals(lastTrack) ? R.color.nexus_accent : R.color.nexus_text));
            holder.image.setTag(null); art(holder.image, row.track.id);
            holder.itemView.setOnClickListener(v -> { if (row.group) { group = row.title; renderLibrary(); } else playTrack(row.track); });
        }
        @Override public int getItemCount() { return rows.size(); }
        final class Holder extends RecyclerView.ViewHolder {
            final TextView title, detail; final ImageView image;
            Holder(View view) { super(view); title = view.findViewById(R.id.audio_title); detail = view.findViewById(R.id.audio_detail); image = view.findViewById(R.id.audio_art); }
        }
    }
}
