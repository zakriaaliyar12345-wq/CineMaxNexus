package com.cinemax.nexus.study;

import android.content.Context;
import androidx.room.*;

/** Independent schema: introducing study tools does not alter the existing media database. */
@Database(entities = {StudyNote.class}, version = 1, exportSchema = false)
public abstract class StudyDatabase extends RoomDatabase {
    public abstract StudyDao notes();
    private static volatile StudyDatabase instance;
    public static StudyDatabase get(Context context) {
        if (instance == null) synchronized (StudyDatabase.class) {
            if (instance == null) instance = Room.databaseBuilder(context.getApplicationContext(),
                    StudyDatabase.class, "study.db").build();
        }
        return instance;
    }
}
