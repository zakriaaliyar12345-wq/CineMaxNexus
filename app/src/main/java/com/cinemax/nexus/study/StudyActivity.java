package com.cinemax.nexus.study;

import android.content.*;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.appcompat.app.*;
import androidx.core.content.ContextCompat;
import androidx.core.view.*;
import androidx.recyclerview.widget.*;
import com.cinemax.nexus.R;
import com.cinemax.nexus.subtitle.SubtitleTimeline;
import com.cinemax.nexus.ui.StudioSheet;
import com.cinemax.nexus.util.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** A durable per-video notebook and searchable, timestamped speech/subtitle workspace. */
public final class StudyActivity extends AppCompatActivity {
    private static final int IMPORT_TEXT = 4621, IMPORT_MODEL = 4622, SAVE_BUNDLE = 4623;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<StudyNote> notes = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private StudyDao dao;
    private Uri source, pendingBundle;
    private String key, title, query = "", section = "transcript";
    private long position, duration;
    private volatile boolean destroyed;
    private boolean exporting;
    private SubtitleTimeline transcript;
    private NotesAdapter adapter;
    private StudyNote pendingClip;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_study);
        key = getIntent().getStringExtra("key"); title = getIntent().getStringExtra("title");
        String address = getIntent().getStringExtra("source");
        if (key == null || address == null) { finish(); return; }
        source = Uri.parse(address); if (title == null) title = getString(R.string.study_workspace);
        position = getIntent().getLongExtra("position", 0); duration = getIntent().getLongExtra("duration", 0);
        if (state != null) { query = state.getString("query", ""); section = state.getString("section", "transcript"); String bundle = state.getString("bundle"); if (bundle != null) pendingBundle = Uri.parse(bundle); }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.study_root), (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        ((TextView) findViewById(R.id.study_video_title)).setText(title);
        ((TextView) findViewById(R.id.study_at)).setText(getString(R.string.study_at_time, StudyText.timestamp(position)));
        findViewById(R.id.study_back).setOnClickListener(v -> finish());
        findViewById(R.id.study_add_note).setOnClickListener(v -> editNote(null, position, ""));
        findViewById(R.id.study_add_clip).setOnClickListener(v -> editClip(null, position, Math.min(duration > 0 ? duration : position + 30000, position + 30000)));
        findViewById(R.id.study_snapshot).setOnClickListener(v -> capture());
        findViewById(R.id.study_import).setOnClickListener(v -> pick(IMPORT_TEXT, "*/*"));
        findViewById(R.id.study_index).setOnClickListener(v -> indexSpeech());
        findViewById(R.id.study_cancel_job).setOnClickListener(v -> startService(new Intent(this, StudyProcessingService.class).setAction(StudyProcessingService.CANCEL)));
        findViewById(R.id.study_use_captions).setOnClickListener(v -> { if (transcript != null) { setResult(RESULT_OK, new Intent().putExtra("study_action", "captions")); finish(); } });
        findViewById(R.id.study_export_notes).setOnClickListener(v -> exportNotes());
        int[] tabs = {R.id.study_tab_transcript, R.id.study_tab_notes, R.id.study_tab_clips, R.id.study_tab_shots};
        String[] modes = {"transcript", "note", "clip", "snapshot"};
        for (int i = 0; i < tabs.length; i++) { final String mode = modes[i]; findViewById(tabs[i]).setOnClickListener(v -> { section = mode; render(); }); }
        EditText search = findViewById(R.id.study_search); search.setText(query);
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            public void onTextChanged(CharSequence text, int start, int before, int count) { query = text.toString(); render(); }
            public void afterTextChanged(android.text.Editable value) {}
        });
        adapter = new NotesAdapter(); RecyclerView list = findViewById(R.id.study_list);
        list.setLayoutManager(new LinearLayoutManager(this)); list.setAdapter(adapter);
        dao = StudyDatabase.get(this).notes();
        dao.observe(key).observe(this, values -> { notes.clear(); if (values != null) notes.addAll(values); render(); });
        StudyProcessingService.status.observe(this, stateValue -> {
            if (stateValue == null) return;
            findViewById(R.id.study_cancel_job).setVisibility(stateValue.busy ? View.VISIBLE : View.GONE);
            findViewById(R.id.study_index).setEnabled(!stateValue.busy);
            TextView status = findViewById(R.id.study_job_status);
            if (key.equals(stateValue.mediaKey) && !stateValue.message.isEmpty())
                status.setText(stateValue.busy ? getString(R.string.study_progress, stateValue.message, stateValue.percent) : stateValue.message);
            else status.setText(stateValue.busy ? R.string.study_other_job : R.string.study_ready);
            if (!stateValue.busy && key.equals(stateValue.mediaKey)) loadTranscript();
        });
        loadTranscript();
    }
    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putString("query", query); state.putString("section", section); if (pendingBundle != null) state.putString("bundle", pendingBundle.toString()); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() { destroyed = true; worker.shutdown(); super.onDestroy(); }
    private void work(Runnable action) { if (!destroyed) try { worker.execute(action); } catch (RejectedExecutionException ignored) {} }
    private void ui(Runnable action) { runOnUiThread(() -> { if (!destroyed) action.run(); }); }
    private void error(Exception error) { ui(() -> Toast.makeText(this, getString(R.string.study_error) + " " + error.getMessage(), Toast.LENGTH_LONG).show()); }
    private void loadTranscript() {
        work(() -> {
            try {
                SubtitleTimeline loaded = TranscriptStore.read(this, key);
                if (loaded == null) {
                    android.content.SharedPreferences prefs = getSharedPreferences("subtitle_studio", 0);
                    String address = prefs.getString(key + ":uri:0", null);
                    if (address != null) try (InputStream input = getContentResolver().openInputStream(Uri.parse(address))) {
                        if (input != null) { loaded = SubtitleTimeline.parse(TranscriptStore.readText(input)); TranscriptStore.save(this, key, loaded.toSrt(prefs.getLong(key + ":delay:0", 0))); loaded = TranscriptStore.read(this, key); }
                    }
                }
                final SubtitleTimeline result = loaded; ui(() -> { transcript = result; render(); });
            } catch (Exception failure) { ui(() -> { ((TextView) findViewById(R.id.study_job_status)).setText(R.string.study_import_unavailable); render(); }); }
        });
    }
    private StudyNote note(String kind, long start, long end, String label, String body) {
        StudyNote item = new StudyNote(); item.mediaKey = key; item.sourceUri = source.toString(); item.sourceTitle = title;
        item.kind = kind; item.positionMs = start; item.endMs = end; item.label = label; item.body = body; item.createdAt = System.currentTimeMillis(); return item;
    }
    private EditText field(LinearLayout box, int hint, String value, boolean multiline) {
        EditText input = new EditText(this); input.setHint(hint); input.setText(value);
        input.setSingleLine(!multiline); if (multiline) { input.setMinLines(3); input.setMaxLines(6); input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE); }
        box.addView(input); return input;
    }
    private void editNote(StudyNote existing, long at, String seed) {
        LinearLayout box = StudioSheet.column(this);
        EditText label = field(box, R.string.study_note_title, existing == null ? "" : existing.label, false);
        EditText body = field(box, R.string.study_note_body, existing == null ? seed : existing.body, true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(getString(R.string.study_note_at, StudyText.timestamp(at)))
                .setView(box).setPositiveButton(R.string.save, null).setNegativeButton(R.string.cancel, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = body.getText().toString().trim(), heading = label.getText().toString().trim();
            if (text.isEmpty() && heading.isEmpty()) { body.setError(getString(R.string.study_note_required)); return; }
            if (text.length() > 20000 || heading.length() > 200) { body.setError(getString(R.string.study_text_too_long)); return; }
            StudyNote item = note("note", at, 0, heading.isEmpty() ? getString(R.string.study_note_default) : heading, text);
            if (existing != null) { item.id = existing.id; item.createdAt = existing.createdAt; }
            work(() -> dao.save(item)); section = "note"; render(); dialog.dismiss();
        })); dialog.show();
    }
    private void editClip(StudyNote existing, long start, long end) {
        LinearLayout box = StudioSheet.column(this);
        EditText label = field(box, R.string.study_clip_name, existing == null ? "" : existing.label, false);
        EditText from = field(box, R.string.trim_start, StudyText.timestamp(start), false);
        EditText to = field(box, R.string.trim_end, StudyText.timestamp(end), false);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.study_save_range).setView(box)
                .setPositiveButton(R.string.save, null).setNegativeButton(R.string.cancel, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                long a = PlaybackTime.parseMillis(from.getText().toString()), b = PlaybackTime.parseMillis(to.getText().toString());
                if (!StudyText.validRange(a, b, duration)) throw new IllegalArgumentException(getString(R.string.study_bad_range));
                String name = label.getText().toString().trim(); if (name.isEmpty()) name = getString(R.string.study_clip_default);
                StudyNote item = note("clip", a, b, name, ""); if (existing != null) { item.id = existing.id; item.createdAt = existing.createdAt; }
                work(() -> dao.save(item)); section = "clip"; render(); dialog.dismiss();
            } catch (IllegalArgumentException failure) { to.setError(failure.getMessage()); }
        })); dialog.show();
    }
    private void capture() {
        ((TextView) findViewById(R.id.study_job_status)).setText(R.string.study_capturing);
        work(() -> {
            try { Uri image = StudyAssets.capture(this, source, position); StudyNote item = note("snapshot", position, 0,
                    getString(R.string.study_snapshot_at, StudyText.timestamp(position)), ""); item.assetUri = image.toString(); dao.save(item); ui(() -> { section = "snapshot"; render(); ((TextView) findViewById(R.id.study_job_status)).setText(R.string.study_saved); }); }
            catch (Exception failure) { error(failure); }
        });
    }
    private void render() {
        if (adapter == null) return;
        rows.clear();
        if ("transcript".equals(section)) {
            if (transcript != null) for (SubtitleTimeline.Entry cue : transcript.search(query)) rows.add(new Row(cue));
        } else for (StudyNote item : notes) if (section.equals(item.kind) && StudyText.matches(query, item.label, item.body)) rows.add(new Row(item));
        int[] tabs = {R.id.study_tab_transcript, R.id.study_tab_notes, R.id.study_tab_clips, R.id.study_tab_shots}; String[] types = {"transcript", "note", "clip", "snapshot"};
        for (int i = 0; i < tabs.length; i++) findViewById(tabs[i]).setSelected(section.equals(types[i]));
        findViewById(R.id.study_use_captions).setEnabled(transcript != null);
        findViewById(R.id.study_empty).setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.study_empty)).setText("transcript".equals(section) && transcript == null ? R.string.study_no_transcript : R.string.study_no_results);
        ((TextView) findViewById(R.id.study_summary)).setText(getString(R.string.study_summary, transcript == null ? 0 : transcript.entries.size(), notes.size()));
        adapter.notifyDataSetChanged();
    }
    private void jump(long start, long end) {
        setResult(RESULT_OK, new Intent().putExtra("study_action", end > start ? "loop" : "seek").putExtra("start", start).putExtra("end", end)); finish();
    }
    private void actions(Row row) {
        if (row.cue != null) {
            new AlertDialog.Builder(this).setTitle(StudyText.timestamp(row.cue.startMs)).setItems(new String[]{getString(R.string.study_play_here), getString(R.string.study_loop_sentence), getString(R.string.study_note_default), getString(R.string.study_save_range)}, (dialog, index) -> {
                if (index == 0) jump(row.cue.startMs, 0); else if (index == 1) jump(row.cue.startMs, row.cue.endMs);
                else if (index == 2) editNote(null, row.cue.startMs, row.cue.text); else editClip(null, row.cue.startMs, row.cue.endMs);
            }).show(); return;
        }
        StudyNote item = row.note;
        List<String> choices = new ArrayList<>(); choices.add(getString(R.string.study_play_here));
        choices.add(getString("clip".equals(item.kind) ? R.string.study_export_bundle : "snapshot".equals(item.kind) ? R.string.share : R.string.study_edit)); choices.add(getString(R.string.delete));
        if ("clip".equals(item.kind) && item.assetUri != null) choices.add(getString(R.string.study_open_bundle));
        new AlertDialog.Builder(this).setTitle(item.label).setItems(choices.toArray(new String[0]), (dialog, index) -> {
            if (index == 0) jump(item.positionMs, "clip".equals(item.kind) ? item.endMs : 0);
            else if (index == 1) {
                if ("clip".equals(item.kind)) exportClip(item);
                else if ("snapshot".equals(item.kind)) share(Uri.parse(item.assetUri), "image/png");
                else editNote(item, item.positionMs, item.body);
            } else if (index == 3) showBundle(Uri.parse(item.assetUri));
            else new AlertDialog.Builder(this).setMessage(R.string.study_delete_confirm).setPositiveButton(R.string.delete, (d, w) -> work(() -> {
                try { if ("snapshot".equals(item.kind)) StudyAssets.delete(this, item.assetUri); dao.delete(item.id, key); } catch (Exception failure) { error(failure); }
            })).setNegativeButton(R.string.cancel, null).show();
        }).show();
    }
    private void indexSpeech() {
        if (!"file".equals(source.getScheme()) && !"content".equals(source.getScheme())) { Toast.makeText(this, R.string.study_local_required, Toast.LENGTH_LONG).show(); return; }
        if (SpeechModelStore.ready(this)) {
            new AlertDialog.Builder(this).setTitle(R.string.study_index_speech).setMessage(R.string.study_index_note)
                    .setPositiveButton(R.string.study_start, (dialog, index) -> startJob(StudyProcessingService.INDEX, null)).setNegativeButton(R.string.cancel, null).show();
        } else new AlertDialog.Builder(this).setTitle(R.string.study_prepare_model).setMessage(R.string.study_model_note)
                .setPositiveButton(R.string.study_download_model, (dialog, index) -> startJob(StudyProcessingService.DOWNLOAD, null))
                .setNeutralButton(R.string.study_import_model, (dialog, index) -> pick(IMPORT_MODEL, "application/zip"))
                .setNegativeButton(R.string.cancel, null).show();
    }
    private void startJob(String action, Uri data) {
        PermissionUtils.requestNotificationPermission(this);
        Intent job = new Intent(this, StudyProcessingService.class).setAction(action).setData(data)
                .putExtra("source", source.toString()).putExtra("key", key).putExtra("title", title).putExtra("position", position).putExtra("duration", duration);
        ContextCompat.startForegroundService(this, job);
    }
    private void pick(int request, String mime) { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), request); }
    @Override protected void onActivityResult(int code, int result, @Nullable Intent data) {
        super.onActivityResult(code, result, data); if (result != RESULT_OK || data == null || data.getData() == null) return; Uri uri = data.getData();
        if (code == IMPORT_MODEL || code == IMPORT_TEXT) try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) {}
        if (code == IMPORT_MODEL) startJob(StudyProcessingService.IMPORT, uri);
        else if (code == IMPORT_TEXT) work(() -> { try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Cannot read subtitles"); SubtitleTimeline parsed = SubtitleTimeline.parse(TranscriptStore.readText(input)); TranscriptStore.save(this, key, parsed.toSrt(0)); ui(() -> { transcript = parsed; render(); });
        } catch (Exception failure) { error(failure); } });
        else if (code == SAVE_BUNDLE && pendingBundle != null) { final Uri bundle = pendingBundle; pendingBundle = null; work(() -> {
            try (InputStream input = getContentResolver().openInputStream(bundle); OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (input == null || output == null) throw new IOException("Cannot open export"); byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                ui(() -> Toast.makeText(this, R.string.study_saved, Toast.LENGTH_LONG).show());
            } catch (Exception failure) { error(failure); }
        }); }
    }
    private void exportNotes() {
        final List<StudyNote> snapshot = new ArrayList<>(notes);
        work(() -> { try {
            File folder = new File(getCacheDir(), "study-export"); if (!folder.exists() && !folder.mkdirs()) throw new IOException("Cannot create export folder");
            File file = new File(folder, StudyText.safeName(title) + "-notes.md"); try (OutputStream output = new FileOutputStream(file)) { output.write(StudyExports.markdown(title, snapshot, 0).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".studyfiles", file); ui(() -> share(uri, "text/markdown"));
        } catch (Exception failure) { error(failure); } });
    }
    private void exportClip(StudyNote clip) {
        if (exporting) return;
        if (PermissionUtils.needsTrimWritePermission() && !PermissionUtils.hasTrimWritePermission(this)) { pendingClip = clip; PermissionUtils.requestTrimWritePermission(this); return; }
        exporting = true; ((TextView) findViewById(R.id.study_job_status)).setText(R.string.study_exporting);
        work(() -> {
            try {
                long actual = VideoTrimmer.actualStartMs(this, source, clip.positionMs);
                SubtitleTimeline captions = TranscriptStore.read(this, key); List<StudyNote> snapshot = dao.getSync(key);
                VideoTrimmer.trim(this, source, clip.positionMs * 1000, clip.endMs * 1000, new VideoTrimmer.Callback() {
                    public void onFinished(@NonNull Uri video, boolean scan) {
                        // Complete on the trim worker even if the screen rotated or closed.
                        try { Uri bundle = StudyExports.bundle(getApplicationContext(), clip, video, actual, captions, snapshot);
                            dao.attachExport(clip.id, clip.mediaKey, bundle.toString());
                            ui(() -> { exporting = false; ((TextView) findViewById(R.id.study_job_status)).setText(R.string.study_bundle_ready); showBundle(bundle); });
                        } catch (Exception failure) { ui(() -> exporting = false); error(failure); }
                    }
                    public void onFailed(@NonNull Exception failure) { ui(() -> exporting = false); error(failure); }
                });
            } catch (Exception failure) { ui(() -> exporting = false); error(failure); }
        });
    }
    private void showBundle(Uri bundle) {
        new AlertDialog.Builder(this).setTitle(R.string.study_bundle_ready).setMessage(R.string.study_bundle_note)
                .setPositiveButton(R.string.share, (d, w) -> share(bundle, "application/zip"))
                .setNeutralButton(R.string.study_save_copy, (d, w) -> {
                    pendingBundle = bundle;
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip")
                            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "CineMax-study.zip"), SAVE_BUNDLE);
                }).setNegativeButton(R.string.close, null).show();
    }
    @Override public void onRequestPermissionsResult(int code, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == PermissionUtils.REQUEST_TRIM_WRITE && pendingClip != null && PermissionUtils.hasTrimWritePermission(this)) { StudyNote clip = pendingClip; pendingClip = null; exportClip(clip); }
    }
    private void share(Uri uri, String type) {
        Intent intent = new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("CineMax study export", uri));
        startActivity(Intent.createChooser(intent, getString(R.string.share)));
    }
    private static final class Row {
        final StudyNote note; final SubtitleTimeline.Entry cue;
        Row(StudyNote note) { this.note = note; cue = null; } Row(SubtitleTimeline.Entry cue) { this.cue = cue; note = null; }
    }
    private final class NotesAdapter extends RecyclerView.Adapter<NotesAdapter.Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) { return new Holder(getLayoutInflater().inflate(R.layout.item_study, parent, false)); }
        @Override public void onBindViewHolder(@NonNull Holder holder, int index) {
            Row row = rows.get(index);
            holder.time.setText(StudyText.timestamp(row.cue == null ? row.note.positionMs : row.cue.startMs));
            holder.label.setText(row.cue == null ? row.note.label : row.cue.text);
            holder.body.setText(row.cue == null ? row.note.body : getString(R.string.study_cue_hint));
            holder.image.setVisibility(row.note != null && "snapshot".equals(row.note.kind) ? View.VISIBLE : View.GONE);
            holder.image.setImageDrawable(null); String asset = row.note == null ? null : row.note.assetUri; holder.image.setTag(asset);
            if (asset != null && row.note != null && "snapshot".equals(row.note.kind)) work(() -> {
                try (InputStream input = getContentResolver().openInputStream(Uri.parse(asset))) {
                    android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options(); options.inSampleSize = 4;
                    android.graphics.Bitmap image = BitmapFactory.decodeStream(input, null, options);
                    ui(() -> { if (asset.equals(holder.image.getTag())) holder.image.setImageBitmap(image); });
                } catch (Exception ignored) {}
            });
            holder.itemView.setOnClickListener(v -> actions(row));
        }
        @Override public int getItemCount() { return rows.size(); }
        final class Holder extends RecyclerView.ViewHolder {
            final TextView time, label, body; final ImageView image;
            Holder(View view) { super(view); time = view.findViewById(R.id.study_row_time); label = view.findViewById(R.id.study_row_title); body = view.findViewById(R.id.study_row_body); image = view.findViewById(R.id.study_row_image); }
        }
    }
}
