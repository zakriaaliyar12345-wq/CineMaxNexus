package com.cinemax.nexus.util;

import android.app.Activity;
import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.cinemax.nexus.R;
import com.cinemax.nexus.data.model.VideoItem;
import com.cinemax.nexus.data.prefs.PrefsManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The PIN gate in front of the private folder.
 *
 * <p>Honest about what it is: a lock on this app's lists, not on the files. The videos stay
 * where they are on disk and any other app with media access can still see them, which is
 * why the UI says so out loud instead of letting the viewer assume more than is true.
 */
public final class PrivateFolder {

    private static final int PIN_LENGTH = 4;

    public interface Callback {
        /** @param passed true when the PIN was entered correctly. */
        void onResult(boolean passed);
    }

    private PrivateFolder() { }

    public static boolean hasPin(@NonNull Context context) {
        return !PrefsManager.getInstance(context).getPrivatePin().isEmpty();
    }

    public static boolean isPrivate(@NonNull Context context, @NonNull String key) {
        return PrefsManager.getInstance(context).getPrivateIds().contains(key);
    }

    /** Moves a video into or out of the private folder. */
    public static void setPrivate(@NonNull Context context, @NonNull String key, boolean hide) {
        PrefsManager prefs = PrefsManager.getInstance(context);
        Set<String> ids = prefs.getPrivateIds();
        if (hide) ids.add(key);
        else ids.remove(key);
        prefs.setPrivateIds(ids);
    }

    /**
     * Asks the viewer to pick the four digits that will guard the folder from now on.
     * The callback fires with false if the dialog is dismissed without a choice.
     */
    public static void createPin(@NonNull Activity activity, @NonNull Callback callback) {
        pinDialog(activity, activity.getString(R.string.private_set_pin), null, null,
                pin -> {
                    PrefsManager.getInstance(activity).setPrivatePin(pin);
                    callback.onResult(true);
                },
                () -> callback.onResult(false));
    }

    /** Asks for the PIN and reports whether the entry was the right one. */
    public static void unlock(@NonNull Activity activity, @NonNull Callback callback) {
        pinDialog(activity, activity.getString(R.string.private_enter_pin),
                null,
                activity.getString(R.string.private_wrong_pin),
                pin -> {
                    boolean ok = pin.equals(
                            PrefsManager.getInstance(activity).getPrivatePin());
                    callback.onResult(ok);
                },
                () -> callback.onResult(false));
    }

    // ---- App lock (a second PIN over the whole app) ----

    public static boolean hasAppPin(@NonNull Context context) {
        return !PrefsManager.getInstance(context).getAppPin().isEmpty();
    }

    public static void setAppPin(@NonNull Context context, @NonNull String pin) {
        PrefsManager.getInstance(context).setAppPin(pin);
    }

    public static void clearAppPin(@NonNull Context context) {
        PrefsManager.getInstance(context).setAppPin("");
    }

    /** Same four-box prompt as the folder lock, pointed at the app-level PIN. */
    public static void createAppPin(@NonNull Activity activity, @NonNull Callback callback) {
        // The "not encrypted / asked on start" disclosure belongs where the user
        // actually consents to enabling the lock; the message and the input field
        // share the material dialog layout, unlike message + list items.
        pinDialog(activity, activity.getString(R.string.app_lock_create),
                activity.getString(R.string.app_lock_note),
                activity.getString(R.string.app_lock_wrong),
                pin -> {
                    setAppPin(activity, pin);
                    callback.onResult(true);
                },
                () -> callback.onResult(false));
    }

    public static void unlockApp(@NonNull Activity activity, @NonNull Callback callback) {
        pinDialog(activity, activity.getString(R.string.app_lock_enter),
                null,
                activity.getString(R.string.app_lock_wrong),
                pin -> {
                    boolean ok = pin.equals(
                            PrefsManager.getInstance(activity).getAppPin());
                    callback.onResult(ok);
                },
                () -> callback.onResult(false));
    }

    /**
     * A four-box PIN pad would look nicer, but a single numeric field is one control, needs
     * no new layout, and is what the system's own credential prompts do. Dismissing without
     * entering anything counts as "no", never as "yes".
     */
    private static void pinDialog(@NonNull Activity activity, @NonNull String title,
                                  @Nullable String note,
                                  @Nullable String errorText,
                                  @NonNull java.util.function.Consumer<String> onSubmit,
                                  @NonNull Runnable onCancel) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(PIN_LENGTH)});
        input.setGravity(android.view.Gravity.CENTER);
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(input)
                .setPositiveButton(R.string.done, null)
                .setNegativeButton(R.string.cancel, (d, w) -> onCancel.run())
                .setOnCancelListener(d -> onCancel.run());
        if (note != null) builder.setMessage(note);
        AlertDialog dialog = builder.create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    Editable text = input.getText();
                    String value = text == null ? "" : text.toString();
                    if (value.length() != PIN_LENGTH) {
                        input.setError(errorText != null ? errorText
                                : activity.getString(R.string.private_set_pin));
                        return;
                    }
                    onSubmit.accept(value);
                    dialog.dismiss();
                }));

        // The box refuses to submit anything that is not a full PIN, so a half-entered
        // number can never become the lock.
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                input.setError(null);
            }

            @Override public void afterTextChanged(Editable s) { }
        });

        dialog.show();
    }

    /** Convenience for screens that just need the current hidden set as a copy. */
    @NonNull
    public static Set<String> hiddenKeys(@NonNull Context context) {
        return new HashSet<>(PrefsManager.getInstance(context).getPrivateIds());
    }

    /**
     * Removes every hidden video from a list.
     *
     * <p>Shared by the grid, the folder views, the resume rail and the playback queue, so
     * the private folder cannot be walked around by opening a folder instead. Returns the
     * same list when nothing is hidden, which is the common case and saves a copy.
     */
    @NonNull
    public static List<VideoItem> visibleOnly(@NonNull Context context,
                                              @NonNull List<VideoItem> source) {
        Set<String> ids = PrefsManager.getInstance(context).getPrivateIds();
        if (ids.isEmpty()) return source;
        List<VideoItem> out = new ArrayList<>(source.size());
        for (VideoItem item : source) {
            if (!ids.contains(item.key())) out.add(item);
        }
        return out;
    }
}
