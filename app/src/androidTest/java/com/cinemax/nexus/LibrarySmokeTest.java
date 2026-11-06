package com.cinemax.nexus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.cinemax.nexus.ui.HistoryActivity;
import com.cinemax.nexus.ui.PlaylistsActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Course topic 06.1 (UI testing): on-device smoke test for the two simplest library
 * screens. Launches each Activity for real and asserts the RecyclerView contract from
 * topic 04.5 holds: a LayoutManager and an Adapter are installed before the user can
 * ever scroll.
 *
 * Run with: {@code gradlew connectedDebugAndroidTest}
 */
@RunWith(AndroidJUnit4.class)
public class LibrarySmokeTest {

    @Test
    public void playlistsScreenInstallsRecyclerContract() {
        try (androidx.test.core.app.ActivityScenario<PlaylistsActivity> scenario =
                     androidx.test.core.app.ActivityScenario.launch(PlaylistsActivity.class)) {
            scenario.onActivity(activity -> {
                RecyclerView recycler = activity.findViewById(R.id.recycler);
                assertNotNull("recycler exists", recycler);
                assertNotNull("adapter installed", recycler.getAdapter());
                assertEquals("layout manager installed",
                        LinearLayoutManager.class, recycler.getLayoutManager().getClass());
                assertEquals("FAB visible for creating playlists",
                        android.view.View.VISIBLE, activity.findViewById(R.id.fab).getVisibility());
            });
        }
    }

    @Test
    public void historyScreenInstallsRecyclerContract() {
        try (androidx.test.core.app.ActivityScenario<HistoryActivity> scenario =
                     androidx.test.core.app.ActivityScenario.launch(HistoryActivity.class)) {
            scenario.onActivity(activity -> {
                RecyclerView recycler = activity.findViewById(R.id.recycler);
                assertNotNull("recycler exists", recycler);
                assertNotNull("adapter installed", recycler.getAdapter());
                assertEquals("layout manager installed",
                        LinearLayoutManager.class, recycler.getLayoutManager().getClass());
            });
        }
    }
}
