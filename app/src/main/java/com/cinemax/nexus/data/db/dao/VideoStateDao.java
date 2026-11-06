package com.cinemax.nexus.data.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.cinemax.nexus.data.db.entity.VideoStateEntity;

import java.util.List;

@Dao
public interface VideoStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(VideoStateEntity entity);

    @Query("SELECT * FROM video_state WHERE video_id = :videoId LIMIT 1")
    VideoStateEntity getSync(String videoId);

    @Query("DELETE FROM video_state WHERE video_id = :videoId")
    void delete(String videoId);

    @Query("SELECT * FROM video_state")
    List<VideoStateEntity> getAllSync();
}
