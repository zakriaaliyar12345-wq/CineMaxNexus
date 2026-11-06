package com.cinemax.nexus.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.cinemax.nexus.data.db.entity.BookmarkEntity;

import java.util.List;

@Dao
public interface BookmarkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(BookmarkEntity entity);

    @Query("SELECT * FROM bookmarks WHERE video_id = :videoId ORDER BY position_ms ASC")
    LiveData<List<BookmarkEntity>> getForVideo(String videoId);

    @Query("SELECT * FROM bookmarks WHERE video_id = :videoId ORDER BY position_ms ASC")
    List<BookmarkEntity> getForVideoSync(String videoId);

    @Query("DELETE FROM bookmarks WHERE id = :id")
    void delete(long id);

    @Query("DELETE FROM bookmarks WHERE video_id = :videoId")
    void deleteForVideo(String videoId);
}
