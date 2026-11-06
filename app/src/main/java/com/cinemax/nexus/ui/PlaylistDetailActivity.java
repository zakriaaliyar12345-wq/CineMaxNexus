package com.cinemax.nexus.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cinemax.nexus.R;
import com.cinemax.nexus.data.MediaRepository;
import com.cinemax.nexus.data.db.entity.PlaylistVideoEntity;
import com.cinemax.nexus.player.PlayerActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlaylistDetailActivity extends AppCompatActivity {

    private long mPlaylistId;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);

        mPlaylistId = getIntent().getLongExtra(PlaylistsActivity.EXTRA_PLAYLIST_ID, -1);
        String name = getIntent().getStringExtra(PlaylistsActivity.EXTRA_PLAYLIST_NAME);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(name != null ? name : getString(R.string.action_playlists));
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView empty = findViewById(R.id.empty_text);
        empty.setText(R.string.playlists_empty);
        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        VideoRowAdapter adapter = new VideoRowAdapter();
        recycler.setAdapter(adapter);

        // Course topic 04.5 (RecyclerView), advanced half: ItemTouchHelper gives the list
        // long-press drag-to-reorder and swipe-to-remove. Reordering rewrites each row's
        // position_in_playlist through the repository so the new order survives a restart.
        ItemTouchHelper touchHelper = new ItemTouchHelper(
                new ItemTouchHelper.SimpleCallback(
                        ItemTouchHelper.UP | ItemTouchHelper.DOWN,
                        ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {

                    @Override
                    public boolean onMove(@NonNull RecyclerView rv,
                                          @NonNull RecyclerView.ViewHolder vh,
                                          @NonNull RecyclerView.ViewHolder target) {
                        int from = vh.getBindingAdapterPosition();
                        int to = target.getBindingAdapterPosition();
                        if (from == RecyclerView.NO_POSITION
                                || to == RecyclerView.NO_POSITION) {
                            return false;
                        }
                        adapter.moveItem(from, to);
                        return true;
                    }

                    @Override
                    public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                        int pos = vh.getBindingAdapterPosition();
                        if (pos == RecyclerView.NO_POSITION) return;
                        PlaylistVideoEntity removed = adapter.removeItem(pos);
                        if (removed != null) {
                            MediaRepository.getInstance(PlaylistDetailActivity.this)
                                    .removePlaylistVideo(removed.id);
                            Toast.makeText(PlaylistDetailActivity.this,
                                    R.string.removed_from_playlist,
                                    Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void clearView(@NonNull RecyclerView rv,
                                          @NonNull RecyclerView.ViewHolder vh) {
                        super.clearView(rv, vh);
                        // Drag finished (or swipe reset): persist the visible order.
                        MediaRepository.getInstance(PlaylistDetailActivity.this)
                                .savePlaylistOrder(adapter.currentItems());
                    }
                });
        touchHelper.attachToRecyclerView(recycler);

        MediaRepository.getInstance(this).playlistVideosLive(mPlaylistId)
                .observe(this, new Observer<List<PlaylistVideoEntity>>() {
                    @Override
                    public void onChanged(List<PlaylistVideoEntity> items) {
                        adapter.submit(items);
                        empty.setVisibility(items == null || items.isEmpty()
                                ? View.VISIBLE : View.GONE);
                    }
                });
    }

    private class VideoRowAdapter extends RecyclerView.Adapter<VideoRowAdapter.VH> {
        private final List<PlaylistVideoEntity> mItems = new ArrayList<>();

        void submit(@NonNull List<PlaylistVideoEntity> items) {
            mItems.clear();
            if (items != null) mItems.addAll(items);
            notifyDataSetChanged();
        }

        /** Move one row during a drag; called for every intermediate step. */
        void moveItem(int from, int to) {
            if (from < 0 || from >= mItems.size() || to < 0 || to >= mItems.size()) return;
            Collections.swap(mItems, from, to);
            notifyItemMoved(from, to);
        }

        /** Remove a swiped row and return it so the caller can delete it by id. */
        @Nullable
        PlaylistVideoEntity removeItem(int position) {
            if (position < 0 || position >= mItems.size()) return null;
            PlaylistVideoEntity removed = mItems.remove(position);
            notifyItemRemoved(position);
            return removed;
        }

        /** The live backing list — handed to the repository after a drag to persist. */
        @NonNull
        List<PlaylistVideoEntity> currentItems() {
            return mItems;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_folder, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            PlaylistVideoEntity e = mItems.get(position);
            holder.name.setText(e.title);
            holder.count.setText(com.cinemax.nexus.util.FormatUtils.formatDuration(e.durationMs));
            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(PlaylistDetailActivity.this, PlayerActivity.class);
                intent.setData(Uri.parse(e.uri));
                intent.putExtra(PlayerActivity.EXTRA_TITLE, e.title);
                intent.putExtra(PlayerActivity.EXTRA_VIDEO_ID, e.videoId);
                intent.putExtra(PlayerActivity.EXTRA_DURATION, e.durationMs);
                startActivity(intent);
            });
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView count;

            VH(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.name);
                count = itemView.findViewById(R.id.count);
            }
        }
    }
}
