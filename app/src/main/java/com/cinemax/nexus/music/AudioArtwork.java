package com.cinemax.nexus.music;

import android.content.Context;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.util.LruCache;
import java.util.concurrent.*;

/** Bounded album-art decoding off main, with identity checked by the receiving View. */
public final class AudioArtwork {
    public interface Result { void ready(Bitmap image); }
    private final Context context;
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(3 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };

    public AudioArtwork(Context context) { this.context = context.getApplicationContext(); }
    public void load(Uri uri, Result callback) {
        Bitmap existing = cache.get(uri.toString());
        if (existing != null) { callback.ready(existing); return; }
        if (closed) return;
        worker.execute(() -> {
            Bitmap decoded = null;
            MediaMetadataRetriever reader = new MediaMetadataRetriever();
            try {
                reader.setDataSource(context, uri);
                byte[] bytes = reader.getEmbeddedPicture();
                if (bytes != null && bytes.length <= 8 * 1024 * 1024) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    options.inSampleSize = 1;
                    while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2;
                    options.inJustDecodeBounds = false;
                    decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                }
            } catch (Exception ignored) { /* A missing cover uses the music glyph. */ }
            finally { try { reader.release(); } catch (Exception ignored) {} }
            final Bitmap result = decoded;
            if (result != null) cache.put(uri.toString(), result);
            main.post(() -> { if (!closed) callback.ready(result); });
        });
    }
    public void close() { closed = true; worker.shutdownNow(); main.removeCallbacksAndMessages(null); cache.evictAll(); }
}
