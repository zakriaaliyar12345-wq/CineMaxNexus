package com.cinemax.nexus.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Cuts a new clip out of an existing video file.
 *
 * <p>This deliberately uses {@link MediaExtractor} plus {@link MediaMuxer} from the platform
 * rather than Media3's Transformer. The samples are copied across untouched, so there is no
 * decode/re-encode round trip: the trim is close to instant, the output is bit-identical to
 * the source inside the range, and the app gains no extra dependency. The one honest
 * consequence is that a cut lands on a key frame, so it may be a fraction of a second earlier
 * than the requested start.
 */
public final class VideoTrimmer {

    /** Smallest clip worth writing out; anything shorter is almost certainly a slip of the thumb. */
    private static final long MIN_RANGE_US = 500_000L;

    private static final int BUFFER_SIZE = 1024 * 1024;

    public interface Callback {
        void onFinished(@NonNull Uri output, boolean needsScan);

        void onFailed(@NonNull Exception error);
    }

    private VideoTrimmer() { }

    /** Matches trimSync's selected tracks and sync-frame seek for caption rebasing. */
    public static long actualStartMs(Context context, Uri source, long requestedMs) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, source, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) extractor.selectTrack(i);
            }
            extractor.seekTo(requestedMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long first = extractor.getSampleTime();
            if (first < 0) throw new IOException("No samples in the selected range");
            return first / 1000;
        } finally { extractor.release(); }
    }

    public static boolean isSupportedRange(long startUs, long endUs) {
        return endUs - startUs >= MIN_RANGE_US;
    }

    /** Runs the trim on a background thread and reports back on it. */
    public static void trim(@NonNull Context context, @NonNull Uri source,
                            long startUs, long endUs, @NonNull Callback callback) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                Result result = trimSync(app, source, startUs, endUs);
                callback.onFinished(result.uri, result.needsScan);
            } catch (Exception e) {
                callback.onFailed(e);
            }
        }, "cinemax-trim").start();
    }

    private static final class Result {
        final Uri uri;
        final boolean needsScan;

        Result(Uri uri, boolean needsScan) {
            this.uri = uri;
            this.needsScan = needsScan;
        }
    }

    @NonNull
    private static Result trimSync(@NonNull Context context, @NonNull Uri source,
                                   long startUs, long endUs) throws IOException {
        boolean useMediaStore = Build.VERSION.SDK_INT >= 29;
        ContentResolver resolver = context.getContentResolver();

        Uri output = null;
        File legacyFile = null;
        ParcelFileDescriptor pfd = null;
        MediaMuxer muxer = null;
        MediaExtractor extractor = null;

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(context, source, null);

            int trackCount = extractor.getTrackCount();
            if (trackCount == 0) throw new IOException("no tracks");

            // Both the picture and the sound track survive the cut. A file with neither is
            // not a video and has no business reaching this code.
            //
            // Selecting is not optional: an extractor with no track selected hands back no
            // samples at all, which quietly produces an empty-but-well-formed file.
            boolean[] keep = new boolean[trackCount];
            int keptTracks = 0;
            for (int t = 0; t < trackCount; t++) {
                String type = extractor.getTrackFormat(t)
                        .getString(MediaFormat.KEY_MIME);
                keep[t] = type != null
                        && (type.startsWith("video/") || type.startsWith("audio/"));
                if (keep[t]) {
                    extractor.selectTrack(t);
                    keptTracks++;
                }
            }
            if (keptTracks == 0) throw new IOException("no audio or video track");

            if (useMediaStore) {
                output = createPendingClip(context, resolver);
                pfd = resolver.openFileDescriptor(output, "w", null);
                if (pfd == null) throw new IOException("could not open output");
                muxer = new MediaMuxer(pfd.getFileDescriptor(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } else {
                legacyFile = createLegacyClip();
                muxer = new MediaMuxer(legacyFile.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }

            int orientation = readRotation(context, source);
            if (orientation != 0) muxer.setOrientationHint(orientation);

            int[] muxerIndex = new int[trackCount];
            boolean[] finished = new boolean[trackCount];
            for (int t = 0; t < trackCount; t++) {
                if (keep[t]) {
                    muxerIndex[t] = muxer.addTrack(extractor.getTrackFormat(t));
                } else {
                    muxerIndex[t] = -1;
                    finished[t] = true;
                }
            }
            muxer.start();

            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long offsetUs = -1;
            int remaining = keptTracks;

            // MediaExtractor hands back samples in presentation order, so seeking to the
            // previous sync frame and walking forward gives a well-formed, decodable file.
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            while (remaining > 0) {
                int track = extractor.getSampleTrackIndex();
                if (track < 0) break;                       // ran out of source data
                if (finished[track]) {
                    extractor.advance();
                    continue;
                }

                long pts = extractor.getSampleTime();
                if (pts > endUs) {
                    // This track is past the cut. Let the others run to their own end so
                    // the two streams stay in step at the tail of the clip.
                    finished[track] = true;
                    remaining--;
                    extractor.advance();
                    continue;
                }

                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    finished[track] = true;
                    remaining--;
                    continue;
                }
                if (offsetUs < 0) offsetUs = pts;           // first sample becomes t=0

                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = Math.max(0L, pts - offsetUs);
                // Never flag an EOS on a truncated clip: the muxer writes its own tail, and
                // a key frame flagged as EOS makes some decoders drop the rest of the file.
                info.flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;

                muxer.writeSampleData(muxerIndex[track], buffer, info);
                extractor.advance();
            }

            muxer.stop();
            muxer.release();
            muxer = null;

            if (useMediaStore) {
                resolver.update(output, pendingDoneValues(), null, null);
                return new Result(output, false);
            }
            MediaScannerConnection.scanFile(context, new String[]{legacyFile.getAbsolutePath()},
                    new String[]{"video/mp4"}, null);
            return new Result(Uri.fromFile(legacyFile), true);
        } catch (IOException | RuntimeException e) {
            // A malformed or protected source surfaces here rather than as IOException.
            if (useMediaStore && output != null) {
                try {
                    resolver.delete(output, null, null);
                } catch (Exception ignored) { }
            } else if (legacyFile != null && legacyFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                legacyFile.delete();
            }
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e.getMessage(), e);
        } finally {
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Exception ignored) { }
                muxer.release();
            }
            if (extractor != null) extractor.release();
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (IOException ignored) { }
            }
        }
    }

    /** Reserves the collection row before a single byte is written, so the file is invisible. */
    @NonNull
    private static Uri createPendingClip(@NonNull Context context,
                                         @NonNull ContentResolver resolver) throws IOException {
        String name = "CineMax_" + System.currentTimeMillis() + ".mp4";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/CineMaxNexus");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("could not create clip in Movies");
        return uri;
    }

    @NonNull
    private static ContentValues pendingDoneValues() {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.IS_PENDING, 0);
        return values;
    }

    /** Pre-Android 10 has no pending-row support and no scoped storage to hide behind. */
    @NonNull
    private static File createLegacyClip() throws IOException {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), "CineMaxNexus");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("could not create " + dir);
        }
        return new File(dir, "CineMax_" + System.currentTimeMillis() + ".mp4");
    }

    /**
     * Rotation lives in the container metadata rather than in the sample data, so it has to
     * be re-applied to the new file by hand or portrait clips come out sideways.
     */
    private static int readRotation(@NonNull Context context, @NonNull Uri source) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, source);
            String value = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            return value == null ? 0 : Integer.parseInt(value);
        } catch (Exception e) {
            return 0;                       // missing metadata must not fail the export
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) { }
        }
    }

    /** Opens a clip in the system player once it exists. */
    @NonNull
    public static Intent viewIntent(@NonNull Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "video/mp4");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }
}
