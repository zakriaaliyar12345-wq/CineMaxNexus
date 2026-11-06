package com.cinemax.nexus.data;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cinemax.nexus.data.model.VideoItem;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads video thumbnails off the main thread with a bounded memory cache.
 * Prefers the efficient MediaStore thumbnail path on Q+ and falls back to
 * {@link MediaMetadataRetriever} frame grabbing on older devices.
 */
public class ThumbnailLoader {

    public interface Callback {
        void onThumbnail(@NonNull Uri uri, @Nullable Bitmap bitmap);
    }

    private static volatile ThumbnailLoader sInstance;

    private final ContentResolver mResolver;
    private final ExecutorService mExecutor;
    private final Handler mMain;
    private final LruCache<String, Bitmap> mCache;

    public static ThumbnailLoader getInstance(@NonNull android.content.Context context) {
        if (sInstance == null) {
            synchronized (ThumbnailLoader.class) {
                if (sInstance == null) {
                    sInstance = new ThumbnailLoader(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private ThumbnailLoader(@NonNull android.content.Context appContext) {
        mResolver = appContext.getContentResolver();
        mExecutor = Executors.newFixedThreadPool(2);
        mMain = new Handler(Looper.getMainLooper());
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024L) / 8;
        mCache = new LruCache<String, Bitmap>(Math.max(maxKb, 4096)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    /** Load a thumbnail for the given video; returns cached bitmap immediately if present. */
    public void load(@NonNull VideoItem item, @NonNull Callback callback) {
        String key = item.key();
        Bitmap cached = mCache.get(key);
        if (cached != null) {
            callback.onThumbnail(item.uri, cached);
            return;
        }
        mExecutor.execute(() -> {
            Bitmap bmp = loadSync(item);
            if (bmp != null) mCache.put(key, bmp);
            mMain.post(() -> callback.onThumbnail(item.uri, bmp));
        });
    }

    @Nullable
    private Bitmap loadSync(@NonNull VideoItem item) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // Keep the original volume/URI, including removable storage and SAF files.
                Bitmap b = mResolver.loadThumbnail(item.uri, new Size(480, 270), null);
                if (b != null) return b;
            } catch (Exception ignored) {
            }
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        AssetFileDescriptor afd = null;
        try {
            afd = mResolver.openAssetFileDescriptor(item.uri, "r");
            if (afd == null) return null;
            // There is no setDataSource(AssetFileDescriptor) overload; pass the raw
            // descriptor plus the container offset/length instead.
            retriever.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(),
                    afd.getLength());
            return frameAt(retriever, 1_000_000L);
        } catch (Exception ignored) {
        } finally {
            if (afd != null) {
                try {
                    afd.close();
                } catch (Exception ignored) {
                }
            }
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Grabs a single frame. Compile SDK 37 only exposes the {@code Bitmap}-returning
     * overloads, so on API 24-28 devices (where the platform still returns
     * {@code byte[]}) we resolve the legacy signature reflectively instead of crashing
     * with a {@code NoSuchMethodError}.
     */
    @Nullable
    private static Bitmap frameAt(@NonNull MediaMetadataRetriever retriever, long timeUs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return retriever.getFrameAtTime(timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Exception ignored) {
                return null;
            }
        }
        try {
            Method legacy = MediaMetadataRetriever.class
                    .getMethod("getFrameAtTime", long.class, int.class);
            Object out = legacy.invoke(retriever, timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (out instanceof Bitmap) return (Bitmap) out;
            if (out instanceof byte[]) {
                byte[] data = (byte[]) out;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2;
                return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public void clearCache() {
        mCache.evictAll();
    }
}
