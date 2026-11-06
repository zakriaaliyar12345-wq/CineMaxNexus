package com.cinemax.nexus.data.db.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "bookmarks",
        indices = {@Index("video_id")}
)
public class BookmarkEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "video_id")
    public String videoId;

    @ColumnInfo(name = "title")
    public String title;

    @ColumnInfo(name = "position_ms")
    public long positionMs;

    @ColumnInfo(name = "created_at")
    public long createdAt;
}
