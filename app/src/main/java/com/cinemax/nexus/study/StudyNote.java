package com.cinemax.nexus.study;

import androidx.annotation.NonNull;
import androidx.room.*;

@Entity(tableName = "study_notes", indices = {@Index("mediaKey")})
public class StudyNote {
    @PrimaryKey(autoGenerate = true) public long id;
    @NonNull public String mediaKey = "";
    @NonNull public String sourceUri = "";
    @NonNull public String sourceTitle = "";
    @NonNull public String kind = "note";
    @NonNull public String label = "";
    @NonNull public String body = "";
    public String assetUri;
    public long positionMs;
    public long endMs;
    public long createdAt;
}
