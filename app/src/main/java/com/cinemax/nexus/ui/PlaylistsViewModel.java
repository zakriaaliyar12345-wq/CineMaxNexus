package com.cinemax.nexus.ui;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.cinemax.nexus.data.MediaRepository;
import com.cinemax.nexus.data.db.entity.PlaylistEntity;

import java.util.List;

/**
 * Course topic 10.1 (ViewModel): owns the playlists LiveData so it survives this
 * screen's recreation (rotation, dark-mode change, locale switch) instead of
 * re-querying Room every time {@code onCreate} runs. The Activity only observes —
 * it never stores the data itself.
 */
public class PlaylistsViewModel extends AndroidViewModel {

    private final LiveData<List<PlaylistEntity>> mPlaylists;

    public PlaylistsViewModel(@NonNull Application application) {
        super(application);
        mPlaylists = MediaRepository.getInstance(application).playlistsLive();
    }

    @NonNull
    public LiveData<List<PlaylistEntity>> getPlaylists() {
        return mPlaylists;
    }
}
