package com.cinemax.nexus.music;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import com.cinemax.nexus.R;
import java.util.*;

/** Blocking provider query; callers must use a worker executor. */
public final class AudioLibrary {
    private AudioLibrary() {}

    public static List<AudioTrack> query(Context context) {
        List<AudioTrack> result = new ArrayList<>();
        String[] columns = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID};
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns,
                MediaStore.Audio.Media.DURATION + " > 0", null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
            if (cursor == null) throw new IllegalStateException("Audio provider unavailable");
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0), albumId = cursor.getLong(5);
                result.add(new AudioTrack("audio:" + id,
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                        label(cursor.getString(1), context.getString(R.string.untitled_audio)),
                        label(cursor.getString(2), context.getString(R.string.unknown_artist)),
                        label(cursor.getString(3), context.getString(R.string.unknown_album)),
                        cursor.getLong(4), albumId > 0
                        ? ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId) : null));
            }
        }
        return result;
    }

    private static String label(String value, String fallback) {
        return value == null || value.trim().isEmpty() || "<unknown>".equals(value) ? fallback : value;
    }
}
