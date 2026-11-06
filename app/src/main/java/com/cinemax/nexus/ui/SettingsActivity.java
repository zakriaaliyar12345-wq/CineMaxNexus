package com.cinemax.nexus.ui;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.os.LocaleListCompat;

import com.cinemax.nexus.R;
import com.cinemax.nexus.data.MediaRepository;
import com.cinemax.nexus.data.ThumbnailLoader;
import com.cinemax.nexus.data.prefs.PrefsManager;
import com.cinemax.nexus.util.FormatUtils;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * Global preferences screen. Values are written straight into {@link PrefsManager},
 * which the player reads on launch, so changes apply to the next playback session.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final float[] SPEEDS = {
            0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f, 4.0f
    };

    private static final long[] SHORT_STEPS = {5_000L, 10_000L, 15_000L, 30_000L};
    private static final long[] LONG_STEPS = {15_000L, 30_000L, 45_000L, 60_000L};

    private PrefsManager mPrefs;

    private TextView mValueSeekShort;
    private TextView mValueSeekLong;
    private TextView mValueVolumeSide;
    private TextView mValueSpeed;
    private TextView mValueAspect;
    private TextView mValueLanguage;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mPrefs = PrefsManager.getInstance(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.action_settings);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        bindSwitches();
        bindValueRows();
        bindLanguageRow();
        bindLibraryRows();
        bindAbout();
    }

    /**
     * In-app language picker. {@link AppCompatDelegate#setApplicationLocales} is supported
     * back to API 24 by appcompat, so this works on devices that have no system-level
     * per-app language screen (Android 7-12). The choice is stored by appcompat itself.
     */
    private void bindLanguageRow() {
        mValueLanguage = findViewById(R.id.value_language);
        mValueLanguage.setText(labelForLocale(AppCompatDelegate.getApplicationLocales()));

        findViewById(R.id.row_language).setOnClickListener(v -> {
            String[] labels = {
                    getString(R.string.settings_language_system),
                    getString(R.string.settings_language_en),
                    getString(R.string.settings_language_fa)
            };
            LocaleListCompat current = AppCompatDelegate.getApplicationLocales();
            int checked = 0;
            if (!current.isEmpty()) {
                String tag = current.toLanguageTags();
                if (tag.startsWith("en")) checked = 1;
                else if (tag.startsWith("fa")) checked = 2;
            }

            new AlertDialog.Builder(this)
                    .setTitle(R.string.settings_language_title)
                    .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                        LocaleListCompat next = which == 1
                                ? LocaleListCompat.forLanguageTags("en-US")
                                : which == 2
                                ? LocaleListCompat.forLanguageTags("fa-AF")
                                : LocaleListCompat.getEmptyLocaleList();
                        dialog.dismiss();
                        if (!next.equals(AppCompatDelegate.getApplicationLocales())) {
                            // Activity recreates itself once the locale is applied.
                            AppCompatDelegate.setApplicationLocales(next);
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
    }

    private String labelForLocale(@NonNull LocaleListCompat locales) {
        if (locales.isEmpty()) return getString(R.string.settings_language_system);
        String tag = locales.toLanguageTags();
        if (tag.startsWith("fa")) return getString(R.string.settings_language_fa);
        if (tag.startsWith("en")) return getString(R.string.settings_language_en);
        return tag;
    }

    private void bindSwitches() {
        SwitchMaterial resume = findViewById(R.id.switch_resume);
        resume.setChecked(mPrefs.isResumeEnabled());
        resume.setOnCheckedChangeListener((b, checked) -> mPrefs.setResumeEnabled(checked));
        findViewById(R.id.row_resume).setOnClickListener(v -> resume.toggle());

        SwitchMaterial gestures = findViewById(R.id.switch_gestures);
        gestures.setChecked(mPrefs.isGesturesEnabled());
        gestures.setOnCheckedChangeListener((b, checked) -> mPrefs.setGesturesEnabled(checked));
        findViewById(R.id.row_gestures).setOnClickListener(v -> gestures.toggle());
    }

    private void bindValueRows() {
        mValueSeekShort = findViewById(R.id.value_seek_short);
        mValueSeekLong = findViewById(R.id.value_seek_long);
        mValueVolumeSide = findViewById(R.id.value_volume_side);
        mValueSpeed = findViewById(R.id.value_speed);
        mValueAspect = findViewById(R.id.value_aspect);

        // MX Player's "volume gesture side": pick which screen half drives volume,
        // the other half keeps brightness. Read live by PlayerActivity per gesture.
        findViewById(R.id.row_volume_side).setOnClickListener(v -> {
            String[] labels = {
                    getString(R.string.settings_volume_side_right),
                    getString(R.string.settings_volume_side_left)
            };
            pickLabel(getString(R.string.settings_volume_side_title), labels,
                    mPrefs.isVolumeOnRight() ? 0 : 1, i -> {
                        mPrefs.setVolumeOnRight(i == 0);
                        refreshValues();
                    });
        });

        findViewById(R.id.row_seek_short).setOnClickListener(v ->
                pickIndex(getString(R.string.settings_seek_short_title),
                        SHORT_STEPS,
                        indexOfLong(SHORT_STEPS, mPrefs.getSeekIntervalShort()),
                        i -> {
                            mPrefs.setSeekIntervalShort(SHORT_STEPS[i]);
                            refreshValues();
                        }));

        findViewById(R.id.row_seek_long).setOnClickListener(v ->
                pickIndex(getString(R.string.settings_seek_long_title),
                        LONG_STEPS,
                        indexOfLong(LONG_STEPS, mPrefs.getSeekIntervalLong()),
                        i -> {
                            mPrefs.setSeekIntervalLong(LONG_STEPS[i]);
                            refreshValues();
                        }));

        findViewById(R.id.row_speed).setOnClickListener(v -> {
            String[] labels = new String[SPEEDS.length];
            for (int i = 0; i < SPEEDS.length; i++) {
                labels[i] = FormatUtils.formatSpeed(SPEEDS[i]);
            }
            pickLabel(getString(R.string.settings_speed_title), labels,
                    indexOfFloat(SPEEDS, mPrefs.getSpeed()), i -> {
                        mPrefs.setSpeed(SPEEDS[i]);
                        refreshValues();
                    });
        });

        findViewById(R.id.row_aspect).setOnClickListener(v -> {
            String[] labels = {
                    getString(R.string.aspect_fit),
                    getString(R.string.aspect_fill),
                    getString(R.string.aspect_stretch)
            };
            int current = mPrefs.getAspectRatioMode();
            pickLabel(getString(R.string.settings_aspect_title), labels,
                    current >= 0 && current < labels.length ? current : 0, i -> {
                        mPrefs.setAspectRatioMode(i);
                        refreshValues();
                    });
        });

        refreshValues();
    }

    private void bindLibraryRows() {
        findViewById(R.id.row_clear_history).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle(R.string.settings_clear_history)
                        .setPositiveButton(R.string.delete, (d, w) -> {
                            MediaRepository.getInstance(this).clearHistory();
                            Toast.makeText(this, R.string.history_cleared,
                                    Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show());

        findViewById(R.id.row_clear_thumbs).setOnClickListener(v -> {
            ThumbnailLoader.getInstance(this).clearCache();
            Toast.makeText(this, R.string.thumbs_cleared, Toast.LENGTH_SHORT).show();
        });
    }

    private void bindAbout() {
        TextView version = findViewById(R.id.value_version);
        try {
            String v = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            version.setText(getString(R.string.about_version, v));
        } catch (Exception ignored) {
            version.setText("");
        }
    }

    private void refreshValues() {
        mValueSeekShort.setText(FormatUtils.formatDuration(mPrefs.getSeekIntervalShort()));
        mValueSeekLong.setText(FormatUtils.formatDuration(mPrefs.getSeekIntervalLong()));
        mValueVolumeSide.setText(mPrefs.isVolumeOnRight()
                ? R.string.settings_volume_side_right : R.string.settings_volume_side_left);
        mValueSpeed.setText(FormatUtils.formatSpeed(mPrefs.getSpeed()));

        int mode = mPrefs.getAspectRatioMode();
        switch (mode) {
            case 1:
                mValueAspect.setText(R.string.aspect_fill);
                break;
            case 2:
                mValueAspect.setText(R.string.aspect_stretch);
                break;
            case 0:
            default:
                mValueAspect.setText(R.string.aspect_fit);
                break;
        }
    }

    private interface OnIndexPicked {
        void onPicked(int index);
    }

    private void pickIndex(@NonNull String title, @NonNull long[] values,
                           int checkedIndex, @NonNull OnIndexPicked cb) {
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = FormatUtils.formatDuration(values[i]);
        }
        pickLabel(title, labels, checkedIndex, cb);
    }

    private void pickLabel(@NonNull String title, @NonNull String[] labels,
                           int checkedIndex, @NonNull OnIndexPicked cb) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels,
                        Math.max(0, Math.min(checkedIndex, labels.length - 1)),
                        (dialog, which) -> {
                            cb.onPicked(which);
                            dialog.dismiss();
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static int indexOfLong(long[] values, long target) {
        int best = 0;
        long bestDelta = Long.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            long delta = Math.abs(values[i] - target);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = i;
            }
        }
        return best;
    }

    private static int indexOfFloat(float[] values, float target) {
        int best = 0;
        float bestDelta = Float.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            float delta = Math.abs(values[i] - target);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = i;
            }
        }
        return best;
    }
}
