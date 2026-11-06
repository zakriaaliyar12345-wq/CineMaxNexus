package com.cinemax.nexus.util;

import androidx.annotation.NonNull;

import com.cinemax.nexus.data.MediaRepository;
import com.cinemax.nexus.data.model.VideoItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Deterministic sorting/filtering helpers — pure logic, unit-testable. */
public final class SortingUtils {

    private SortingUtils() {
    }

    @NonNull
    public static List<VideoItem> sorted(@NonNull List<VideoItem> input,
                                         @NonNull MediaRepository.SortMode mode,
                                         boolean ascending) {
        List<VideoItem> out = new ArrayList<>(input);
        Comparator<VideoItem> cmp = comparatorFor(mode);
        if (!ascending) cmp = cmp.reversed();
        Collections.sort(out, cmp);
        return out;
    }

    @NonNull
    public static Comparator<VideoItem> comparatorFor(@NonNull MediaRepository.SortMode mode) {
        switch (mode) {
            case TITLE:
                return Comparator.comparing(v -> v.title.toLowerCase(java.util.Locale.US));
            case DURATION:
                return Comparator.comparingLong(v -> v.durationMs);
            case SIZE:
                return Comparator.comparingLong(v -> v.sizeBytes);
            case DATE_ADDED:
            default:
                return Comparator.comparingLong(v -> v.dateAddedSec);
        }
    }

    @NonNull
    public static List<VideoItem> search(@NonNull List<VideoItem> input, @NonNull String query) {
        String q = query.trim().toLowerCase(java.util.Locale.US);
        if (q.isEmpty()) return input;
        List<VideoItem> out = new ArrayList<>();
        for (VideoItem v : input) {
            if (v.title.toLowerCase(java.util.Locale.US).contains(q)) out.add(v);
        }
        return out;
    }

    @NonNull
    public static List<VideoItem> favoritesOnly(@NonNull List<VideoItem> input,
                                                @NonNull java.util.Set<String> favoriteIds) {
        List<VideoItem> out = new ArrayList<>();
        for (VideoItem v : input) {
            if (favoriteIds.contains(v.key())) out.add(v);
        }
        return out;
    }

    @NonNull
    public static List<VideoItem> byFolder(@NonNull List<VideoItem> input, @NonNull String folder) {
        List<VideoItem> out = new ArrayList<>();
        for (VideoItem v : input) {
            if (folder.equals(v.folderName)) out.add(v);
        }
        return out;
    }
}
