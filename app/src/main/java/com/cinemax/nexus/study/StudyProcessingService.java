package com.cinemax.nexus.study;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.MutableLiveData;
import com.cinemax.nexus.R;
import java.io.InputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One cancellable foreground job: model preparation or local speech indexing. */
public final class StudyProcessingService extends Service {
    public static final String INDEX = "study.index", DOWNLOAD = "study.model", IMPORT = "study.import", CANCEL = "study.cancel";
    public static final MutableLiveData<State> status = new MutableLiveData<>(new State("", false, 0, ""));
    public static final class State {
        public final String mediaKey, message; public final boolean busy; public final int percent;
        State(String key, boolean busy, int percent, String message) { this.mediaKey = key; this.busy = busy; this.percent = percent; this.message = message; }
    }
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean busy;
    private long lastUpdate;
    private String mediaKey = "";
    private Intent activityIntent;
    private static final int NOTIFICATION = 4610;
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (CANCEL.equals(intent.getAction())) { cancelled.set(true); if (!busy) stopSelf(); return START_NOT_STICKY; }
        if (busy) return START_NOT_STICKY;
        busy = true; cancelled.set(false); mediaKey = intent.getStringExtra("key");
        if (mediaKey == null) mediaKey = "";
        activityIntent = new Intent(this, StudyActivity.class).putExtras(intent).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        String action = intent.getAction();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel("study-processing", getString(R.string.study_processing), NotificationManager.IMPORTANCE_LOW));
        Notification initial = notification(getString(R.string.study_preparing), 0);
        if (Build.VERSION.SDK_INT >= 35) startForeground(NOTIFICATION, initial,
                INDEX.equals(action) ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION, initial);
        publish(getString(R.string.study_preparing), 0, true);
        worker.execute(() -> {
            String message;
            try {
                if (DOWNLOAD.equals(action)) {
                    SpeechModelStore.download(this, cancelled, (done, total) -> update(getString(R.string.study_model_downloading), total > 0 ? (int) (done * 100 / total) : 0));
                    message = getString(R.string.study_model_ready);
                } else if (IMPORT.equals(action)) {
                    Uri uri = intent.getData(); if (uri == null) throw new IllegalArgumentException("No model ZIP selected");
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        if (input == null) throw new IllegalArgumentException("Cannot read model ZIP");
                        SpeechModelStore.install(this, input, cancelled);
                    }
                    message = getString(R.string.study_model_ready);
                } else if (INDEX.equals(action)) {
                    Uri source = Uri.parse(intent.getStringExtra("source"));
                    String srt = SpeechIndexer.transcribe(this, source, cancelled, (position, duration) ->
                            update(getString(R.string.study_recognizing), duration > 0 ? (int) Math.min(99, position * 100 / duration) : 0));
                    if (cancelled.get()) throw new CancellationException();
                    TranscriptStore.save(this, mediaKey, srt);
                    message = getString(R.string.study_index_ready);
                } else throw new IllegalArgumentException("Unknown study job");
            } catch (CancellationException error) { message = getString(R.string.study_cancelled); }
            catch (Exception | LinkageError error) { message = getString(R.string.study_job_failed) + " " + String.valueOf(error.getMessage()); }
            busy = false; publish(message, 100, false);
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        });
        return START_NOT_STICKY;
    }
    private void update(String message, int progress) {
        long now = SystemClock.elapsedRealtime(); if (now - lastUpdate < 900) return;
        lastUpdate = now; publish(message, progress, true);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification(message, progress));
    }
    private void publish(String message, int progress, boolean active) { status.postValue(new State(mediaKey, active, progress, message)); }
    private Notification notification(String message, int progress) {
        PendingIntent open = PendingIntent.getActivity(this, 4611, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 4612, new Intent(this, StudyProcessingService.class).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, "study-processing").setSmallIcon(R.drawable.ic_subtitle)
                .setContentTitle(getString(R.string.study_workspace)).setContentText(message).setContentIntent(open)
                .setProgress(100, progress, progress == 0).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_close, getString(R.string.cancel), cancel).build();
    }
    @Override public void onDestroy() { cancelled.set(true); worker.shutdownNow(); super.onDestroy(); }
}
