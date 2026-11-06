package com.cinemax.nexus.study;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import androidx.core.content.FileProvider;
import java.io.*;

public final class StudyAssets {
    private StudyAssets() {}
    public static Uri capture(Context context, Uri source, long positionMs) throws Exception {
        MediaMetadataRetriever reader = new MediaMetadataRetriever(); Bitmap frame = null;
        File folder = new File(context.getFilesDir(), "study-assets");
        if (!folder.exists() && !folder.mkdirs()) throw new IOException("Cannot create snapshot folder");
        File output = new File(folder, java.util.UUID.randomUUID() + ".png");
        try {
            reader.setDataSource(context, source);
            if (Build.VERSION.SDK_INT >= 27) frame = reader.getScaledFrameAtTime(positionMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST, 960, 540);
            else frame = reader.getFrameAtTime(positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST);
            if (frame == null) throw new IOException("No readable video frame");
            try (OutputStream stream = new FileOutputStream(output)) { if (!frame.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("Cannot encode snapshot"); }
            return FileProvider.getUriForFile(context, context.getPackageName() + ".studyfiles", output);
        } catch (Exception error) { output.delete(); throw error; }
        finally { if (frame != null) frame.recycle(); try { reader.release(); } catch (Exception ignored) {} }
    }
    public static void delete(Context context, String address) throws IOException {
        if (address == null) return;
        Uri uri = Uri.parse(address);
        if (!(context.getPackageName() + ".studyfiles").equals(uri.getAuthority())) return;
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[a-fA-F0-9-]+\\.png")) return;
        File folder = new File(context.getFilesDir(), "study-assets"), file = new File(folder, name);
        if (!file.getCanonicalFile().getParentFile().equals(folder.getCanonicalFile())) return;
        if (file.exists() && !file.delete()) throw new IOException("Cannot delete snapshot");
    }
}
