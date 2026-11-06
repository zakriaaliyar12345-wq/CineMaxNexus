package com.cinemax.nexus;

import android.app.Application;

import com.cinemax.nexus.data.db.AppDatabase;
import com.cinemax.nexus.data.prefs.PrefsManager;
import com.cinemax.nexus.job.StaleStateJobService;

public class NexusApplication extends Application {

    private static NexusApplication sInstance;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        registerActivityLifecycleCallbacks(new com.cinemax.nexus.player.MiniPlayerDock.Lifecycle());
        // Course topic 08.3 (JobScheduler): install the daily stale-row cleanup job.
        StaleStateJobService.scheduleIfNeeded(this);
    }

    public static NexusApplication getInstance() {
        return sInstance;
    }

    public AppDatabase getDatabase() {
        return AppDatabase.getInstance(this);
    }

    public PrefsManager getPrefs() {
        return PrefsManager.getInstance(this);
    }
}
