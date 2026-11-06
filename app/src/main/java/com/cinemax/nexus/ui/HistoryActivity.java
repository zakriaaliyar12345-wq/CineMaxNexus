package com.cinemax.nexus.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cinemax.nexus.R;
import com.cinemax.nexus.data.db.entity.HistoryEntity;
import com.cinemax.nexus.player.PlayerActivity;
import com.cinemax.nexus.util.FormatUtils;

import java.util.ArrayList;
import java.util.List;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class HistoryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.action_history);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView empty = findViewById(R.id.empty_text);
        empty.setText(R.string.history_empty);
        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        HistoryAdapter adapter = new HistoryAdapter();
        recycler.setAdapter(adapter);

        // Course topic 10.1 (ViewModel): query owned by HistoryViewModel, observable
        // across configuration changes.
        new ViewModelProvider(this).get(HistoryViewModel.class)
                .getHistory()
                .observe(this, new Observer<List<HistoryEntity>>() {
                    @Override
                    public void onChanged(List<HistoryEntity> items) {
                        adapter.submit(items);
                        empty.setVisibility(items == null || items.isEmpty()
                                ? View.VISIBLE : View.GONE);
                    }
                });
    }

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {
        private final List<HistoryEntity> mItems = new ArrayList<>();

        void submit(@NonNull List<HistoryEntity> items) {
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
            HistoryEntity e = mItems.get(position);
            holder.name.setText(e.title);
            holder.count.setText(FormatUtils.formatDuration(e.positionMs)
                    + " / " + FormatUtils.formatDuration(e.durationMs));
            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(HistoryActivity.this, PlayerActivity.class);
                intent.setData(Uri.parse(e.uri));
                intent.putExtra(PlayerActivity.EXTRA_TITLE, e.title);
                intent.putExtra(PlayerActivity.EXTRA_VIDEO_ID, e.videoId);
                intent.putExtra(PlayerActivity.EXTRA_DURATION, e.durationMs);
                intent.putExtra(PlayerActivity.EXTRA_START_POSITION, e.positionMs);
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
