package com.cinemax.nexus.data.prefs;

import android.content.Context;
import android.content.SharedPreferences;

public class PrefsManager {

    private static final String PREFS = "nexus_prefs";
    private static volatile PrefsManager sInstance;

    private final SharedPreferences mPrefs;

    private PrefsManager(Context context) {
        mPrefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static PrefsManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (PrefsManager.class) {
                if (sInstance == null) {
                    sInstance = new PrefsManager(context);
                }
            }
        }
        return sInstance;
    }

    public String getLibrarySort() {
        String value = mPrefs.getString("library_sort", "DATE_ADDED");
        return java.util.Arrays.asList("DATE_ADDED", "TITLE", "DURATION", "SIZE").contains(value)
                ? value : "DATE_ADDED";
    }

    public boolean isLibraryAscending() { return mPrefs.getBoolean("library_ascending", false); }

    public void setLibrarySort(String mode, boolean ascending) {
        mPrefs.edit().putString("library_sort", mode)
                .putBoolean("library_ascending", ascending).apply();
    }

    public float getSpeed() {
        return mPrefs.getFloat("speed", 1.0f);
    }

    public void setSpeed(float speed) {
        mPrefs.edit().putFloat("speed", speed).apply();
    }

    public int getAspectRatioMode() {
        return mPrefs.getInt("aspect_ratio_mode", 0);
    }

    public void setAspectRatioMode(int mode) {
        mPrefs.edit().putInt("aspect_ratio_mode", mode).apply();
    }

    public boolean isResumeEnabled() {
        return mPrefs.getBoolean("resume_enabled", true);
    }

    public void setResumeEnabled(boolean enabled) {
        mPrefs.edit().putBoolean("resume_enabled", enabled).apply();
    }

    public long getSeekIntervalShort() {
        return mPrefs.getLong("seek_interval_short", 10_000L);
    }

    public void setSeekIntervalShort(long ms) {
        mPrefs.edit().putLong("seek_interval_short", ms).apply();
    }

    public long getSeekIntervalLong() {
        return mPrefs.getLong("seek_interval_long", 30_000L);
    }

    public void setSeekIntervalLong(long ms) {
        mPrefs.edit().putLong("seek_interval_long", ms).apply();
    }

    public boolean isDarkTheme() {
        return mPrefs.getBoolean("dark_theme", true);
    }

    public void setDarkTheme(boolean dark) {
        mPrefs.edit().putBoolean("dark_theme", dark).apply();
    }

    /**
     * Which screen half the vertical volume swipe uses (MX Player's "volume gesture side").
     * When true (default) volume is on the right half and brightness on the left; false
     * swaps them.
     */
    public boolean isVolumeOnRight() {
        return mPrefs.getBoolean("volume_on_right", true);
    }

    public void setVolumeOnRight(boolean right) {
        mPrefs.edit().putBoolean("volume_on_right", right).apply();
    }

    public boolean isGesturesEnabled() {
        return mPrefs.getBoolean("gestures_enabled", true);
    }

    public void setGesturesEnabled(boolean enabled) {
        mPrefs.edit().putBoolean("gestures_enabled", enabled).apply();
    }

    public float getBrightness() {
        return mPrefs.getFloat("brightness", -1f);
    }

    public void setBrightness(float b) {
        mPrefs.edit().putFloat("brightness", b).apply();
    }

    /** Equalizer preset index chosen by the user, or -1 when the equalizer is off. */
    public int getEqPreset() {
        return mPrefs.getInt("eq_preset", -1);
    }

    public void setEqPreset(int preset) {
        mPrefs.edit().putInt("eq_preset", preset).apply();
    }

    // ---- Queue transport ----

    /** 0 = off, 1 = repeat the whole queue, 2 = repeat one video. */
    public int getRepeatMode() {
        return mPrefs.getInt("repeat_mode", 0);
    }

    public void setRepeatMode(int mode) {
        mPrefs.edit().putInt("repeat_mode", mode).apply();
    }

    public boolean isShuffle() {
        return mPrefs.getBoolean("shuffle", false);
    }

    public void setShuffle(boolean on) {
        mPrefs.edit().putBoolean("shuffle", on).apply();
    }

    // ---- Library ----

    /** Grid columns: 4 = compact, 3 = medium, 2 = large. */
    public int getGridSpan() {
        return mPrefs.getInt("grid_span", 2);
    }

    public void setGridSpan(int span) {
        mPrefs.edit().putInt("grid_span", span).apply();
    }

    // ---- Subtitles ----

    /**
     * Vertical offset of the subtitle block as a fraction of the screen height.
     * 0 keeps Media3's own placement; positive moves subtitles up.
     */
    public float getSubtitleOffset() {
        return mPrefs.getFloat("subtitle_offset", 0f);
    }

    public void setSubtitleOffset(float offset) {
        mPrefs.edit().putFloat("subtitle_offset", offset).apply();
    }

    // ---- Private folder ----

    /** 4-digit PIN guarding the private folder; empty until the user sets one. */
    public String getPrivatePin() {
        return mPrefs.getString("private_pin", "");
    }

    public void setPrivatePin(String pin) {
        mPrefs.edit().putString("private_pin", pin).apply();
    }

    /**
     * MediaStore ids the user chose to hide. Stored in preferences rather than Room on
     * purpose: a schema change would force a migration on data that already exists, and a
     * handful of ids needs nothing heavier than a string set.
     */
    public java.util.Set<String> getPrivateIds() {
        return new java.util.HashSet<>(mPrefs.getStringSet("private_ids",
                java.util.Collections.<String>emptySet()));
    }

    public void setPrivateIds(java.util.Set<String> ids) {
        mPrefs.edit().putStringSet("private_ids", new java.util.HashSet<>(ids)).apply();
    }

    // ---- App lock ----

    /**
     * Separate 4-digit PIN that guards the whole app, not just the private folder.
     * Empty until the viewer turns the lock on.
     */
    public String getAppPin() {
        return mPrefs.getString("app_pin", "");
    }

    public void setAppPin(String pin) {
        mPrefs.edit().putString("app_pin", pin).apply();
    }

    // ---- Library filter ----

    /** Whether the library should hide videos the viewer already watched to the end. */
    public boolean isHideWatchedEnabled() {
        return mPrefs.getBoolean("hide_watched", false);
    }

    public void setHideWatchedEnabled(boolean enabled) {
        mPrefs.edit().putBoolean("hide_watched", enabled).apply();
    }
}
