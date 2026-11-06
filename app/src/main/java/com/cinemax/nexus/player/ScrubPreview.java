package com.cinemax.nexus.player;

import android.app.Activity;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.cinemax.nexus.R;
import com.cinemax.nexus.util.FormatUtils;
import java.util.concurrent.*;

/** Coalesced, bounded frame previews. Video decoding stays off the UI thread. */
public final class ScrubPreview {
    private final Activity activity;
    private final View bubble;
    private final ImageView image;
    private final TextView time;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Runnable pending;
    private volatile int generation;
    private volatile boolean closed;

    public ScrubPreview(Activity activity) {
        this.activity = activity;
        bubble = activity.findViewById(R.id.scrub_preview);
        image = activity.findViewById(R.id.scrub_frame);
        time = activity.findViewById(R.id.scrub_time);
    }

    public void show(Uri source, long position, float fraction) {
        if (closed) return;
        int request = ++generation;
        image.setImageDrawable(null);
        time.setText(FormatUtils.formatDuration(position));
        bubble.setVisibility(View.VISIBLE);
        View parent = (View) bubble.getParent();
        float width = bubble.getWidth() > 0 ? bubble.getWidth() : dp(164);
        float margin = dp(20);
        bubble.setTranslationX(Math.max(margin, Math.min(parent.getWidth() - width - margin,
                fraction * parent.getWidth() - width / 2)));
        if (pending != null) main.removeCallbacks(pending);
        pending = () -> worker.execute(() -> {
            if (closed || request != generation) return;
            Bitmap result = null;
            MediaMetadataRetriever reader = new MediaMetadataRetriever();
            try {
                // Network/DRM preview is intentionally not attempted by this helper.
                String scheme = source.getScheme();
                if (!"content".equals(scheme) && !"file".equals(scheme)) return;
                reader.setDataSource(activity.getApplicationContext(), source);
                if (Build.VERSION.SDK_INT >= 27) result = reader.getScaledFrameAtTime(position * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180);
                else {
                    Bitmap frame = reader.getFrameAtTime(position * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame != null) {
                        result = Bitmap.createScaledBitmap(frame, 320, 180, true);
                        if (result != frame) frame.recycle();
                    }
                }
            } catch (Exception ignored) { /* The timestamp remains available without a frame. */ }
            finally { try { reader.release(); } catch (Exception ignored) {} }
            final Bitmap frame = result;
            main.post(() -> {
                if (closed || request != generation) { if (frame != null) frame.recycle(); return; }
                image.setImageBitmap(frame);
            });
        });
        main.postDelayed(pending, 140);
    }

    private int dp(int value) { return (int) (value * activity.getResources().getDisplayMetrics().density); }
    public void hide() {
        generation++;
        if (pending != null) main.removeCallbacks(pending);
        bubble.setVisibility(View.GONE);
        image.setImageDrawable(null);
    }
    public void close() { closed = true; hide(); worker.shutdownNow(); main.removeCallbacksAndMessages(null); }
}
