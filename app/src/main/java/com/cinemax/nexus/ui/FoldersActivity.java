package com.cinemax.nexus.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cinemax.nexus.R;
import com.cinemax.nexus.data.MediaRepository;
import com.cinemax.nexus.data.model.VideoItem;

import java.util.ArrayList;
import java.util.List;

public class FoldersActivity extends AppCompatActivity {

    private MediaRepository mRepository;
    private FolderAdapter mAdapter;
    private TextView mEmpty;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.action_folders);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        mRepository = MediaRepository.getInstance(this);
        mEmpty = findViewById(R.id.empty_text);
        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new FolderAdapter();
        recycler.setAdapter(mAdapter);

        mRepository.loadVideos(new MediaRepository.LoadCallback() {
            @Override
            public void onLoaded(@NonNull List<VideoItem> videos) {
                List<MediaRepository.FolderEntry> folders = mRepository.folderEntries(videos);
                runOnUiThread(() -> {
                    mAdapter.submit(folders);
                    mEmpty.setVisibility(folders.isEmpty() ? View.VISIBLE : View.GONE);
                });
            }

            @Override
            public void onError(@NonNull Exception e) {
                runOnUiThread(() -> mEmpty.setVisibility(View.VISIBLE));
            }
        });
    }

    private class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.VH> {
        private final List<MediaRepository.FolderEntry> mItems = new ArrayList<>();

        void submit(@NonNull List<MediaRepository.FolderEntry> folders) {
            mItems.clear();
            mItems.addAll(folders);
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
            MediaRepository.FolderEntry e = mItems.get(position);
            holder.name.setText(e.name);
            holder.count.setText(getString(R.string.videos_count, e.count));
            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(FoldersActivity.this, FolderVideosActivity.class);
                intent.putExtra(FolderVideosActivity.EXTRA_FOLDER, e.name);
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
