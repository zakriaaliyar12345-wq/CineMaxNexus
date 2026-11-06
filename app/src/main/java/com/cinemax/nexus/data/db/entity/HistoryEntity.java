package com.cinemax.nexus.data.db.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "history")
public class HistoryEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "video_id")
    public String videoId;

    @ColumnInfo(name = "title")
    public String title;

    @ColumnInfo(name = "uri")
    public String uri;

    @ColumnInfo(name = "duration_ms")
    public long durationMs;

    @ColumnInfo(name = "position_ms")
    public long positionMs;

    @ColumnInfo(name = "last_played_at")
    public long lastPlayedAt;

    @ColumnInfo(name = "watched_ms")
    public long watchedMs;
}
