package com.cinemax.nexus.ui;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cinemax.nexus.R;
import com.cinemax.nexus.adapter.ContinueAdapter;
import com.cinemax.nexus.adapter.VideoAdapter;
import com.cinemax.nexus.data.MediaRepository;
import com.cinemax.nexus.data.ThumbnailLoader;
import com.cinemax.nexus.data.db.entity.PlaylistEntity;
import com.cinemax.nexus.data.model.VideoItem;
import com.cinemax.nexus.data.prefs.PrefsManager;
import com.cinemax.nexus.player.PlayerActivity;
import com.cinemax.nexus.util.FormatUtils;
import com.cinemax.nexus.util.PermissionUtils;
import com.cinemax.nexus.util.PrivateFolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_OPEN_VIDEO = 1102;
    private int mLoadGeneration;
    private VideoItem mHeroVideo;
    private int mHeroGeneration;
    private String mCollection = "all";
    private Set<String> mFavoriteKeys = Collections.emptySet();

    private static final int SPAN_LARGE = 2;
    private static final int SPAN_MEDIUM = 3;
    private static final int SPAN_COMPACT = 4;

    private PrefsManager mPrefs;
    private MediaRepository mRepository;
    private VideoAdapter mAdapter;
    private GridLayoutManager mGridLayoutManager;
    private ProgressBar mProgress;
    /** Container (a LinearLayout in the layout), not the message TextView inside it. */
    private View mEmptyState;
    private TextView mEmptyText;
    private View mErrorState;
    private RecyclerView mRecyclerView;

    /** "Continue watching" rail above the grid; hidden while nothing is resumable. */
    private View mContinueSection;
    private ContinueAdapter mContinueAdapter;

    private List<VideoItem> mAllVideos = new ArrayList<>();
    private MediaRepository.SortMode mCurrentSort = MediaRepository.SortMode.DATE_ADDED;
    private boolean mSortAscending = false;
    /** Last live search term, kept so a refresh does not silently drop the active filter. */
    private String mQuery = "";

    /** Contextual bar shown while the viewer is picking several videos at once. */
    private ActionMode mActionMode;

    /**
     * Keys already watched to the end, for the hide-watched filter. Loaded once, off the
     * main thread, and refreshed whenever the library itself reloads.
     */
    private Set<String> mWatchedKeys = Collections.emptySet();
    private boolean mHideWatched;

    /**
     * Counts started activities so "the app went to the background" can be told apart from
     * "the viewer opened a screen on top of this one" - which is what lets the app lock
     * re-arm when the phone is handed over, without re-prompting after every video.
     * The platform starts the new activity before it stops the covered one, so the counter
     * only ever reaches zero when the whole app really left the foreground.
     */
    private static int sStartedActivities;
    /** Starts true so the first launch of every process has to pass the PIN. */
    private static boolean sWentToBackground = true;
    /** Process-lifetime unlock, so a rotation or a return from the player does not re-prompt. */
    private static boolean sAppUnlocked;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_library);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.brand_short);
        }

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.library_root), (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        mPrefs = PrefsManager.getInstance(this);
        mRepository = MediaRepository.getInstance(this);
        mCurrentSort = MediaRepository.SortMode.valueOf(mPrefs.getLibrarySort());
        mSortAscending = mPrefs.isLibraryAscending();
        if (savedInstanceState != null) mQuery = savedInstanceState.getString("query", "");
        findViewById(R.id.hero_play).setOnClickListener(v -> { if (mHeroVideo != null) openPlayer(mHeroVideo); else openDocument(); });
        findViewById(R.id.quick_open).setOnClickListener(v -> openDocument());
        findViewById(R.id.quick_stream).setOnClickListener(v -> showPlayUrlDialog());
        findViewById(R.id.library_tools).setOnClickListener(v -> showLibraryTools());
        findViewById(R.id.library_view_toggle).setOnClickListener(v -> applyGridSpan(mPrefs.getGridSpan() == 1 ? 2 : 1));
        findViewById(R.id.quick_folders).setOnClickListener(v ->
                startActivity(new Intent(this, FoldersActivity.class)));
        findViewById(R.id.nav_music).setOnClickListener(v ->
                startActivity(new Intent(this, com.cinemax.nexus.music.MusicActivity.class)));
        mProgress = findViewById(R.id.progress);
        mEmptyState = findViewById(R.id.empty_state);
        mEmptyText = mEmptyState.findViewById(R.id.empty_text);
        mErrorState = findViewById(R.id.error_state);
        mRecyclerView = findViewById(R.id.recycler);

        mAdapter = new VideoAdapter(ThumbnailLoader.getInstance(this), true);
        mAdapter.setOnVideoClickListener(new VideoAdapter.OnVideoClickListener() {
            @Override
            public void onVideoClick(@NonNull VideoItem item, int position) {
                // In selection mode a tap is a tick, not a play - the whole point of the mode.
                if (mAdapter.isSelectionMode()) {
                    mAdapter.toggleSelection(item);
                } else {
                    openPlayer(item);
                }
            }

            @Override
            public boolean onVideoLongClick(@NonNull VideoItem item, int position) {
                if (mAdapter.isSelectionMode()) {
                    mAdapter.toggleSelection(item);
                } else {
                    showVideoActions(item);
                }
                return true;
            }
        });
        mAdapter.setOnSelectionChangedListener(this::onSelectionChanged);
        mGridLayoutManager = new GridLayoutManager(this, mPrefs.getGridSpan());
        mRecyclerView.setLayoutManager(mGridLayoutManager);
        mRecyclerView.setAdapter(mAdapter);
        mAdapter.setListMode(mPrefs.getGridSpan() == 1);
        if (savedInstanceState != null) mCollection = savedInstanceState.getString("collection", "all");
        bindCollections();
        findViewById(R.id.nav_library).setOnClickListener(v -> { mCollection = "all"; updateCollections(); applyFilterAndSort(null); });
        findViewById(R.id.nav_playlists).setOnClickListener(v -> startActivity(new Intent(this, PlaylistsActivity.class)));
        findViewById(R.id.nav_settings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        mContinueSection = findViewById(R.id.continue_section);
        mContinueAdapter = new ContinueAdapter(ThumbnailLoader.getInstance(this));
        mContinueAdapter.setOnVideoClickListener(this::openPlayer);
        RecyclerView continueRail = findViewById(R.id.continue_recycler);
        continueRail.setLayoutManager(new LinearLayoutManager(this,
                LinearLayoutManager.HORIZONTAL, false));
        // Nested scrolling would otherwise measure the wrap_content rail at the full
        // height of the vertical scroller.
        continueRail.setNestedScrollingEnabled(false);
        continueRail.setAdapter(mContinueAdapter);

        mErrorState.findViewById(R.id.retry_button)
                .setOnClickListener(v -> loadVideos());

        mEmptyState.findViewById(R.id.grant_button)
                .setOnClickListener(v -> PermissionUtils.requestMediaPermission(this));

        if (PermissionUtils.hasMediaPermission(this)) {
            PermissionUtils.requestNotificationPermission(this);
            loadVideos();
        } else {
            showPermissionPrompt();
        }

        mHideWatched = mPrefs.isHideWatchedEnabled();
        getApplication().registerActivityLifecycleCallbacks(mStartTracker);
        maybeShowAppLock();
    }

    /**
     * Fires once the app has really left the foreground, so the next launch of the library
     * has to pass the PIN again.
     */
    private final android.app.Application.ActivityLifecycleCallbacks mStartTracker =
            new android.app.Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityStarted(@NonNull android.app.Activity activity) {
                    sStartedActivities++;
                }

                @Override
                public void onActivityStopped(@NonNull android.app.Activity activity) {
                    if (--sStartedActivities <= 0) {
                        sStartedActivities = 0;
                        sWentToBackground = true;
                    }
                }

                @Override
                public void onActivityCreated(@NonNull android.app.Activity a,
                                              @Nullable Bundle b) { }

                @Override
                public void onActivityResumed(@NonNull android.app.Activity a) { }

                @Override
                public void onActivityPaused(@NonNull android.app.Activity a) { }

                @Override
                public void onActivitySaveInstanceState(@NonNull android.app.Activity a,
                                                        @NonNull Bundle b) { }

                @Override
                public void onActivityDestroyed(@NonNull android.app.Activity a) { }
            };

    /** True while the app-lock PIN box is on screen, so onResume cannot stack a second one. */
    private boolean mAppLockShowing;

    /** Locks the library behind the app PIN if one is set and the app was backgrounded. */
    private void maybeShowAppLock() {
        if (!PrivateFolder.hasAppPin(this) || mAppLockShowing) return;
        if (sWentToBackground) {
            // Cleared before prompting, so coming back from a video does not ask again.
            sWentToBackground = false;
            sAppUnlocked = false;
        }
        if (sAppUnlocked) return;
        mAppLockShowing = true;
        PrivateFolder.unlockApp(this, passed -> {
            mAppLockShowing = false;
            if (passed) {
                sAppUnlocked = true;
            } else {
                // Fail closed: a wrong PIN leaves nothing on screen to read.
                Toast.makeText(this, R.string.app_lock_wrong, Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    @Override
    protected void onDestroy() {
        getApplication().unregisterActivityLifecycleCallbacks(mStartTracker);
        super.onDestroy();
    }

    private void showPermissionPrompt() {
        mProgress.setVisibility(View.GONE);
        mEmptyState.setVisibility(View.VISIBLE);
        mEmptyText.setText(R.string.permission_needed);
        mErrorState.setVisibility(View.GONE);
        // Without this the user was stuck: the prompt was shown but never actionable.
        View grant = mEmptyState.findViewById(R.id.grant_button);
        if (grant != null) grant.setVisibility(View.VISIBLE);
    }

    private void loadVideos() {
        if (!PermissionUtils.hasMediaPermission(this)) { showPermissionPrompt(); return; }
        final int generation = ++mLoadGeneration;
        mProgress.setVisibility(View.VISIBLE);
        mEmptyState.setVisibility(View.GONE);
        mErrorState.setVisibility(View.GONE);
        mRepository.loadVideos(new MediaRepository.LoadCallback() {
            @Override
            public void onLoaded(@NonNull List<VideoItem> videos) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || generation != mLoadGeneration) return;
                    mProgress.setVisibility(View.GONE);
                    mAllVideos = videos;
                    applyFilterAndSort(null);
                    refreshContinueWatching();
                    refreshWatchedKeys();
                });
            }

            @Override
            public void onError(@NonNull Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || generation != mLoadGeneration) return;
                    mProgress.setVisibility(View.GONE);
                    mErrorState.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void applyFilterAndSort(@Nullable String query) {
        if (query != null) mQuery = query;
        query = mQuery;
        if (!PermissionUtils.hasMediaPermission(this)) { showPermissionPrompt(); return; }
        List<VideoItem> list = new ArrayList<>(mAllVideos);
        // Anything the viewer has moved into the private folder stays out of the grid.
        list = withoutPrivate(list);
        // Finished videos are dropped too while the filter is on. Before the watched set
        // has arrived nothing is hidden, so the grid never flashes empty on first load.
        if (mHideWatched && !mWatchedKeys.isEmpty()) {
            List<VideoItem> unwatched = new ArrayList<>(list.size());
            for (VideoItem v : list) {
                if (!mWatchedKeys.contains(v.key())) unwatched.add(v);
            }
            list = unwatched;
        }
        long totalBytes = 0, totalMs = 0;
        for (VideoItem video : list) { totalBytes += video.sizeBytes; totalMs += video.durationMs; }
        ((TextView) findViewById(R.id.library_metrics)).setText(getString(R.string.library_metrics,
                list.size(), FormatUtils.formatSize(totalBytes), FormatUtils.formatDuration(totalMs)));
        List<VideoItem> collection = new ArrayList<>();
        long weekAgo = System.currentTimeMillis() / 1000 - 7L * 24 * 60 * 60;
        for (VideoItem video : list) {
            boolean include = "all".equals(mCollection)
                    || ("favorites".equals(mCollection) && mFavoriteKeys.contains(video.key()))
                    || ("unwatched".equals(mCollection) && !mWatchedKeys.contains(video.key()))
                    || ("hd".equals(mCollection) && Math.min(video.width, video.height) >= 720)
                    || ("recent".equals(mCollection) && video.dateAddedSec >= weekAgo);
            if (include) collection.add(video);
        }
        list = collection;
        mRepository.sortBy(list, mCurrentSort, mSortAscending);
        if (query != null && !query.isEmpty()) {
            list = mRepository.search(list, query);
        }
        mAdapter.submit(list);
        ((TextView) findViewById(R.id.library_summary)).setText(
                getString(R.string.library_count, list.size()));
        mEmptyState.findViewById(R.id.grant_button).setVisibility(View.GONE);
        boolean empty = list.isEmpty();
        mEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty && PermissionUtils.hasMediaPermission(this)) {
            mEmptyText.setText(mQuery.isEmpty() ? ("all".equals(mCollection) ? R.string.no_videos_found : R.string.library_filter_empty) : R.string.no_search_results);
        }
    }

    /** Recomputes the watched set off the main thread, then re-applies the filter. */
    private void refreshWatchedKeys() {
        mRepository.loadWatchedKeys(mAllVideos, keys -> runOnUiThread(() -> {
            mWatchedKeys = keys;
            if (mHideWatched || "unwatched".equals(mCollection)) applyFilterAndSort(null);
        }));
    }

    private void bindCollections() {
        int[] ids = {R.id.filter_all, R.id.filter_favorites, R.id.filter_unwatched, R.id.filter_hd, R.id.filter_recent};
        String[] keys = {"all", "favorites", "unwatched", "hd", "recent"};
        for (int i = 0; i < ids.length; i++) {
            final String key = keys[i];
            findViewById(ids[i]).setOnClickListener(v -> {
                mCollection = key;
                updateCollections();
                refreshFavoriteKeys();
                refreshWatchedKeys();
                applyFilterAndSort(null);
            });
        }
        updateCollections();
    }

    private void updateCollections() {
        int[] ids = {R.id.filter_all, R.id.filter_favorites, R.id.filter_unwatched, R.id.filter_hd, R.id.filter_recent};
        String[] keys = {"all", "favorites", "unwatched", "hd", "recent"};
        for (int i = 0; i < ids.length; i++) {
            TextView chip = findViewById(ids[i]);
            boolean selected = keys[i].equals(mCollection);
            chip.setSelected(selected);
            chip.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                    selected ? R.color.nexus_black : R.color.nexus_text_secondary));
        }
    }

    private void refreshFavoriteKeys() {
        mRepository.loadFavoriteKeys(keys -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            mFavoriteKeys = keys;
            if ("favorites".equals(mCollection)) applyFilterAndSort(null);
        }));
    }

    /** Changes how many columns the grid uses and remembers the choice. */
    private void applyGridSpan(int span) {
        if (mGridLayoutManager == null || mPrefs.getGridSpan() == span) return;
        mPrefs.setGridSpan(span);
        mAdapter.setListMode(span == 1);
        mGridLayoutManager.setSpanCount(span);
        // Each card is measured from its column width now, so the whole list is rebuilt.
        mAdapter.notifyDataSetChanged();
    }

    /**
     * Drops every video the viewer has moved into the private folder.
     *
     * <p>This is the only gate there is: the files themselves are untouched on disk, so the
     * hiding is a courtesy of this app's lists and not encryption. That limitation is stated
     * in the UI rather than left for the viewer to discover.
     */
    @NonNull
    private List<VideoItem> withoutPrivate(@NonNull List<VideoItem> source) {
        return PrivateFolder.visibleOnly(this, source);
    }

    /**
     * Rebuilds the resume rail. Playback writes its progress while the player is in the
     * foreground, so this has to run every time we come back - the library otherwise
     * shows the position from before the video was opened.
     */
    private void refreshContinueWatching() {
        if (mContinueAdapter == null || mContinueSection == null) return;
        if (mAllVideos.isEmpty()) {
            mContinueSection.setVisibility(View.GONE);
            updateCinemaHero(null, false);
            return;
        }
        final List<VideoItem> snapshot = withoutPrivate(mAllVideos);
        final int requestedHero = ++mHeroGeneration;
        updateCinemaHero(snapshot.isEmpty() ? null : snapshot.get(0), false);
        mRepository.loadContinueWatching(snapshot, 10, entries -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (requestedHero != mHeroGeneration) return;
            boolean has = !entries.isEmpty();
            if (has) updateCinemaHero(entries.get(0).video, true);
            mContinueSection.setVisibility(has ? View.VISIBLE : View.GONE);
            mContinueAdapter.submit(entries);
        }));
    }

    /** Uses only the visible library; private videos never become home artwork. */
    private void updateCinemaHero(VideoItem video, boolean resume) {
        mHeroVideo = video;
        android.widget.ImageView image = findViewById(R.id.hero_art);
        image.setTag(video == null ? null : video.key());
        image.setImageDrawable(null);
        ((TextView) findViewById(R.id.hero_title)).setText(video == null ? getString(R.string.cinema_headline) : video.title);
        ((TextView) findViewById(R.id.hero_eyebrow)).setText(resume ? R.string.continue_watching : R.string.cinema_eyebrow);
        ((TextView) findViewById(R.id.hero_play)).setText(video == null ? R.string.open_video : R.string.play);
        if (video != null) ThumbnailLoader.getInstance(this).load(video, (uri, bitmap) -> {
            if (!isDestroyed() && video.key().equals(image.getTag()) && bitmap != null) image.setImageBitmap(bitmap);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        maybeShowAppLock();
        refreshContinueWatching();
        refreshFavoriteKeys();
        refreshWatchedKeys();
        // The private set is mutable from PrivateActivity, so a video taken back out of the
        // private folder must reappear the moment the viewer returns - without this the grid
        // kept hiding it until the next full data load (verified on device: the row only came
        // back after a process restart).
        applyFilterAndSort(mQuery);
    }

    /**
     * Second, later refresh.
     *
     * <p>{@link #onResume()} can still run while the player's final progress write is in
     * flight - the library's own media scan completes on a worker that may beat it - in
     * which case the row is built from the pre-playback state. By the time this window
     * has focus the player has already passed {@code onPause} and queued its write, so a
     * read on the state lane is now ordered behind it and the row is guaranteed to show
     * the position the viewer just left.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refreshContinueWatching();
    }

    private void openPlayer(@NonNull VideoItem item) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.setData(item.uri);
        intent.putExtra(PlayerActivity.EXTRA_TITLE, item.title);
        intent.putExtra(PlayerActivity.EXTRA_VIDEO_ID, item.key());
        intent.putExtra(PlayerActivity.EXTRA_DURATION, item.durationMs);
        if (item.folderName != null) {
            intent.putExtra(PlayerActivity.EXTRA_FOLDER, item.folderName);
        }
        startActivity(intent);
    }

    // ---- Long-press actions ----

    private static final int REQUEST_DELETE = 1101;

    private void showVideoActions(@NonNull VideoItem item) {
        boolean isPrivate = mPrefs.getPrivateIds().contains(item.key());
        String[] options = {
                getString(R.string.play),
                getString(R.string.add_to_playlist),
                getString(R.string.action_favorites),
                getString(R.string.share),
                getString(R.string.video_info),
                isPrivate ? getString(R.string.remove_from_private)
                        : getString(R.string.add_to_private),
                getString(R.string.delete_from_device)
        };
        new AlertDialog.Builder(this)
                .setTitle(item.title)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            openPlayer(item);
                            break;
                        case 1:
                            showAddToPlaylistDialog(
                                    Collections.singletonList(item));
                            break;
                        case 2:
                            toggleFavorite(Collections.singletonList(item));
                            break;
                        case 3:
                            shareVideo(item);
                            break;
                        case 4:
                            showVideoInfo(item);
                            break;
                        case 5:
                            togglePrivate(Collections.singletonList(item));
                            break;
                        case 6:
                            confirmDelete(Collections.singletonList(item));
                            break;
                        default:
                            break;
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void togglePrivate(@NonNull List<VideoItem> items) {
        if (items.isEmpty()) return;
        // A mixed batch is treated as "hide them all": that is what the tick mark implies.
        boolean makePrivate = !PrivateFolder.isPrivate(this, items.get(0).key());
        if (!makePrivate || PrivateFolder.hasPin(this)) {
            // Either the PIN already exists or the videos are coming back out; no gate to pass.
            applyPrivate(items, makePrivate);
            return;
        }
        // First use: the viewer has to choose the PIN before anything can be hidden behind it.
        PrivateFolder.createPin(this, passed -> {
            if (passed) applyPrivate(items, true);
        });
    }

    private void applyPrivate(@NonNull List<VideoItem> items, boolean makePrivate) {
        for (VideoItem item : items) {
            PrivateFolder.setPrivate(this, item.key(), makePrivate);
        }
        Toast.makeText(this, makePrivate
                        ? getString(R.string.added_to_private, items.get(0).title)
                        : getString(R.string.removed_from_private, items.get(0).title),
                Toast.LENGTH_SHORT).show();
        // The rows have to leave the grid the moment they are hidden, or the feature looks
        // broken; anything selected that just vanished is dropped from the selection too.
        exitSelectionMode();
        applyFilterAndSort(mQuery);
        refreshContinueWatching();
    }

    private void showAddToPlaylistDialog(@NonNull final List<VideoItem> items) {
        if (items.isEmpty()) return;
        final MediaRepository repo = MediaRepository.getInstance(this);
        LiveData<List<PlaylistEntity>> live = repo.playlistsLive();
        Observer<List<PlaylistEntity>> observer = new Observer<List<PlaylistEntity>>() {
            @Override
            public void onChanged(List<PlaylistEntity> playlists) {
                // One-shot: otherwise every DB change would pop another dialog.
                live.removeObserver(this);
                final List<PlaylistEntity> list =
                        playlists != null ? playlists : new ArrayList<PlaylistEntity>();
                String[] names = new String[list.size() + 1];
                for (int i = 0; i < list.size(); i++) {
                    names[i] = list.get(i).name;
                }
                names[list.size()] = getString(R.string.new_playlist);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.add_to_playlist)
                        .setItems(names, (dialog, which) -> {
                            if (which == list.size()) {
                                promptNewPlaylist(items);
                            } else {
                                for (VideoItem video : items) {
                                    repo.addToPlaylist(list.get(which).id, video);
                                }
                                Toast.makeText(MainActivity.this,
                                        getString(R.string.added_to_playlist_count,
                                                items.size(), list.get(which).name),
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        };
        live.observe(this, observer);
    }

    private void promptNewPlaylist(@NonNull final List<VideoItem> items) {
        final EditText input = new EditText(this);
        input.setHint(R.string.playlist_name_hint);
        input.setPadding(48, 24, 48, 12);
        new AlertDialog.Builder(this)
                .setTitle(R.string.new_playlist)
                .setView(input)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    MediaRepository.getInstance(this).createPlaylist(name, id -> {
                        for (VideoItem video : items) {
                            MediaRepository.getInstance(this).addToPlaylist(id, video);
                        }
                    });
                    Toast.makeText(this, getString(R.string.added_to_playlist_count,
                            items.size(), name), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void toggleFavorite(@NonNull final List<VideoItem> items) {
        if (items.isEmpty()) return;
        final MediaRepository repo = MediaRepository.getInstance(this);
        repo.isFavorite(items.get(0), favorite -> runOnUiThread(() -> {
            boolean makeFavorite = !favorite;
            for (VideoItem item : items) repo.setFavorite(item, makeFavorite);
            refreshFavoriteKeys();
            Toast.makeText(this, makeFavorite
                    ? R.string.added_favourite : R.string.removed_favourite,
                    Toast.LENGTH_SHORT).show();
        }));
    }

    private void shareVideo(@NonNull VideoItem item) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("video/*");
        share.putExtra(Intent.EXTRA_STREAM, item.uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, getString(R.string.share)));
    }

    private void showVideoInfo(@NonNull VideoItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append(item.title).append("\n\n");
        sb.append(getString(R.string.info_duration)).append(": ")
                .append(FormatUtils.formatDuration(item.durationMs)).append("\n");
        sb.append(getString(R.string.info_size)).append(": ")
                .append(FormatUtils.formatSize(item.sizeBytes)).append("\n");
        if (item.hasResolution()) {
            sb.append(getString(R.string.info_resolution)).append(": ")
                    .append(item.width).append(" \u00D7 ").append(item.height)
                    .append("  (").append(FormatUtils.formatResolution(
                            item.width, item.height)).append(")\n");
        }
        if (item.folderName != null) {
            sb.append(getString(R.string.info_path)).append(": ")
                    .append(item.folderName).append("\n");
        }
        sb.append(getString(R.string.info_mime)).append(": ")
                .append(item.mimeType != null ? item.mimeType : "\u2014");
        new AlertDialog.Builder(this)
                .setTitle(R.string.video_info)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void confirmDelete(@NonNull final List<VideoItem> items) {
        if (items.isEmpty()) return;
        String message = items.size() == 1
                ? getString(R.string.delete_confirm, items.get(0).title)
                : getString(R.string.delete_confirm_count, items.size());
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_from_device)
                .setMessage(message)
                .setPositiveButton(R.string.delete, (dialog, which) -> deleteVideo(items))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteVideo(@NonNull List<VideoItem> items) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Let the system own the deletion so it can confirm with the user. The batch
            // goes as one request, so a multi-select needs a single confirmation, not one
            // per video.
            try {
                ArrayList<android.net.Uri> uris = new ArrayList<>(items.size());
                for (VideoItem item : items) uris.add(item.uri);
                android.app.PendingIntent pi = android.provider.MediaStore
                        .createDeleteRequest(getContentResolver(), uris);
                startIntentSenderForResult(pi.getIntentSender(), REQUEST_DELETE,
                        null, 0, 0, 0);
                exitSelectionMode();
            } catch (Exception e) {
                Toast.makeText(this, R.string.delete_unavailable,
                        Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, R.string.delete_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Multi-select ----

    /**
     * Enters selection mode. Nothing is picked yet: the viewer taps the videos they want,
     * and every action then applies to the whole set at once.
     */
    private void startSelectionMode() {
        // The bar goes up first so the adapter's own "0 selected" callback has somewhere
        // to write, instead of trying to tear the mode down as it is being built.
        if (mActionMode == null) startSelectionActionMode();
        mAdapter.setSelectionMode(true);
    }

    private void exitSelectionMode() {
        if (mActionMode != null) {
            mActionMode.finish();          // calls back into onSelectionChanged(0)
        } else {
            mAdapter.setSelectionMode(false);
        }
    }

    private void onSelectionChanged(int count) {
        // Count 0 is left alone on purpose. Exiting here would re-enter the adapter from
        // inside its own callback, and un-ticking the last video is better read as "pick
        // something" than as "leave the mode" - the X in the bar does that.
        if (mActionMode != null) {
            mActionMode.setTitle(getString(R.string.selected_count, count));
        }
    }

    private void startSelectionActionMode() {
        mActionMode = startSupportActionMode(new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                mode.getMenuInflater().inflate(R.menu.menu_selection, menu);
                mode.setTitle(getString(R.string.selected_count,
                        mAdapter.getSelectedCount()));
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                return handleSelectionAction(item.getItemId());
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                mActionMode = null;
                mAdapter.setSelectionMode(false);
            }
        });
    }

    private boolean handleSelectionAction(int id) {
        if (id == R.id.action_select_all) { mAdapter.selectAll(); return true; }
        if (id == R.id.action_selection_close) { exitSelectionMode(); return true; }
        final List<VideoItem> picked = mAdapter.getSelectedItems();
        if (picked.isEmpty()) return true;
        if (id == R.id.action_select_all) {
            mAdapter.selectAll();
        } else if (id == R.id.action_selection_playlist) {
            exitSelectionMode();
            showAddToPlaylistDialog(picked);
        } else if (id == R.id.action_selection_favorite) {
            toggleFavorite(picked);
            exitSelectionMode();
        } else if (id == R.id.action_selection_share) {
            ArrayList<Uri> uris = new ArrayList<>();
            for (VideoItem video : picked) uris.add(video.uri);
            Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*");
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            android.content.ClipData clip = android.content.ClipData.newRawUri("Videos", uris.get(0));
            for (int i = 1; i < uris.size(); i++) clip.addItem(new android.content.ClipData.Item(uris.get(i)));
            share.setClipData(clip);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, getString(R.string.share)));
            exitSelectionMode();
        } else if (id == R.id.action_selection_private) {
            togglePrivate(picked);
        } else if (id == R.id.action_selection_delete) {
            confirmDelete(picked);
        } else if (id == R.id.action_selection_close) {
            exitSelectionMode();
        }
        return true;
    }

    // ---- Network stream ----

    /**
     * Pastes a URL straight into the player. The playback stack already handles http, https
     * and the HLS/DASH manifests, so this is a dialog and nothing more - no new dependency
     * and no second player.
     */
    private void showLibraryTools() {
        LinearLayout box=StudioSheet.column(this);
        StudioSheet.heading(box,getString(R.string.library_tools),getString(R.string.library_tools_note));
        StudioSheet.action(box,getString(R.string.view_list),v -> applyGridSpan(1));
        StudioSheet.action(box,getString(R.string.view_grid),v -> applyGridSpan(2));
        StudioSheet.action(box,getString(R.string.open_video),v -> openDocument());
        StudioSheet.action(box,getString(R.string.action_history),v -> startActivity(new Intent(this,HistoryActivity.class)));
        StudioSheet.action(box,getString(R.string.music),v -> startActivity(new Intent(this,com.cinemax.nexus.music.MusicActivity.class)));
        StudioSheet.action(box,getString(R.string.quick_stream),v -> showPlayUrlDialog());
        StudioSheet.action(box,getString(R.string.action_settings),v -> startActivity(new Intent(this,SettingsActivity.class)));
        StudioSheet.show(this,box);
    }

    private void showPlayUrlDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.url_hint);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setPadding(48, 24, 48, 12);

        LinearLayout requestBox=StudioSheet.column(this);
        requestBox.addView(input);
        EditText headers=new EditText(this); headers.setHint(R.string.network_headers_hint);
        headers.setMinLines(2);headers.setMaxLines(4);headers.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        requestBox.addView(StudioSheet.text(this,getString(R.string.network_headers),13));requestBox.addView(headers);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.play_url)
                .setMessage(R.string.network_request_note)
                .setView(requestBox)
                // Button handled in onShowListener, so a rejected URL keeps the dialog and
                // everything typed into it instead of throwing the input away.
                .setPositiveButton(R.string.play, null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String raw = input.getText().toString().trim();
                    if (raw.isEmpty() || raw.contains(" ")) {
                        input.setError(getString(R.string.invalid_url));
                        return;
                    }
                    String url = raw.contains("://") ? raw : "http://" + raw;
                    if (!isValidStreamUrl(url)) {
                        input.setError(getString(R.string.invalid_url));
                        return;
                    }
                    // Course 07.2: check connectivity before handing the URL to the player,
                    // so an offline device gets a clear inline message instead of a
                    // playback error after the dialog has already closed.
                    if (!isNetworkAvailable()) {
                        input.setError(getString(R.string.no_connection));
                        return;
                    }
                    com.cinemax.nexus.util.StreamRequest request;
                    try { request=new com.cinemax.nexus.util.StreamRequest(url,headers.getText().toString()); }
                    catch(IllegalArgumentException error) { headers.setError(error.getMessage()); return; }
                    dialog.dismiss();
                    Intent intent = new Intent(this, PlayerActivity.class);
                    Bundle requestHeaders=new Bundle();for(java.util.Map.Entry<String,String> entry:request.headers.entrySet())requestHeaders.putString(entry.getKey(),entry.getValue());
                    intent.putExtra("stream_headers",requestHeaders);
                    intent.setData(Uri.parse(url));
                    intent.putExtra(PlayerActivity.EXTRA_TITLE, url);
                    startActivity(intent);
                }));

        dialog.show();
    }

    /**
     * Validates a stream URL the way the playback stack will actually parse it.
     *
     * <p>{@link Uri} is deliberately lenient - it will cheerfully return a host for
     * {@code http://10.0.2.2:8765nexus_long.mp4o} - while ExoPlayer's HTTP source builds a
     * strict {@link java.net.URL} and throws {@code MalformedURLException} on the very same
     * string, which surfaced as a "Couldn't play this video" error after acceptance. Using
     * the strict parser here closes that gap instead of duplicating a weaker check.
     */
    private static boolean isValidStreamUrl(@NonNull String url) {
        try {
            java.net.URL parsed = new java.net.URL(url);
            String scheme = parsed.getProtocol();
            return ("http".equals(scheme) || "https".equals(scheme))
                    && parsed.getHost() != null && !parsed.getHost().isEmpty();
        } catch (java.net.MalformedURLException e) {
            return false;
        }
    }

    /**
     * Course 07.2: {@link ConnectivityManager}'s answer to "is the internet actually
     * reachable?". Uses the modern (API 23+) active-network API - the course's
     * {@code getActiveNetworkInfo()} is deprecated - and demands
     * {@link NetworkCapabilities#NET_CAPABILITY_VALIDATED}, not just INTERNET, so a link
     * that is up but has no working route (captive portal, emulator wifi off) reports as
     * offline here instead of failing later inside Media3.
     */
    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    // ---- App lock ----

    /**
     * Turns the app lock on, off, or forces it. Locking is deliberately an explicit
     * action: there is no background timer, and nothing on disk is encrypted.
     */
    private void showAppLockMenu() {
        if (!PrivateFolder.hasAppPin(this)) {
            PrivateFolder.createAppPin(this, passed -> {
                if (passed) {
                    sAppUnlocked = true;
                    Toast.makeText(this, R.string.app_lock_on,
                            Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }
        String[] options = {
                getString(R.string.lock_now),
                getString(R.string.change_pin),
                getString(R.string.turn_off)
        };
        // appcompat renders setMessage and setItems in MUTUALLY EXCLUSIVE layouts:
        // abc_alert_dialog_material has a message view but no list, and
        // abc_select_dialog_material has the list but no message view - so setting both
        // made this dialog show only the note and Cancel, silently dropping all three
        // options above (Lock now / Change PIN / Turn off were unreachable). Caught by
        // actually opening the menu on device. The note moved to the PIN-creation
        // dialog, which shows it alongside its text field - message + custom view do
        // share the material layout.
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_lock)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        // Drop the session unlock and re-arm; the next launch must pass.
                        sAppUnlocked = false;
                        sWentToBackground = true;
                        maybeShowAppLock();
                    } else if (which == 1) {
                        PrivateFolder.createAppPin(this, passed -> {
                            if (passed) Toast.makeText(this, R.string.app_lock_on,
                                    Toast.LENGTH_SHORT).show();
                        });
                    } else {
                        PrivateFolder.clearAppPin(this);
                        sAppUnlocked = true;
                        Toast.makeText(this, R.string.app_lock_off,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_library, menu);

        MenuItem searchItem = menu.findItem(R.id.action_search);
        SearchView searchView = (SearchView) searchItem.getActionView();
        searchView.setQueryHint(getString(R.string.search_hint));
        if (!mQuery.isEmpty()) {
            searchItem.expandActionView();
            searchView.setQuery(mQuery, false);
            searchView.clearFocus();
        }
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                mQuery = query;
                applyFilterAndSort(query);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                mQuery = newText;
                applyFilterAndSort(newText);
                return true;
            }
        });
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(@NonNull Menu menu) {
        // Ticks the density that is actually in force so the menu never lies about state.
        int span = mPrefs.getGridSpan();
        menu.findItem(R.id.action_view_list).setChecked(span == 1);
        menu.findItem(R.id.action_view_large).setChecked(span == SPAN_LARGE);
        menu.findItem(R.id.action_view_medium).setChecked(span == SPAN_MEDIUM);
        menu.findItem(R.id.action_view_compact).setChecked(span == SPAN_COMPACT);
        menu.findItem(R.id.action_hide_watched).setChecked(mHideWatched);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_open_video) {
            openDocument(); return true;
        } else if (id == R.id.action_refresh) {
            loadVideos(); return true;
        } else if (id == R.id.action_sort_title) {
            mCurrentSort = MediaRepository.SortMode.TITLE;
            mSortAscending = true;
        } else if (id == R.id.action_sort_date) {
            mCurrentSort = MediaRepository.SortMode.DATE_ADDED;
            mSortAscending = false;
        } else if (id == R.id.action_sort_duration) {
            mCurrentSort = MediaRepository.SortMode.DURATION;
            mSortAscending = false;
        } else if (id == R.id.action_sort_size) {
            mCurrentSort = MediaRepository.SortMode.SIZE;
            mSortAscending = false;
        } else if (id == R.id.action_toggle_order) {
            mSortAscending = !mSortAscending;
        } else if (id == R.id.action_view_list) {
            applyGridSpan(1); return true;
        } else if (id == R.id.action_view_large) {
            applyGridSpan(SPAN_LARGE);
            return true;
        } else if (id == R.id.action_view_medium) {
            applyGridSpan(SPAN_MEDIUM);
            return true;
        } else if (id == R.id.action_view_compact) {
            applyGridSpan(SPAN_COMPACT);
            return true;
        } else if (id == R.id.action_private) {
            startActivity(new Intent(this, PrivateActivity.class));
            return true;
        } else if (id == R.id.action_select) {
            startSelectionMode();
            return true;
        } else if (id == R.id.action_play_url) {
            showPlayUrlDialog();
            return true;
        } else if (id == R.id.action_hide_watched) {
            mHideWatched = !mHideWatched;
            mPrefs.setHideWatchedEnabled(mHideWatched);
            if (mHideWatched && mWatchedKeys.isEmpty()) refreshWatchedKeys();
            applyFilterAndSort(null);
            return true;
        } else if (id == R.id.action_app_lock) {
            showAppLockMenu();
            return true;
        } else if (id == R.id.action_folders) {
            startActivity(new Intent(this, FoldersActivity.class));
            return true;
        } else if (id == R.id.action_playlists) {
            startActivity(new Intent(this, PlaylistsActivity.class));
            return true;
        } else if (id == R.id.action_history) {
            startActivity(new Intent(this, HistoryActivity.class));
            return true;
        } else if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
        mPrefs.setLibrarySort(mCurrentSort.name(), mSortAscending);
        applyFilterAndSort(null);
        return true;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putString("query", mQuery);
        outState.putString("collection", mCollection);
        super.onSaveInstanceState(outState);
    }

    /** Storage Access Framework: grants access to one chosen file without a library grant. */
    private void openDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_OPEN_VIDEO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQUEST_DELETE) { loadVideos(); return; }
        if (requestCode != REQUEST_OPEN_VIDEO || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { /* Some providers offer only temporary access. */ }
        Intent play = new Intent(this, PlayerActivity.class).setData(uri);
        play.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        play.putExtra(PlayerActivity.EXTRA_TITLE, getString(R.string.open_video));
        startActivity(play);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PermissionUtils.REQUEST_MEDIA) {
            if (PermissionUtils.hasMediaPermission(this)) {
                View grant = mEmptyState.findViewById(R.id.grant_button);
                if (grant != null) grant.setVisibility(View.GONE);
                PermissionUtils.requestNotificationPermission(this);
                loadVideos();
            } else {
                showPermissionPrompt();
            }
        }
    }
}

