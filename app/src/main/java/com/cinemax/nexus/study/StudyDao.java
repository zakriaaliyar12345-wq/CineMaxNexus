package com.cinemax.nexus.study;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import java.util.List;

@Dao
public interface StudyDao {
    @Query("SELECT * FROM study_notes WHERE mediaKey = :key ORDER BY positionMs, createdAt")
    LiveData<List<StudyNote>> observe(String key);
    @Query("SELECT * FROM study_notes WHERE mediaKey = :key ORDER BY positionMs, createdAt")
    List<StudyNote> getSync(String key);
    @Insert(onConflict = OnConflictStrategy.REPLACE) long save(StudyNote note);
    @Query("DELETE FROM study_notes WHERE id = :id AND mediaKey = :key") void delete(long id, String key);
    @Query("UPDATE study_notes SET assetUri = :uri WHERE id = :id AND mediaKey = :key")
    void attachExport(long id, String key, String uri);
}
