package com.cinemax.nexus.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.cinemax.nexus.data.db.entity.PlaylistEntity;
import com.cinemax.nexus.data.db.entity.PlaylistVideoEntity;

import java.util.List;

@Dao
public interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertPlaylist(PlaylistEntity entity);

    @Query("SELECT * FROM playlists ORDER BY created_at DESC")
    LiveData<List<PlaylistEntity>> getAllPlaylists();

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    PlaylistEntity getPlaylistSync(long id);

    @Query("DELETE FROM playlists WHERE id = :id")
    void deletePlaylist(long id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertVideo(PlaylistVideoEntity entity);

    @Query("SELECT * FROM playlist_videos WHERE playlist_id = :playlistId ORDER BY position_in_playlist ASC")
    LiveData<List<PlaylistVideoEntity>> getVideosForPlaylist(long playlistId);

    @Query("SELECT * FROM playlist_videos WHERE playlist_id = :playlistId ORDER BY position_in_playlist ASC")
    List<PlaylistVideoEntity> getVideosForPlaylistSync(long playlistId);

    @Query("DELETE FROM playlist_videos WHERE id = :videoId")
    void removeVideoFromPlaylist(long videoId);

    @Query("UPDATE playlist_videos SET position_in_playlist = :pos WHERE id = :rowId")
    void updatePosition(long rowId, int pos);

    @Query("DELETE FROM playlist_videos WHERE playlist_id = :playlistId")
    void clearPlaylist(long playlistId);
}
