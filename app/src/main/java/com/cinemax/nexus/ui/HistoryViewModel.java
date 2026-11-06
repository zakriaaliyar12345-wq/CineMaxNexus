package com.cinemax.nexus.ui;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.cinemax.nexus.data.MediaRepository;
import com.cinemax.nexus.data.db.entity.HistoryEntity;

import java.util.List;

/**
 * Course topic 10.1 (ViewModel): holds the history query (latest 200 rows) in a
 * lifecycle-aware owner. The list keeps its instance across configuration changes,
 * so returning to the screen shows data immediately without a fresh DB round-trip.
 */
public class HistoryViewModel extends AndroidViewModel {

    private static final int HISTORY_LIMIT = 200;

    private final LiveData<List<HistoryEntity>> mHistory;

    public HistoryViewModel(@NonNull Application application) {
        super(application);
        mHistory = MediaRepository.getInstance(application).historyLive(HISTORY_LIMIT);
    }

    @NonNull
    public LiveData<List<HistoryEntity>> getHistory() {
        return mHistory;
    }
}
