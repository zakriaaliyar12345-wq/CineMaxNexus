package com.cinemax.nexus.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cinemax.nexus.R;
import com.cinemax.nexus.adapter.VideoAdapter;
import com.cinemax.nexus.data.MediaRepository;
import com.cinemax.nexus.data.ThumbnailLoader;
import com.cinemax.nexus.data.model.VideoItem;
import com.cinemax.nexus.data.prefs.PrefsManager;
import com.cinemax.nexus.player.PlayerActivity;
import com.cinemax.nexus.util.PrivateFolder;

import java.util.ArrayList;
import java.util.List;

/**
 * The viewer's hidden shelf.
 *
 * <p>Nothing here touches the underlying files. The videos stay in MediaStore exactly where
 * they were; this screen is the only place they are listed, and the grid above no longer
 * shows them. That is a deliberate trade - relocating or encrypting someone's personal
 * videos is the kind of operation that can lose them, and no amount of feature parity is
 * worth that risk - so the limitation is stated in the empty state instead of being glossed.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PrivateActivity extends AppCompatActivity {

    private VideoAdapter mAdapter;
    private TextView mEmpty;
    private PrefsManager mPrefs;
    private boolean mUnlocked;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        mPrefs = PrefsManager.getInstance(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.private_folder);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        mEmpty = findViewById(R.id.empty_text);
        mEmpty.setText(R.string.private_empty);

        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new GridLayoutManager(this, mPrefs.getGridSpan()));
        mAdapter = new VideoAdapter(ThumbnailLoader.getInstance(this), true);
        mAdapter.setOnVideoClickListener(new VideoAdapter.OnVideoClickListener() {
            @Override
            public void onVideoClick(@NonNull VideoItem item, int position) {
                openPlayer(item);
            }

            @Override
            public boolean onVideoLongClick(@NonNull VideoItem item, int position) {
                confirmUnhide(item);
                return true;
            }
        });
        recycler.setAdapter(mAdapter);

        // The folder is only worth having if it is shut. Asking on entry rather than on each
        // tap means one PIN covers the whole visit.
        PrivateFolder.unlock(this, passed -> {
            if (passed) {
                mUnlocked = true;
                loadPrivate();
            } else {
                Toast.makeText(this, R.string.private_unlock_failed,
                        Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private void loadPrivate() {
        MediaRepository.getInstance(this).loadVideos(new MediaRepository.LoadCallback() {
            @Override
            public void onLoaded(@NonNull List<VideoItem> videos) {
                List<VideoItem> hidden = new ArrayList<>();
                for (VideoItem item : videos) {
                    if (PrivateFolder.isPrivate(PrivateActivity.this, item.key())) {
                        hidden.add(item);
                    }
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    mAdapter.submit(hidden);
                    mEmpty.setText(hidden.isEmpty()
                            ? R.string.private_empty : R.string.private_note);
                    mEmpty.setVisibility(hidden.isEmpty() ? View.VISIBLE : View.GONE);
                });
            }

            @Override
            public void onError(@NonNull Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(PrivateActivity.this, R.string.load_failed,
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void confirmUnhide(@NonNull final VideoItem item) {
        new AlertDialog.Builder(this)
                .setTitle(item.title)
                .setMessage(R.string.private_note)
                .setPositiveButton(R.string.remove_from_private, (d, w) -> {
                    PrivateFolder.setPrivate(this, item.key(), false);
                    Toast.makeText(this,
                            getString(R.string.removed_from_private, item.title),
                            Toast.LENGTH_SHORT).show();
                    loadPrivate();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openPlayer(@NonNull VideoItem item) {
        if (!mUnlocked) return;
        Intent intent = new Intent(this, PlayerActivity.class);
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
    protected void onResume() {
        super.onResume();
        // A video can be moved out of the folder from the main grid while this screen is
        // behind it, so the list is rebuilt whenever we come back.
        if (mUnlocked) loadPrivate();
    }
}
