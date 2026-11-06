package com.cinemax.nexus.study;

import android.content.Context;
import android.net.Uri;
import org.json.*;
import org.vosk.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real file-audio recognition with word timestamps; never accesses a microphone. */
public final class SpeechIndexer {
    public interface Progress { void update(long positionMs, long durationMs); }
    private SpeechIndexer() {}
    public static String transcribe(Context context, Uri source, AtomicBoolean cancelled, Progress progress) throws Exception {
        if (!SpeechModelStore.ready(context)) throw new IllegalStateException("Install the English speech model first");
        List<Segment> segments = new ArrayList<>(); long[] offset = {-1};
        try (Model model = new Model(SpeechModelStore.model(context).getAbsolutePath()); Recognizer recognizer = new Recognizer(model, 16000)) {
            recognizer.setWords(true);
            AudioPcmDecoder.decode(context, source, cancelled, (pcm, position, duration) -> {
                if (offset[0] < 0) offset[0] = position;
                if (recognizer.acceptWaveForm(pcm, pcm.length)) append(segments, recognizer.getResult(), offset[0]);
                progress.update(position, duration);
            });
            append(segments, recognizer.getFinalResult(), Math.max(0, offset[0]));
        }
        if (segments.isEmpty()) throw new IllegalStateException("No English speech was recognized. You can import subtitles instead.");
        StringBuilder srt = new StringBuilder(); int number = 1;
        for (Segment item : segments) srt.append(number++).append('\n').append(stamp(item.start)).append(" --> ")
                .append(stamp(Math.max(item.start + 100, item.end))).append('\n').append(item.text).append("\n\n");
        return srt.toString();
    }
    private static void append(List<Segment> target, String json, long offset) throws Exception {
        JSONArray words = new JSONObject(json).optJSONArray("result");
        if (words == null) return;
        for (int i = 0; i < words.length();) {
            JSONObject first = words.getJSONObject(i); long start = offset + Math.round(first.getDouble("start") * 1000), end = start;
            StringBuilder sentence = new StringBuilder(); int count = 0;
            while (i < words.length() && count < 10) {
                JSONObject word = words.getJSONObject(i++);
                if (sentence.length() > 0) sentence.append(' ');
                sentence.append(word.getString("word")); end = offset + Math.round(word.getDouble("end") * 1000); count++;
                if (end - start >= 6000) break;
            }
            target.add(new Segment(start, end, sentence.toString()));
        }
    }
    private static String stamp(long ms) { return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000); }
    private static final class Segment {
        final long start, end; final String text;
        Segment(long start, long end, String text) { this.start = start; this.end = end; this.text = text; }
    }
}
