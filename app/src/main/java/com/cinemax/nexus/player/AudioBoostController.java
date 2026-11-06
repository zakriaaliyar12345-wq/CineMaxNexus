package com.cinemax.nexus.player;

import android.media.audiofx.AudioEffect;
import android.media.audiofx.LoudnessEnhancer;

/** Owns one native effect. ExoPlayer volume alone is limited to 0..1. */
public final class AudioBoostController {
    private LoudnessEnhancer effect;
    private int sessionId;

    /** Returns false when this device cannot provide the requested amplification. */
    public boolean apply(int audioSessionId, float multiplier) {
        if (multiplier <= 1f) { release(); return true; }
        if (audioSessionId <= 0) return false;
        try {
            if (effect == null || sessionId != audioSessionId) {
                release();
                effect = new LoudnessEnhancer(audioSessionId);
                sessionId = audioSessionId;
            }
            // API expects millibels: 20 log10(amplitude) dB * 100.
            effect.setTargetGain((int) Math.round(2000 * Math.log10(Math.min(2f, multiplier))));
            return effect.setEnabled(true) == AudioEffect.SUCCESS && effect.getEnabled();
        } catch (RuntimeException unavailable) {
            release();
            return false;
        }
    }

    public void release() {
        if (effect != null) {
            try { effect.release(); } catch (RuntimeException ignored) { }
            effect = null;
        }
        sessionId = 0;
    }
}
