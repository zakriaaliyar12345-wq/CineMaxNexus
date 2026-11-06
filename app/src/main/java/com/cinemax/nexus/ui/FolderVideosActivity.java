package com.cinemax.nexus.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cinemax.nexus.R;
import com.cinemax.nexus.adapter.VideoAdapter;
import com.cinemax.nexus.data.MediaRepository;
import com.cinemax.nexus.data.ThumbnailLoader;
import com.cinemax.nexus.data.model.VideoItem;
import com.cinemax.nexus.player.PlayerActivity;
import com.cinemax.nexus.util.PrivateFolder;

import java.util.List;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class FolderVideosActivity extends AppCompatActivity {

    public static final String EXTRA_FOLDER = "extra_folder";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_library);

        String folder = getIntent().getStringExtra(EXTRA_FOLDER);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(folder != null ? folder : getString(R.string.action_folders));
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        ProgressBar progress = findViewById(R.id.progress);
        // empty_state is a LinearLayout container; the message lives in empty_text.
        View empty = findViewById(R.id.empty_state);
        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new GridLayoutManager(this, 2));

        VideoAdapter adapter = new VideoAdapter(ThumbnailLoader.getInstance(this), false);
        adapter.setOnVideoClickListener(new VideoAdapter.OnVideoClickListener() {
            @Override
            public void onVideoClick(@NonNull VideoItem item, int position) {
                Intent intent = new Intent(FolderVideosActivity.this, PlayerActivity.class);
                intent.setData(item.uri);
                intent.putExtra(PlayerActivity.EXTRA_TITLE, item.title);
                intent.putExtra(PlayerActivity.EXTRA_VIDEO_ID, item.key());
                intent.putExtra(PlayerActivity.EXTRA_DURATION, item.durationMs);
                if (item.folderName != null) {
                    intent.putExtra(PlayerActivity.EXTRA_FOLDER, item.folderName);
                }
                startActivity(intent);
            }

            @Override
            public boolean onVideoLongClick(@NonNull VideoItem item, int position) {
                return false;
            }
        });
        recycler.setAdapter(adapter);

        progress.setVisibility(View.VISIBLE);
        MediaRepository.getInstance(this).loadVideos(new MediaRepository.LoadCallback() {
            @Override
            public void onLoaded(@NonNull List<VideoItem> videos) {
                List<VideoItem> folderVideos = MediaRepository.getInstance(FolderVideosActivity.this)
                        .filterByFolder(videos, folder != null ? folder : "");
                // Hidden videos stay hidden here too, or a folder view would give the
                // private folder away.
                final List<VideoItem> filtered = PrivateFolder.visibleOnly(
                        FolderVideosActivity.this, folderVideos);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    adapter.submit(filtered);
                    empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
                });
            }

            @Override
            public void onError(@NonNull Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    empty.setVisibility(View.VISIBLE);
                });
            }
        });
    }
}
