package com.cinemax.nexus.study;

import android.content.Context;
import com.cinemax.nexus.subtitle.SubtitleTimeline;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Atomic private transcript snapshots, independent of external document grants. */
public final class TranscriptStore {
    private TranscriptStore() {}
    private static File file(Context context, String key) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte part : hash) name.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
        File folder = new File(context.getFilesDir(), "study-transcripts");
        if (!folder.exists() && !folder.mkdirs()) throw new IOException("Cannot create transcript folder");
        return new File(folder, name + ".srt");
    }
    public static void save(Context context, String key, String srt) throws Exception {
        SubtitleTimeline.parse(srt);
        File output = file(context, key);
        android.util.AtomicFile atomic = new android.util.AtomicFile(output);
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite(); stream.write(srt.getBytes(StandardCharsets.UTF_8)); atomic.finishWrite(stream);
        } catch (Exception error) { if (stream != null) atomic.failWrite(stream); throw error; }
    }
    public static SubtitleTimeline read(Context context, String key) throws Exception {
        File input = file(context, key);
        if (!input.exists()) return null;
        try (InputStream stream = new FileInputStream(input)) { return SubtitleTimeline.parse(readText(stream)); }
    }
    public static String readText(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = stream.read(buffer)) != -1) {
            if (bytes.size() + count > 2_000_000) throw new IOException("Transcript exceeds 2 MB");
            bytes.write(buffer, 0, count);
        }
        return bytes.toString(StandardCharsets.UTF_8.name());
    }
}
