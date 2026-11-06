package com.cinemax.nexus.data.model;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

public class VideoItem implements Parcelable {

    public final long id;
    @NonNull public final String title;
    @NonNull public final Uri uri;
    public final long durationMs;
    public final long sizeBytes;
    public final long dateAddedSec;
    public final long dateModifiedSec;
    public final int width;
    public final int height;
    @Nullable public final String folderName;
    @Nullable public final String folderPath;
    @Nullable public final String mimeType;

    public VideoItem(long id, @NonNull String title, @NonNull Uri uri,
                     long durationMs, long sizeBytes,
                     long dateAddedSec, long dateModifiedSec,
                     int width, int height,
                     @Nullable String folderName, @Nullable String folderPath,
                     @Nullable String mimeType) {
        this.id = id;
        this.title = title;
        this.uri = uri;
        this.durationMs = durationMs;
        this.sizeBytes = sizeBytes;
        this.dateAddedSec = dateAddedSec;
        this.dateModifiedSec = dateModifiedSec;
        this.width = width;
        this.height = height;
        this.folderName = folderName;
        this.folderPath = folderPath;
        this.mimeType = mimeType;
    }

    protected VideoItem(Parcel in) {
        id = in.readLong();
        title = in.readString();
        uri = in.readParcelable(Uri.class.getClassLoader());
        durationMs = in.readLong();
        sizeBytes = in.readLong();
        dateAddedSec = in.readLong();
        dateModifiedSec = in.readLong();
        width = in.readInt();
        height = in.readInt();
        folderName = in.readString();
        folderPath = in.readString();
        mimeType = in.readString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeString(title);
        dest.writeParcelable(uri, flags);
        dest.writeLong(durationMs);
        dest.writeLong(sizeBytes);
        dest.writeLong(dateAddedSec);
        dest.writeLong(dateModifiedSec);
        dest.writeInt(width);
        dest.writeInt(height);
        dest.writeString(folderName);
        dest.writeString(folderPath);
        dest.writeString(mimeType);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<VideoItem> CREATOR = new Creator<VideoItem>() {
        @Override
        public VideoItem createFromParcel(Parcel in) {
            return new VideoItem(in);
        }

        @Override
        public VideoItem[] newArray(int size) {
            return new VideoItem[size];
        }
    };

    /** Stable identifier string used for history / favorites / state keys. */
    @NonNull
    public String key() {
        return Long.toString(id);
    }

    public boolean hasResolution() {
        return width > 0 && height > 0;
    }

    public boolean isShort() {
        return durationMs > 0 && durationMs < 60_000L;
    }

    public boolean isLong() {
        return durationMs >= 3_600_000L;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VideoItem)) return false;
        VideoItem that = (VideoItem) o;
        return id == that.id && Objects.equals(uri, that.uri);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, uri);
    }

    @NonNull
    @Override
    public String toString() {
        return "VideoItem{id=" + id + ", title='" + title + "'}";
    }
}
