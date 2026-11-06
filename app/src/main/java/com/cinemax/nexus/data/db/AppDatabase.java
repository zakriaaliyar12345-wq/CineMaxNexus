package com.cinemax.nexus.data.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.cinemax.nexus.data.db.dao.BookmarkDao;
import com.cinemax.nexus.data.db.dao.HistoryDao;
import com.cinemax.nexus.data.db.dao.PlaylistDao;
import com.cinemax.nexus.data.db.dao.VideoStateDao;
import com.cinemax.nexus.data.db.entity.BookmarkEntity;
import com.cinemax.nexus.data.db.entity.HistoryEntity;
import com.cinemax.nexus.data.db.entity.PlaylistEntity;
import com.cinemax.nexus.data.db.entity.PlaylistVideoEntity;
import com.cinemax.nexus.data.db.entity.VideoStateEntity;

@Database(
        entities = {
                HistoryEntity.class,
                PlaylistEntity.class,
                PlaylistVideoEntity.class,
                BookmarkEntity.class,
                VideoStateEntity.class
        },
        version = 1,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase sInstance;

    public abstract HistoryDao historyDao();
    public abstract PlaylistDao playlistDao();
    public abstract BookmarkDao bookmarkDao();
    public abstract VideoStateDao videoStateDao();

    public static AppDatabase getInstance(Context context) {
        if (sInstance == null) {
            synchronized (AppDatabase.class) {
                if (sInstance == null) {
                    sInstance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "nexus.db"
                            )
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return sInstance;
    }
}
