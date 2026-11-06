package com.cinemax.nexus.data;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cinemax.nexus.data.model.VideoItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Queries {@link MediaStore.Video} on a background thread and returns {@link VideoItem}s.
 * Handles scoped-storage column differences across API levels and skips unreadable rows
 * instead of crashing on corrupt metadata.
 */
public class MediaStoreSource {

    public interface Callback {
        void onVideosLoaded(@NonNull List<VideoItem> videos);
        void onError(@NonNull Exception e);
    }

    private static final String[] PROJECTION = {
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATA
    };

    private final ContentResolver mResolver;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    public MediaStoreSource(@NonNull Context context) {
        this.mResolver = context.getApplicationContext().getContentResolver();
    }

    public void loadAll(@NonNull Callback callback) {
        mExecutor.execute(() -> {
            try {
                List<VideoItem> videos = queryVideos();
                callback.onVideosLoaded(videos);
            } catch (Exception e) {
                callback.onError(e);
            }
        });
    }

    @NonNull
    private List<VideoItem> queryVideos() {
        List<VideoItem> result = new ArrayList<>();
        Uri collection;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        } else {
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        }

        String sort = MediaStore.Video.Media.DATE_ADDED + " DESC";
        try (Cursor cursor = mResolver.query(collection, PROJECTION, null, null, sort)) {
            if (cursor == null) return result;
            int idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int titleIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE);
            int nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int durIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
            int sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int addedIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
            int modIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED);
            int wIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH);
            int hIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT);
            int mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE);
            int bucketIdx = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
            int dataIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATA);

            while (cursor.moveToNext()) {
                try {
                    long id = cursor.getLong(idIdx);
                    String title = cursor.getString(titleIdx);
                    String displayName = cursor.getString(nameIdx);
                    if (title == null || title.isEmpty()) title = displayName;
                    if (title == null) title = "Untitled";

                    long duration = cursor.getLong(durIdx);
                    long size = cursor.getLong(sizeIdx);
                    long added = cursor.getLong(addedIdx);
                    long modified = cursor.getLong(modIdx);
                    int width = cursor.getInt(wIdx);
                    int height = cursor.getInt(hIdx);
                    String mime = cursor.getString(mimeIdx);
                    String bucket = bucketIdx >= 0 ? cursor.getString(bucketIdx) : null;
                    String dataPath = dataIdx >= 0 ? cursor.getString(dataIdx) : null;

                    String folderPath = null;
                    if (dataPath != null) {
                        int slash = dataPath.lastIndexOf('/');
                        if (slash > 0) folderPath = dataPath.substring(0, slash);
                    }

                    Uri uri = ContentUris.withAppendedId(collection, id);
                    result.add(new VideoItem(id, title, uri, duration, size,
                            added, modified, width, height, bucket, folderPath, mime));
                } catch (Exception rowError) {
                    // Skip unreadable/corrupt rows rather than aborting the whole scan.
                }
            }
        }
        return result;
    }

    public void shutdown() {
        mExecutor.shutdownNow();
    }
}
