package com.cinemax.nexus.study;

import android.content.Context;
import android.net.Uri;
import androidx.core.content.FileProvider;
import com.cinemax.nexus.subtitle.SubtitleTimeline;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** A portable revision bundle: playable MP4, optional sidecar SRT, notes and snapshots. */
public final class StudyExports {
    private StudyExports() {}
    public static String markdown(String title, List<StudyNote> notes, long offset) {
        StringBuilder out = new StringBuilder("# ").append(title).append("\n\n");
        for (StudyNote note : notes) out.append("## ").append(StudyText.timestamp(Math.max(0, note.positionMs - offset)))
                .append(" — ").append(note.label).append("\n\n").append(note.body).append("\n\n");
        return out.toString();
    }
    public static Uri bundle(Context context, StudyNote clip, Uri video, long actualStart,
                             SubtitleTimeline transcript, List<StudyNote> notes) throws Exception {
        File folder = new File(context.getCacheDir(), "study-export");
        if (!folder.exists() && !folder.mkdirs()) throw new IOException("Cannot create export folder");
        File output = new File(folder, StudyText.safeName(clip.label) + "-" + System.currentTimeMillis() + ".zip");
        List<StudyNote> selected = new ArrayList<>();
        for (StudyNote note : notes) if (note.positionMs >= actualStart && note.positionMs <= clip.endMs) selected.add(note);
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
            copy(context, zip, "clip.mp4", video);
            if (transcript != null) {
                String captions = transcript.toSrtRange(actualStart, clip.endMs);
                if (!captions.isEmpty()) text(zip, "captions.srt", captions);
            }
            text(zip, "notes.md", markdown(clip.sourceTitle, selected, actualStart));
            text(zip, "manifest.json", new JSONObject().put("title", clip.label).put("requestedStartMs", clip.positionMs)
                    .put("actualStartMs", actualStart).put("endMs", clip.endMs)
                    .put("captions", "Optional sidecar SRT; not burned into video").toString(2));
            for (StudyNote note : selected) if ("snapshot".equals(note.kind) && note.assetUri != null
                    && (context.getPackageName() + ".studyfiles").equals(Uri.parse(note.assetUri).getAuthority()))
                copy(context, zip, "screenshots/" + note.id + ".png", Uri.parse(note.assetUri));
        } catch (Exception error) { output.delete(); throw error; }
        return FileProvider.getUriForFile(context, context.getPackageName() + ".studyfiles", output);
    }
    private static void text(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
    private static void copy(Context context, ZipOutputStream zip, String name, Uri uri) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Cannot read export asset");
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count);
        }
        zip.closeEntry();
    }
}
