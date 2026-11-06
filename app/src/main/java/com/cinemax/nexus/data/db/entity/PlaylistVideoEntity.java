package com.cinemax.nexus.data.db.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "playlist_videos",
        foreignKeys = @ForeignKey(
                entity = PlaylistEntity.class,
                parentColumns = "id",
                childColumns = "playlist_id",
                onDelete = ForeignKey.CASCADE
        ),
        indices = {@Index("playlist_id")}
)
public class PlaylistVideoEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "playlist_id")
    public long playlistId;

    @ColumnInfo(name = "video_id")
    public String videoId;

    @ColumnInfo(name = "title")
    public String title;

    @ColumnInfo(name = "uri")
    public String uri;

    @ColumnInfo(name = "duration_ms")
    public long durationMs;

    @ColumnInfo(name = "position_in_playlist")
    public int positionInPlaylist;
}
