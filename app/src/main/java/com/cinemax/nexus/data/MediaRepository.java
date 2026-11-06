package com.cinemax.nexus.data;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.cinemax.nexus.data.db.AppDatabase;
import com.cinemax.nexus.data.db.entity.BookmarkEntity;
import com.cinemax.nexus.data.db.entity.HistoryEntity;
import com.cinemax.nexus.data.db.entity.PlaylistEntity;
import com.cinemax.nexus.data.db.entity.PlaylistVideoEntity;
import com.cinemax.nexus.data.db.entity.VideoStateEntity;
import com.cinemax.nexus.data.model.VideoItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single entry point for media data. Combines live {@link MediaStore} discovery with
 * persistent Room state (history, favorites, playback position, playlists).
 */
public class MediaRepository {

    public enum SortMode { DATE_ADDED, TITLE, DURATION, SIZE }

    public enum FolderSortMode { NAME, VIDEO_COUNT, DATE }

    public interface LoadCallback {
        void onLoaded(@NonNull List<VideoItem> videos);
        void onError(@NonNull Exception e);
    }

    public interface FavoriteCallback {
        void onResult(boolean favorite);
    }

    private static volatile MediaRepository sInstance;

    private final Context mAppContext;
    private final MediaStoreSource mSource;
    private final AppDatabase mDb;
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(2);

    /**
     * Single-file lane for playback state.
     *
     * <p>The player enqueues its progress write in {@code onPause}, and the library enqueues
     * the "continue watching" read in its own {@code onResume} a few milliseconds later.
     * Both used to share the two-thread pool above, so the read could be picked up by the
     * second thread while the write was still queued on the first - the row came back empty
     * right after a video was closed, and only filled in on the next cold start. Routing
     * both through one FIFO lane makes "write happens-before read" structural instead of
     * accidental.
     */
    private final ExecutorService mStateExecutor = Executors.newSingleThreadExecutor();

