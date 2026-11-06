package com.cinemax.nexus.data.db.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "video_state",
        indices = {@Index(value = "video_id", unique = true)}
)
public class VideoStateEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "video_id")
    public String videoId;

    @ColumnInfo(name = "position_ms")
    public long positionMs;

    @ColumnInfo(name = "duration_ms")
    public long durationMs;

    @ColumnInfo(name = "is_favorite")
    public boolean favorite;

    @ColumnInfo(name = "speed")
    public float speed = 1.0f;

    @ColumnInfo(name = "aspect_mode")
    public int aspectMode;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
