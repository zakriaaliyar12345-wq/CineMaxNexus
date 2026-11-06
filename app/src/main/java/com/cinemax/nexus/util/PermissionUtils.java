package com.cinemax.nexus.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/** Centralizes runtime permission logic for media access across OS versions. */
public final class PermissionUtils {

    public static final int REQUEST_MEDIA = 1001;
    public static final int REQUEST_NOTIFICATIONS = 1002;
    public static final int REQUEST_TRIM_WRITE = 1003;

    private PermissionUtils() {
    }

    /**
     * Whether saving a trimmed clip needs a write grant at all. Android 10 and later accept
     * the clip straight through the MediaStore, which needs no permission whatsoever; the
     * releases below it write the file by hand and so do.
     */
    public static boolean needsTrimWritePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
    }

    public static boolean hasTrimWritePermission(@NonNull Context context) {
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void requestTrimWritePermission(@NonNull Activity activity) {
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQUEST_TRIM_WRITE);
    }

    /** The single runtime permission this app needs to list device videos. */
    @NonNull
    public static String mediaPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return Manifest.permission.READ_MEDIA_VIDEO;
        }
        return Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    public static boolean hasMediaPermission(@NonNull Context context) {
        return (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
                || ContextCompat.checkSelfPermission(context, mediaPermission())
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void requestMediaPermission(@NonNull Activity activity) {
        ActivityCompat.requestPermissions(activity,
                Build.VERSION.SDK_INT >= 34
                        ? new String[]{mediaPermission(), Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}
                        : new String[]{mediaPermission()}, REQUEST_MEDIA);
    }

    /** Needed on 13+ for the background-playback media notification to appear. */
    public static boolean hasNotificationPermission(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void requestNotificationPermission(@NonNull Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (hasNotificationPermission(activity)) return;
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATIONS);
    }

    public static boolean shouldShowRationale(@NonNull Activity activity) {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, mediaPermission());
    }
}
