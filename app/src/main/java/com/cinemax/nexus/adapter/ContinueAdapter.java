package com.cinemax.nexus.adapter;

import android.graphics.Bitmap;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.cinemax.nexus.R;
import com.cinemax.nexus.data.MediaRepository;
import com.cinemax.nexus.data.ThumbnailLoader;
import com.cinemax.nexus.data.model.VideoItem;
import com.cinemax.nexus.util.FormatUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal rail of unfinished videos, shown at the top of the library.
 *
 * <p>Each card carries the resume position so the row shows how far into a file the viewer
 * got, both as a bar and as "position / duration / remaining".
 */
public class ContinueAdapter extends RecyclerView.Adapter<ContinueAdapter.ResumeViewHolder> {

    public interface OnVideoClickListener {
        void onVideoClick(@NonNull VideoItem item);
    }

    private final ThumbnailLoader mThumbs;
    private final List<MediaRepository.ResumeEntry> mItems = new ArrayList<>();

    private OnVideoClickListener mListener;

    public ContinueAdapter(@NonNull ThumbnailLoader thumbs) {
        mThumbs = thumbs;
        setHasStableIds(true);
    }

    public void setOnVideoClickListener(OnVideoClickListener listener) {
        mListener = listener;
    }

    /** Replaces the rail's contents. Must be called on the UI thread. */
    public void submit(@NonNull List<MediaRepository.ResumeEntry> entries) {
        mItems.clear();
        mItems.addAll(entries);
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        return mItems.get(position).video.id;
    }

    @NonNull
    @Override
    public ResumeViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_continue, parent, false);
        return new ResumeViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ResumeViewHolder holder, int position) {
        holder.bind(mItems.get(position));
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    class ResumeViewHolder extends RecyclerView.ViewHolder {

        private final ImageView thumb;
        private final TextView title;
        private final TextView duration;
        private final TextView position;
        private final ProgressBar progress;

        ResumeViewHolder(@NonNull View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.thumb);
            title = itemView.findViewById(R.id.title);
            duration = itemView.findViewById(R.id.duration);
            position = itemView.findViewById(R.id.position);
            progress = itemView.findViewById(R.id.progress);
        }

        void bind(@NonNull MediaRepository.ResumeEntry entry) {
            VideoItem item = entry.video;
            title.setText(item.title);
            duration.setText(FormatUtils.formatDuration(item.durationMs));

            long remaining = Math.max(0, entry.durationMs - entry.positionMs);
            position.setText(FormatUtils.formatDuration(entry.positionMs)
                    + " / " + FormatUtils.formatDuration(entry.durationMs)
                    + "  \u00b7  -" + FormatUtils.formatDuration(remaining));

            if (entry.durationMs > 0) {
                long scaled = (entry.positionMs * 1000L) / entry.durationMs;
                progress.setProgress((int) Math.min(1000L, Math.max(0L, scaled)));
                progress.setVisibility(View.VISIBLE);
            } else {
                progress.setVisibility(View.GONE);
            }

            thumb.setImageResource(R.drawable.ic_video_placeholder);
            mThumbs.load(item, (uri, bitmap) -> {
                // RecyclerView rebinds holders while a decode is in flight; only apply
                // the result if this holder is still showing the entry it was asked for.
                if (getItem() != entry) return;
                if (bitmap != null) thumb.setImageBitmap(bitmap);
            });

            itemView.setOnClickListener(v -> {
                if (mListener != null) mListener.onVideoClick(item);
            });
        }

        /** The entry currently bound to this holder, or null when it has been recycled. */
        @Nullable
        private MediaRepository.ResumeEntry getItem() {
            int pos = getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION || pos >= mItems.size()) return null;
            return mItems.get(pos);
        }
    }
}
