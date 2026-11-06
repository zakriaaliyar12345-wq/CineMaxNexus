package com.cinemax.nexus.study;

/** Streaming linear conversion to 16 kHz mono; state is preserved across codec buffers. */
public final class PcmResampler {
    public interface Sink { void sample(short value); }
    private final double step;
    private final Sink sink;
    private long index;
    private double next;
    private float previous;
    public PcmResampler(int inputRate, Sink sink) {
        if (inputRate < 8000 || inputRate > 192000) throw new IllegalArgumentException("Unsupported sample rate");
        step = inputRate / 16000.0; this.sink = sink;
    }
    public void accept(float value) {
        float current = Math.max(-1, Math.min(1, value));
        if (index == 0) previous = current;
        while (next <= index) {
            double fraction = Math.max(0, next - (index - 1));
            float interpolated = (float) (previous + (current - previous) * Math.min(1, fraction));
            sink.sample((short) Math.round(interpolated * 32767)); next += step;
        }
        previous = current; index++;
    }
}
