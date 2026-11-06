package com.cinemax.nexus.adapter;

import android.graphics.Bitmap;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.cinemax.nexus.R;
import com.cinemax.nexus.data.ThumbnailLoader;
import com.cinemax.nexus.data.model.VideoItem;
import com.cinemax.nexus.util.FormatUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Grid/list adapter for videos with async thumbnails and multi-select support. */
public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {

    public interface OnVideoClickListener {
        void onVideoClick(@NonNull VideoItem item, int position);
        boolean onVideoLongClick(@NonNull VideoItem item, int position);
    }

    /** Fires after every selection change so the host can retitle/close its ActionMode. */
    public interface OnSelectionChangedListener {
        void onSelectionChanged(int count);
    }

    private final List<VideoItem> mItems = new ArrayList<>();
    private OnVideoClickListener mListener;
    private OnSelectionChangedListener mSelectionListener;
    private final ThumbnailLoader mThumbs;
    private final boolean mShowFolder;

    /**
     * Selection is tracked by {@link VideoItem#key()} rather than position, because the
     * grid is re-filtered and re-sorted underneath the selection while the ActionMode is up.
     * Insertion order is kept so bulk actions run in the order the viewer picked them.
     */
    private final Set<String> mSelectedKeys = new LinkedHashSet<>();
    private boolean mSelectionMode;
    private boolean mListMode;
    public void setListMode(boolean enabled) { if(mListMode != enabled) {mListMode=enabled;notifyDataSetChanged();} }
    @Override public int getItemViewType(int position) { return mListMode ? 1 : 0; }

    public VideoAdapter(@NonNull ThumbnailLoader thumbs, boolean showFolder) {
        mThumbs = thumbs;
        mShowFolder = showFolder;
        setHasStableIds(true);
    }

    public void setOnVideoClickListener(@Nullable OnVideoClickListener l) {
        mListener = l;
    }

    public void submit(@NonNull List<VideoItem> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    public VideoItem getAt(int position) {
        return mItems.get(position);
    }

    public boolean isSelectionMode() {
        return mSelectionMode;
    }

    public int getSelectedCount() {
        return mSelectedKeys.size();
    }

    /** Turns selection mode on/off; switching off also drops whatever was selected. */
    public void setSelectionMode(boolean enabled) {
        mSelectionMode = enabled;
        if (!enabled) mSelectedKeys.clear();
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    /** Toggles the tapped card and reports back. Returns the new selection count. */
    public int toggleSelection(@NonNull VideoItem item) {
        if (!mSelectionMode) return 0;
        String key = item.key();
        if (!mSelectedKeys.remove(key)) mSelectedKeys.add(key);
        notifyDataSetChanged();
        notifySelectionChanged();
        return mSelectedKeys.size();
    }

    public void selectAll() {
        if (!mSelectionMode) return;
        for (VideoItem item : mItems) mSelectedKeys.add(item.key());
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void clearSelection() {
        mSelectedKeys.clear();
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    /**
     * The selected videos in the order they were tapped, filtered down to the ones still
     * on screen - a card that scrolled out of a filter is not a valid target any more.
     */
    @NonNull
    public List<VideoItem> getSelectedItems() {
        List<VideoItem> out = new ArrayList<>();
        for (VideoItem item : mItems) {
            if (mSelectedKeys.contains(item.key())) out.add(item);
        }
        return out;
    }

    public void setOnSelectionChangedListener(@Nullable OnSelectionChangedListener l) {
        mSelectionListener = l;
    }

    private void notifySelectionChanged() {
        if (mSelectionListener != null) mSelectionListener.onSelectionChanged(mSelectedKeys.size());
    }

    @Override
    public long getItemId(int position) {
        return mItems.get(position).id;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(viewType == 1 ? R.layout.item_video_row : R.layout.item_video, parent, false);
        return new VideoViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        VideoItem item = mItems.get(position);
        holder.title.setText(item.title);
        holder.duration.setText(FormatUtils.formatDuration(item.durationMs));
        if (mShowFolder && item.folderName != null) {
            holder.folder.setVisibility(View.VISIBLE);
            holder.folder.setText(mListMode ? item.folderName + " · " + FormatUtils.formatSize(item.sizeBytes)
                    + (item.width > 0 ? " · " + item.width + "×" + item.height : "") : item.folderName);
        } else {
            holder.folder.setVisibility(View.GONE);
        }
        holder.thumb.setImageResource(R.drawable.bg_thumb_placeholder);
        holder.thumb.setTag(item.key());
        mThumbs.load(item, (uri, bitmap) -> {
            if (bitmap != null && item.key().equals(holder.thumb.getTag())) {
                holder.thumb.setImageBitmap(bitmap);
            }
        });
        // Selected cards are dimmed and badged so the set is readable at a glance.
        boolean selected = mSelectionMode && mSelectedKeys.contains(item.key());
        holder.selectBadge.setVisibility(selected ? View.VISIBLE : View.GONE);
        holder.itemView.setAlpha(selected ? 0.55f : 1f);
        holder.itemView.setOnClickListener(v -> {
            if (mListener != null) mListener.onVideoClick(item, holder.getAdapterPosition());
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (mListener != null) {
                return mListener.onVideoLongClick(item, holder.getAdapterPosition());
            }
            return false;
        });
    }

    static class VideoViewHolder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title;
        final TextView duration;
        final TextView folder;
        final ImageView selectBadge;

        VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.thumb);
            title = itemView.findViewById(R.id.title);
            duration = itemView.findViewById(R.id.duration);
            folder = itemView.findViewById(R.id.folder);
            selectBadge = itemView.findViewById(R.id.select_badge);
        }
    }
}