    public static MediaRepository getInstance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (MediaRepository.class) {
                if (sInstance == null) {
                    sInstance = new MediaRepository(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private MediaRepository(@NonNull Context appContext) {
        this.mAppContext = appContext;
        this.mSource = new MediaStoreSource(appContext);
        this.mDb = AppDatabase.getInstance(appContext);
    }

    public void loadVideos(@NonNull LoadCallback callback) {
        mSource.loadAll(new MediaStoreSource.Callback() {
            @Override
            public void onVideosLoaded(@NonNull List<VideoItem> videos) {
                mExecutor.execute(() -> {
                    List<VideoItem> filtered = pruneMissing(videos);
                    callback.onLoaded(filtered);
                });
            }

            @Override
            public void onError(@NonNull Exception e) {
                callback.onError(e);
            }
        });
    }

    /** Drops entries whose persisted history row no longer has a reachable URI. */
    @NonNull
    private List<VideoItem> pruneMissing(@NonNull List<VideoItem> videos) {
        // MediaStore already reflects only reachable files, so pass through.
        return videos;
    }

    public void sortBy(@NonNull List<VideoItem> videos, @NonNull SortMode mode, boolean ascending) {
        Comparator<VideoItem> cmp;
        switch (mode) {
            case TITLE:
                cmp = Comparator.comparing(v -> v.title.toLowerCase(java.util.Locale.ROOT));
                break;
            case DURATION:
                cmp = Comparator.comparingLong(v -> v.durationMs);
                break;
            case SIZE:
                cmp = Comparator.comparingLong(v -> v.sizeBytes);
                break;
            case DATE_ADDED:
            default:
                cmp = Comparator.comparingLong(v -> v.dateAddedSec);
                break;
        }
        if (!ascending) cmp = cmp.reversed();
        Collections.sort(videos, cmp);
    }

    @NonNull
    public List<VideoItem> search(@NonNull List<VideoItem> videos, @NonNull String query) {
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) return videos;
        List<VideoItem> out = new ArrayList<>();
        for (VideoItem v : videos) {
            if (v.title.toLowerCase(java.util.Locale.ROOT).contains(q)
                    || (v.folderName != null && v.folderName.toLowerCase(java.util.Locale.ROOT).contains(q))) {
                out.add(v);
            }
        }
        return out;
    }

    @NonNull
    public List<VideoItem> filterByFolder(@NonNull List<VideoItem> videos, @NonNull String folderName) {
        List<VideoItem> out = new ArrayList<>();
        for (VideoItem v : videos) {
            if (folderName.equals(v.folderName)) out.add(v);
        }
        return out;
    }

    /** Distinct folder names with their video counts, most videos first. */
    @NonNull
    public List<FolderEntry> folderEntries(@NonNull List<VideoItem> videos) {
        java.util.LinkedHashMap<String, FolderEntry> map = new java.util.LinkedHashMap<>();
        for (VideoItem v : videos) {
            String name = v.folderName != null ? v.folderName : "Unknown";
            FolderEntry e = map.get(name);
            if (e == null) {
                e = new FolderEntry(name, v.dateModifiedSec);
                map.put(name, e);
            }
            e.count++;
            if (v.dateModifiedSec > e.latestModified) e.latestModified = v.dateModifiedSec;
        }
        List<FolderEntry> list = new ArrayList<>(map.values());
        Collections.sort(list, (a, b) -> Integer.compare(b.count, a.count));
        return list;
    }

    /**
     * Videos that were started but not finished, best resume candidates first.
     *
     * <p>The thresholds scale with the file's length. The obvious fixed version
     * ("more than 5s in, more than 15s left") is unsatisfiable for any video shorter than
     * about 20 seconds, which would silently exclude every short clip from the row.
     */
    @NonNull
    public List<VideoItem> continueWatching(@NonNull List<VideoItem> videos, int limit) {
        List<VideoItem> out = new ArrayList<>();
        for (VideoItem v : videos) {
            if (v.durationMs <= 0) continue;
            VideoStateEntity st = mDb.videoStateDao().getSync(v.key());
            if (st == null) continue;

            // Start threshold: 5s into a long file, 5% into a short one, never under 1s.
            long startThreshold = Math.max(1_000L, Math.min(5_000L, v.durationMs / 20));
            // Finish threshold: 15s before the end of a long file, 5% before the end of a
            // short one, never closer than 1s - so a clip is only dropped once it is
            // effectively over.
            long tail = Math.min(15_000L, Math.max(1_000L, v.durationMs / 20));
            long endThreshold = v.durationMs - tail;

            boolean started = st.positionMs > startThreshold;
            boolean unfinished = st.positionMs < endThreshold;
            if (started && unfinished) out.add(v);
        }
        Collections.sort(out, (a, b) -> Long.compare(
                mDb.videoStateDao().getSync(b.key()).updatedAt,
                mDb.videoStateDao().getSync(a.key()).updatedAt));
        if (limit > 0 && out.size() > limit) return new ArrayList<>(out.subList(0, limit));
        return out;
    }

    /**
     * Keys the viewer has watched to (near) the end, computed off the main thread.
     *
     * <p>Used by the library's "hide watched" filter. A video counts as watched once the
     * saved position is inside the last 5% of its length, because nobody reaches the final
     * frame and a stricter rule would leave finished videos on screen. Never-opened videos
     * have no saved state at all and are always kept.
     */
    public void loadWatchedKeys(@NonNull List<VideoItem> videos,
                                @NonNull java.util.function.Consumer<java.util.Set<String>> cb) {
        mExecutor.execute(() -> {
            java.util.Set<String> out = new java.util.HashSet<>();
            for (VideoItem v : videos) {
                VideoStateEntity st = mDb.videoStateDao().getSync(v.key());
                if (st == null || st.durationMs <= 0) continue;
                if (st.positionMs >= st.durationMs * 0.95f) out.add(v.key());
            }
            cb.accept(out);
        });
    }

    /** A video together with where it was left off. */
    public static final class ResumeEntry {
        @NonNull public final VideoItem video;
        public final long positionMs;
        public final long durationMs;
        public final long updatedAt;

        ResumeEntry(@NonNull VideoItem video, long positionMs, long durationMs, long updatedAt) {
            this.video = video;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.updatedAt = updatedAt;
        }
    }

    public interface ResumeCallback {
        void onResumees(@NonNull List<ResumeEntry> entries);
    }

    /**
     * Loads the resume row off the main thread - Room forbids synchronous queries on it,
     * and a naive UI-side call would throw. The callback fires on the worker thread; the
     * caller is responsible for marshalling to the UI thread, matching every other
     * callback in this class.
     */
    public void loadContinueWatching(@NonNull List<VideoItem> videos, int limit,
                                     @NonNull final ResumeCallback cb) {
        mStateExecutor.execute(() -> {
            List<ResumeEntry> out = new ArrayList<>();
            for (VideoItem v : continueWatching(videos, limit)) {
                VideoStateEntity st = mDb.videoStateDao().getSync(v.key());
                if (st == null) continue;
                out.add(new ResumeEntry(v, st.positionMs, st.durationMs, st.updatedAt));
            }
            cb.onResumees(out);
        });
    }

    public interface FavoriteKeysCallback { void onResult(Set<String> keys); }

    public void loadFavoriteKeys(FavoriteKeysCallback callback) {
        mStateExecutor.execute(() -> {
            Set<String> keys = new HashSet<>();
            for (VideoStateEntity state : mDb.videoStateDao().getAllSync()) {
                if (state.favorite) keys.add(state.videoId);
            }
            callback.onResult(keys);
        });
    }

    public void setFavorite(@NonNull VideoItem item, boolean favorite) {
        mStateExecutor.execute(() -> {
            VideoStateEntity st = mDb.videoStateDao().getSync(item.key());
            if (st == null) {
                st = new VideoStateEntity();
                st.videoId = item.key();
                st.positionMs = 0;
                st.durationMs = item.durationMs;
                st.aspectMode = 0;
            }
            st.favorite = favorite;
            st.updatedAt = System.currentTimeMillis();
            mDb.videoStateDao().upsert(st);
        });
    }

    public void isFavorite(@NonNull VideoItem item, @NonNull FavoriteCallback cb) {
        mStateExecutor.execute(() -> {
            VideoStateEntity st = mDb.videoStateDao().getSync(item.key());
            cb.onResult(st != null && st.favorite);
        });
    }

    @NonNull
    public LiveData<List<HistoryEntity>> historyLive(int limit) {
        return mDb.historyDao().getRecent(limit);
    }

    @NonNull
    public LiveData<List<PlaylistEntity>> playlistsLive() {
        return mDb.playlistDao().getAllPlaylists();
    }

    @NonNull
    public LiveData<List<PlaylistVideoEntity>> playlistVideosLive(long playlistId) {
        return mDb.playlistDao().getVideosForPlaylist(playlistId);
    }

    public void createPlaylist(@NonNull String name, @NonNull final CreateCallback cb) {
        mExecutor.execute(() -> {
            PlaylistEntity e = new PlaylistEntity();
            e.name = name;
            e.createdAt = System.currentTimeMillis();
            long id = mDb.playlistDao().insertPlaylist(e);
            cb.onCreated(id);
        });
    }

    public interface CreateCallback {
        void onCreated(long id);
    }

    public void addToPlaylist(long playlistId, @NonNull VideoItem item) {
        mExecutor.execute(() -> {
            List<PlaylistVideoEntity> existing =
                    mDb.playlistDao().getVideosForPlaylistSync(playlistId);
            for (PlaylistVideoEntity e : existing) {
                if (e.videoId.equals(item.key())) return; // no duplicates
            }
            PlaylistVideoEntity e = new PlaylistVideoEntity();
            e.playlistId = playlistId;
            e.videoId = item.key();
            e.title = item.title;
            e.uri = item.uri.toString();
            e.durationMs = item.durationMs;
            e.positionInPlaylist = existing.size();
            mDb.playlistDao().insertVideo(e);
        });
    }

    public void deletePlaylist(long playlistId) {
        mExecutor.execute(() -> mDb.playlistDao().deletePlaylist(playlistId));
    }

    /** Persist a drag-reordered playlist: the list's new order becomes each row's position. */
    public void savePlaylistOrder(@NonNull final List<PlaylistVideoEntity> ordered) {
        mExecutor.execute(() -> {
            for (int i = 0; i < ordered.size(); i++) {
                PlaylistVideoEntity e = ordered.get(i);
                if (e.positionInPlaylist != i) {
                    e.positionInPlaylist = i;
                    mDb.playlistDao().updatePosition(e.id, i);
                }
            }
        });
    }

    /** Remove one row from a playlist (swipe-to-remove in the playlist detail list). */
    public void removePlaylistVideo(long rowId) {
        mExecutor.execute(() -> mDb.playlistDao().removeVideoFromPlaylist(rowId));
    }

    /**
     * Delete resume-state and history rows whose video no longer exists in MediaStore.
     * Called by {@link com.cinemax.nexus.job.StaleStateJobService} on its worker thread.
     *
     * @return number of rows removed
     */
    public int pruneStaleMedia() {
        // A partial or revoked grant is not evidence that unlisted videos were deleted.
        if (androidx.core.content.ContextCompat.checkSelfPermission(mAppContext,
                com.cinemax.nexus.util.PermissionUtils.mediaPermission())
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return 0;
        Set<String> live = new HashSet<>();
        Cursor c = mAppContext.getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Video.Media._ID}, null, null, null);
        if (c == null) return 0;
        if (c != null) {
            try {
                while (c.moveToNext()) live.add(c.getString(0));
            } finally {
                c.close();
            }
        }
        int removed = 0;
        Set<String> libraryKeys = new HashSet<>();
        for (HistoryEntity h : mDb.historyDao().getAllSync()) {
            if (h.uri != null && h.uri.startsWith("content://media/")) libraryKeys.add(h.videoId);
        }
        for (VideoStateEntity st : mDb.videoStateDao().getAllSync()) {
            if (libraryKeys.contains(st.videoId) && !live.contains(st.videoId)) {
                mDb.videoStateDao().delete(st.videoId);
                removed++;
            }
        }
        for (HistoryEntity h : mDb.historyDao().getAllSync()) {
            if (libraryKeys.contains(h.videoId) && !live.contains(h.videoId)) {
                mDb.historyDao().deleteByVideoId(h.videoId);
                removed++;
            }
        }
        return removed;
    }

    /** Save watch position; used for resume + history. */
    public void saveProgress(@NonNull VideoItem item, long positionMs, long durationMs) {
        mStateExecutor.execute(() -> {
            long now = System.currentTimeMillis();
            VideoStateEntity st = mDb.videoStateDao().getSync(item.key());
            if (st == null) {
                st = new VideoStateEntity();
                st.videoId = item.key();
                st.aspectMode = 0;
            }
            st.positionMs = positionMs;
            st.durationMs = durationMs > 0 ? durationMs : item.durationMs;
            st.updatedAt = now;
            mDb.videoStateDao().upsert(st);

            HistoryEntity h = mDb.historyDao().getSync(item.key());
            if (h == null) h = new HistoryEntity();
            h.videoId = item.key();
            h.title = item.title;
            h.uri = item.uri.toString();
            h.durationMs = st.durationMs;
            h.positionMs = positionMs;
            h.lastPlayedAt = now;
            h.watchedMs = Math.max(h.watchedMs, positionMs);
            mDb.historyDao().upsert(h);
        });
    }

    public long getResumePosition(@NonNull VideoItem item) {
        VideoStateEntity st = mDb.videoStateDao().getSync(item.key());
        return st != null ? st.positionMs : 0L;
    }

    @Nullable
    public VideoStateEntity getVideoState(@NonNull String videoId) {
        return mDb.videoStateDao().getSync(videoId);
    }

    public void saveVideoState(@NonNull String videoId, float speed, int aspectMode) {
        mStateExecutor.execute(() -> {
            VideoStateEntity st = mDb.videoStateDao().getSync(videoId);
            if (st == null) {
                st = new VideoStateEntity();
                st.videoId = videoId;
            }
            st.speed = speed;
            st.aspectMode = aspectMode;
            st.updatedAt = System.currentTimeMillis();
            mDb.videoStateDao().upsert(st);
        });
    }

    public interface StateCallback {
        void onState(@Nullable VideoStateEntity state);
    }

    public interface PositionCallback {
        void onPosition(long positionMs, long durationMs);
    }

    /** Room forbids main-thread queries, so state reads are always dispatched. */
    public void getStateAsync(@NonNull String videoId, @NonNull final StateCallback cb) {
        mExecutor.execute(() -> cb.onState(mDb.videoStateDao().getSync(videoId)));
    }

    public void getResumePositionAsync(@NonNull String videoId,
                                       @NonNull final PositionCallback cb) {
        mExecutor.execute(() -> {
            VideoStateEntity st = mDb.videoStateDao().getSync(videoId);
            cb.onPosition(st != null ? st.positionMs : 0L,
                    st != null ? st.durationMs : 0L);
        });
    }

    public void getHistoryAsync(@NonNull String videoId,
                                @NonNull final HistoryCallback cb) {
        mExecutor.execute(() -> cb.onHistory(mDb.historyDao().getSync(videoId)));
    }

    public interface HistoryCallback {
        void onHistory(@Nullable HistoryEntity entry);
    }

    public void deleteVideoState(@NonNull String videoId) {
        mExecutor.execute(() -> mDb.videoStateDao().delete(videoId));
    }

    // ---- History ----

    public void clearHistory() {
        mExecutor.execute(() -> mDb.historyDao().clearAll());
    }

    @Nullable
    public HistoryEntity getHistoryEntry(@NonNull String videoId) {
        return mDb.historyDao().getSync(videoId);
    }

    public void deleteHistory(@NonNull String videoId) {
        mExecutor.execute(() -> mDb.historyDao().deleteByVideoId(videoId));
    }

    // ---- Bookmarks ----

    @NonNull
    public LiveData<List<BookmarkEntity>> bookmarksLive(@NonNull String videoId) {
        return mDb.bookmarkDao().getForVideo(videoId);
    }

    @NonNull
    public List<BookmarkEntity> getBookmarksSync(@NonNull String videoId) {
        return mDb.bookmarkDao().getForVideoSync(videoId);
    }

    public void addBookmark(@NonNull String videoId, @NonNull String title, long positionMs) {
        mExecutor.execute(() -> {
            BookmarkEntity e = new BookmarkEntity();
            e.videoId = videoId;
            e.title = title;
            e.positionMs = positionMs;
            e.createdAt = System.currentTimeMillis();
            mDb.bookmarkDao().insert(e);
        });
    }

    public void deleteBookmark(long id) {
        mExecutor.execute(() -> mDb.bookmarkDao().delete(id));
    }

    public void deleteBookmarksForVideo(@NonNull String videoId) {
        mExecutor.execute(() -> mDb.bookmarkDao().deleteForVideo(videoId));
    }

    public void shutdown() {
        mSource.shutdown();
        mExecutor.shutdownNow();
    }

    /** Lightweight folder summary used by the folder browser. */
    public static class FolderEntry {
        @NonNull public final String name;
        public int count;
        public long latestModified;

        public FolderEntry(@NonNull String name, long latestModified) {
            this.name = name;
            this.latestModified = latestModified;
        }
    }
}
