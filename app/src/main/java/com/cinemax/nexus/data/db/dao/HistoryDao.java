package com.cinemax.nexus.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.cinemax.nexus.data.db.entity.HistoryEntity;

import java.util.List;

@Dao
public interface HistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(HistoryEntity entity);

    @Query("SELECT * FROM history ORDER BY last_played_at DESC LIMIT :limit")
    LiveData<List<HistoryEntity>> getRecent(int limit);

    @Query("SELECT * FROM history WHERE video_id = :videoId LIMIT 1")
    HistoryEntity getSync(String videoId);

    @Query("DELETE FROM history WHERE video_id = :videoId")
    void deleteByVideoId(String videoId);

    @Query("DELETE FROM history")
    void clearAll();

    @Query("SELECT * FROM history")
    List<HistoryEntity> getAllSync();
}
