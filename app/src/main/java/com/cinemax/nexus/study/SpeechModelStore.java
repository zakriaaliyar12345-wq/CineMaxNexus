package com.cinemax.nexus.study;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

/** Installs the small English offline model from its publisher or a chosen ZIP. */
public final class SpeechModelStore {
    public static final String MODEL_NAME = "vosk-model-small-en-us-0.15";
    public static final String MODEL_URL = "https://alphacephei.com/vosk/models/" + MODEL_NAME + ".zip";
    public static final String MODEL_SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498";
    public static final long MODEL_BYTES = 41205931L;
    public interface Progress { void update(long done, long total); }
    private SpeechModelStore() {}
    public static File model(Context context) { return new File(context.getFilesDir(), "speech-models/" + MODEL_NAME); }
    public static boolean ready(Context context) {
        File folder = model(context);
        return new File(folder, "am/final.mdl").isFile() && new File(folder, "conf/model.conf").isFile()
                && new File(folder, "graph/HCLr.fst").isFile();
    }
    public static void download(Context context, AtomicBoolean cancelled, Progress progress) throws Exception {
        File zip = new File(context.getCacheDir(), "speech-model.zip.part");
        Exception failure = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            check(cancelled);
            long offset = zip.isFile() ? zip.length() : 0;
            if (offset == MODEL_BYTES) break;
            HttpURLConnection connection = (HttpURLConnection) new URL(MODEL_URL).openConnection();
            connection.setConnectTimeout(20000); connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            try {
                int response = connection.getResponseCode();
                if (response == 200) offset = 0;
                else if (response != 206 || connection.getHeaderField("Content-Range") == null
                        || !connection.getHeaderField("Content-Range").startsWith("bytes " + offset + "-"))
                    throw new IOException("Model server returned " + response);
                try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(zip, offset > 0)) {
                    byte[] buffer = new byte[65536]; int count; long done = offset;
                    while ((count = input.read(buffer)) != -1) {
                        check(cancelled); done += count;
                        if (done > MODEL_BYTES) throw new IOException("Unexpected model archive size");
                        output.write(buffer, 0, count); progress.update(done, MODEL_BYTES);
                    }
                }
                if (zip.length() == MODEL_BYTES) break;
                throw new IOException("Model transfer was interrupted");
            } catch (IOException error) { failure = error; }
            finally { connection.disconnect(); }
        }
        if (zip.length() != MODEL_BYTES) throw failure == null ? new IOException("Model download incomplete. Retry to resume.") : failure;
        try (InputStream input = new FileInputStream(zip)) { install(context, input, cancelled); }
        finally { zip.delete(); }
    }
    public static void install(Context context, InputStream source, AtomicBoolean cancelled) throws Exception {
        File verified = new File(context.getCacheDir(), "verified-model-" + java.util.UUID.randomUUID() + ".zip");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); long size = 0;
            try (OutputStream output = new FileOutputStream(verified)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = source.read(buffer)) != -1) {
                    check(cancelled); size += count;
                    if (size > MODEL_BYTES) throw new IOException("Choose the official small English model ZIP");
                    digest.update(buffer, 0, count); output.write(buffer, 0, count);
                }
            }
            StringBuilder checksum = new StringBuilder();
            for (byte part : digest.digest()) checksum.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
            if (size != MODEL_BYTES || !MODEL_SHA256.equals(checksum.toString())) throw new IOException("The model ZIP failed its checksum check");
            try (InputStream input = new FileInputStream(verified)) { extract(context, input, cancelled); }
        } finally { verified.delete(); }
    }
    private static void extract(Context context, InputStream source, AtomicBoolean cancelled) throws Exception {
        File parent = new File(context.getFilesDir(), "speech-models");
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create model folder");
        File temporary = new File(parent, ".install-" + java.util.UUID.randomUUID());
        if (!temporary.mkdir()) throw new IOException("Cannot stage model");
        boolean installed = false;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            ZipEntry entry; long expanded = 0; int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                check(cancelled);
                if (++entries > 10000) throw new IOException("Too many archive entries");
                String name = entry.getName();
                if (!name.startsWith(MODEL_NAME + "/") || name.contains("\\")) throw new IOException("Choose the official small English model ZIP");
                File output = new File(temporary, name);
                if (!output.getCanonicalPath().startsWith(temporary.getCanonicalPath() + File.separator)) throw new IOException("Invalid archive path");
                if (entry.isDirectory()) { if (!output.mkdirs() && !output.isDirectory()) throw new IOException("Cannot create model directory"); continue; }
                File folder = output.getParentFile(); if (!folder.exists() && !folder.mkdirs()) throw new IOException("Cannot create model directory");
                try (OutputStream stream = new FileOutputStream(output)) {
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = zip.read(buffer)) != -1) {
                        check(cancelled); expanded += count;
                        if (expanded > 300_000_000) throw new IOException("Expanded model is too large");
                        stream.write(buffer, 0, count);
                    }
                }
            }
            File staged = new File(temporary, MODEL_NAME);
            if (!new File(staged, "am/final.mdl").isFile() || !new File(staged, "graph/HCLr.fst").isFile()) throw new IOException("Incomplete model ZIP");
            // Validate native loading before replacing an existing usable model.
            try (org.vosk.Model ignored = new org.vosk.Model(staged.getAbsolutePath())) {}
            File destination = model(context);
            if (destination.exists()) deleteOwned(parent, destination);
            if (!staged.renameTo(destination)) throw new IOException("Cannot install model");
            installed = true;
        } finally { deleteOwned(parent, temporary); }
    }
    private static void check(AtomicBoolean cancelled) { if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException(); }
    private static void deleteOwned(File parent, File target) throws IOException {
        if (!target.getCanonicalPath().startsWith(parent.getCanonicalPath() + File.separator)) throw new IOException("Invalid cleanup path");
        File[] children = target.listFiles(); if (children != null) for (File child : children) deleteOwned(parent, child);
        if (target.exists() && !target.delete()) throw new IOException("Cannot clean model staging files");
    }
}
