package com.cinemax.nexus.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cinemax.nexus.R;
import com.cinemax.nexus.data.MediaRepository;
import com.cinemax.nexus.data.db.entity.PlaylistEntity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

public class PlaylistsActivity extends AppCompatActivity {

    public static final String EXTRA_PLAYLIST_ID = "extra_playlist_id";
    public static final String EXTRA_PLAYLIST_NAME = "extra_playlist_name";

    private PlaylistAdapter mAdapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.action_playlists);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView empty = findViewById(R.id.empty_text);
        empty.setText(R.string.playlists_empty);
        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new PlaylistAdapter();
        recycler.setAdapter(mAdapter);

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setVisibility(View.VISIBLE);
        fab.setOnClickListener(v -> showCreateDialog());

        // Course topic 10.1 (ViewModel): the LiveData lives in PlaylistsViewModel, so a
        // configuration change re-attaches to the same query result instead of re-running it.
        new ViewModelProvider(this).get(PlaylistsViewModel.class)
                .getPlaylists()
                .observe(this, new Observer<List<PlaylistEntity>>() {
                    @Override
                    public void onChanged(List<PlaylistEntity> playlists) {
                        mAdapter.submit(playlists);
                        empty.setVisibility(playlists == null || playlists.isEmpty()
                                ? View.VISIBLE : View.GONE);
                    }
                });
    }

    private void showCreateDialog() {
        EditText input = new EditText(this);
        input.setHint(R.string.playlist_name_hint);
        input.setPadding(48, 24, 48, 12);
        new AlertDialog.Builder(this)
                .setTitle(R.string.new_playlist)
                .setView(input)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        MediaRepository.getInstance(this).createPlaylist(name, id -> { });
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.VH> {
        private final List<PlaylistEntity> mItems = new ArrayList<>();

        void submit(@NonNull List<PlaylistEntity> items) {
            mItems.clear();
            if (items != null) mItems.addAll(items);
            notifyDataSetChanged();
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
            PlaylistEntity e = mItems.get(position);
            holder.name.setText(e.name);
            holder.count.setText("");
            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(PlaylistsActivity.this, PlaylistDetailActivity.class);
                intent.putExtra(EXTRA_PLAYLIST_ID, e.id);
                intent.putExtra(EXTRA_PLAYLIST_NAME, e.name);
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
