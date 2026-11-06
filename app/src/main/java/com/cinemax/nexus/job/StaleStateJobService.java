package com.cinemax.nexus.job;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.cinemax.nexus.data.MediaRepository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Course topic 08.3 (JobScheduler): periodic housekeeping that only runs when the app
 * is not in the foreground. Deletes resume-state and history rows whose video file has
 * been removed from the device, so the database stays a true mirror of the library.
 *
 * <p>The job is scheduled once from {@code NexusApplication.onCreate}; Android keeps it
 * across reboots of the process, and re-scheduling is skipped while a copy is pending.
 * Testable from adb:
 * {@code adb shell jobscheduler run -f com.cinemax.nexus 21315}
 */
public class StaleStateJobService extends JobService {

    public static final int JOB_ID = 21315;
    private static final String TAG = "StaleStateJob";

    /** One short-lived task is all this needs; kept as a field so onStopJob can cancel. */
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private java.util.concurrent.Future<?> mTask;

    @Override
    public boolean onStartJob(final JobParameters params) {
        mTask = mExecutor.submit(() -> {
            int removed = 0;
            boolean retry = false;
            try {
                removed = MediaRepository.getInstance(StaleStateJobService.this)
                        .pruneStaleMedia();
            } catch (RuntimeException error) {
                // Permissions or storage can change after this daily job was scheduled.
                // Keep the app alive and let JobScheduler retry when it can read again.
                retry = true;
                Log.e(TAG, "Could not prune stale media", error);
            } finally {
                Log.d(TAG, "pruned " + removed + " stale row(s)");
                if (!Thread.currentThread().isInterrupted()) jobFinished(params, retry);
            }
        });
        return true; // work runs on the executor thread, not the main one
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (mTask != null) mTask.cancel(true);
        return true; // reschedule — the prune is idempotent
    }

    @Override
    public void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    /** Install the daily job if nothing is pending yet. Safe to call on every launch. */
    public static void scheduleIfNeeded(@NonNull Context context) {
        JobScheduler js = (JobScheduler) context.getSystemService(JOB_SCHEDULER_SERVICE);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, StaleStateJobService.class))
                .setPeriodic(24L * 60L * 60L * 1000L)
                .build();
        js.schedule(job);
    }
}
