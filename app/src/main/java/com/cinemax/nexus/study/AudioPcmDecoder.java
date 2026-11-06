package com.cinemax.nexus.study;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import java.io.*;
import java.nio.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Decodes a local media audio track without playing or recording microphone audio. */
public final class AudioPcmDecoder {
    public interface Sink { void accept(byte[] pcm, long positionMs, long durationMs) throws Exception; }
    private AudioPcmDecoder() {}
    public static void decode(Context context, Uri source, AtomicBoolean cancelled, Sink sink) throws Exception {
        if (!"content".equals(source.getScheme()) && !"file".equals(source.getScheme())) throw new IOException("Choose a local video or audio file");
        MediaExtractor extractor = new MediaExtractor(); MediaCodec codec = null;
        try {
            extractor.setDataSource(context, source, null);
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String type = candidate.getString(MediaFormat.KEY_MIME);
                if (type != null && type.startsWith("audio/")) { format = candidate; extractor.selectTrack(i); break; }
            }
            if (format == null) throw new IOException("This media has no audio track");
            long durationMs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) / 1000 : 0;
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0); codec.start();
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            ByteArrayOutputStream converted = new ByteArrayOutputStream(32768);
            PcmResampler resampler = resampler(rate, converted);
            boolean inputEnd = false, outputEnd = false;
            long lastOutput = android.os.SystemClock.elapsedRealtime();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputEnd) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
                if (!inputEnd) {
                    int input = codec.dequeueInputBuffer(10000);
                    if (input >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(input);
                        if (buffer == null) throw new IOException("No decoder input buffer");
                        int count = extractor.readSampleData(buffer, 0);
                        if (count < 0) { codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnd = true; }
                        else { codec.queueInputBuffer(input, 0, count, extractor.getSampleTime(), 0); extractor.advance(); }
                    }
                }
                int output = codec.dequeueOutputBuffer(info, 10000);
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat decoded = codec.getOutputFormat();
                    int updatedRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (updatedRate != rate) { rate = updatedRate; resampler = resampler(rate, converted); }
                    encoding = decoded.containsKey(MediaFormat.KEY_PCM_ENCODING) ? decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                } else if (output >= 0) {
                    lastOutput = android.os.SystemClock.elapsedRealtime();
                    ByteBuffer buffer = codec.getOutputBuffer(output);
                    if (buffer != null && info.size > 0) {
                        if (channels < 1 || channels > 8 || (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT)) throw new IOException("Unsupported decoder PCM format");
                        buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.order(ByteOrder.LITTLE_ENDIAN);
                        int bytes = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
                        while (buffer.remaining() >= channels * bytes) {
                            float mono = 0;
                            for (int channel = 0; channel < channels; channel++) mono += encoding == AudioFormat.ENCODING_PCM_FLOAT ? buffer.getFloat() : buffer.getShort() / 32768f;
                            resampler.accept(mono / channels);
                        }
                        sink.accept(converted.toByteArray(), Math.max(0, info.presentationTimeUs / 1000), durationMs);
                        converted.reset();
                    }
                    outputEnd = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(output, false);
                }
                if (android.os.SystemClock.elapsedRealtime() - lastOutput > 30000) throw new IOException("Audio decoder stalled");
            }
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} codec.release(); }
            extractor.release();
        }
    }
    private static PcmResampler resampler(int rate, ByteArrayOutputStream buffer) {
        return new PcmResampler(rate, value -> { buffer.write(value & 255); buffer.write((value >>> 8) & 255); });
    }
}
