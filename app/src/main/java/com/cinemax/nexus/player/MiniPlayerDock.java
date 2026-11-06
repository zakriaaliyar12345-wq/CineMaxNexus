package com.cinemax.nexus.player;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import com.cinemax.nexus.R;
import com.cinemax.nexus.music.MusicActivity;
import com.cinemax.nexus.music.MusicPlaybackService;
import com.cinemax.nexus.util.FormatUtils;

/** A compact transport shared by browsing screens. Services retain all playback state. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class MiniPlayerDock {
    private static boolean musicSelected;
    public static void selectVideo() { musicSelected=false; }
    public static void selectMusic() { musicSelected=true; }
    private final Activity activity;
    private final View panel;
    private final TextView title, detail;
    private final ImageButton play, next;
    private final ImageView art;
    private final ProgressBar progress;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean running;
    private Player shown;
    private boolean showingMusic;
    private String artworkKey="";

    private MiniPlayerDock(Activity activity,View panel) {
        this.activity=activity; this.panel=panel;
        ((ViewGroup)panel).getChildAt(0).setClipToOutline(true);
        title=panel.findViewById(R.id.mini_title); detail=panel.findViewById(R.id.mini_detail);
        play=panel.findViewById(R.id.mini_play); next=panel.findViewById(R.id.mini_next);
        art=panel.findViewById(R.id.mini_art); progress=panel.findViewById(R.id.mini_progress);
        panel.findViewById(R.id.mini_open).setOnClickListener(v -> {
            if (shown==null) return;
            if(showingMusic) activity.startActivity(new Intent(activity,MusicActivity.class)
                    .putExtra("open_now_playing",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
            else if(PlaybackService.active()!=null) activity.startActivity(PlaybackService.active().returnIntent());
        });
        play.setOnClickListener(v -> {
            if(shown==null) return;
            if(shown.getPlayWhenReady()) shown.pause();
            else { if(shown.getPlaybackState()==Player.STATE_ENDED) shown.seekTo(0); shown.prepare(); shown.play(); }
            update();
        });
        next.setOnClickListener(v -> {
            if(showingMusic && shown!=null && shown.hasNextMediaItem()) shown.seekToNextMediaItem();
            else if(!showingMusic && PlaybackService.active()!=null) PlaybackService.active().skip(1);
            update();
        });
        panel.findViewById(R.id.mini_close).setOnClickListener(v -> {
            if(showingMusic) MusicPlaybackService.closePlayback();
            else if(PlaybackService.active()!=null) PlaybackService.active().closePlayback();
            update();
        });
    }
    private static boolean hasMedia(Player player) { return player!=null && player.getMediaItemCount()>0; }
    private void update() {
        Player music=MusicPlaybackService.currentPlayer();
        Player video=PlaybackService.active()==null ? null : PlaybackService.active().player();
        showingMusic=musicSelected;
        if(!hasMedia(showingMusic ? music : video)) showingMusic=!showingMusic;
        shown=showingMusic ? music : video;
        if(!hasMedia(shown) || (showingMusic && activity instanceof MusicActivity)) {
            panel.setVisibility(View.GONE); shown=null; return;
        }
        panel.setVisibility(View.VISIBLE);
        MediaItem item=shown.getCurrentMediaItem();
        title.setText(item.mediaMetadata.title);
        long duration=Math.max(0,shown.getDuration()), position=shown.getCurrentPosition();
        String clock=androidx.core.text.BidiFormatter.getInstance(activity.getResources().getConfiguration().getLocales().get(0))
                .unicodeWrap(FormatUtils.formatDuration(position)+" / "+FormatUtils.formatDuration(duration),
                        androidx.core.text.TextDirectionHeuristicsCompat.LTR);
        detail.setText(activity.getString(showingMusic ? R.string.mini_music_detail : R.string.mini_video_detail, clock));
        play.setImageResource(shown.getPlayWhenReady() ? R.drawable.ic_pause : R.drawable.ic_play);
        play.setContentDescription(activity.getString(shown.getPlayWhenReady() ? R.string.pause : R.string.play));
        boolean canNext=showingMusic ? shown.hasNextMediaItem() : PlaybackService.active()!=null && PlaybackService.active().canSkip();
        next.setEnabled(canNext); next.setAlpha(canNext ? 1f : .35f);
        String key=(showingMusic ? "music:" : "video:")+item.mediaId;
        if(!key.equals(artworkKey)) {
            artworkKey=key;
            art.setImageResource(showingMusic ? R.drawable.ic_music_art : R.drawable.ic_video_placeholder);
            if(!showingMusic && PlaybackService.active()!=null && PlaybackService.active().currentVideo()!=null)
                com.cinemax.nexus.data.ThumbnailLoader.getInstance(activity).load(PlaybackService.active().currentVideo(),(uri,bitmap) -> {
                    if(running && key.equals(artworkKey) && bitmap!=null) art.setImageBitmap(bitmap);
                });
        }
        progress.setProgress(duration>0 ? (int)Math.min(1000,position*1000/duration) : 0);
    }
    private final Runnable tick=new Runnable() {
        @Override public void run() { if(running) { update(); main.postDelayed(this,500); } }
    };
    private void resume() { running=true; main.removeCallbacks(tick); tick.run(); }
    private void stop() { running=false; main.removeCallbacks(tick); shown=null; }

    /** Adds a real layout row, so the dock never covers a video card, FAB or bottom nav. */
    public static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        private MiniPlayerDock find(Activity activity) {
            View view=activity.findViewById(R.id.global_mini_player);
            return view==null ? null : (MiniPlayerDock)view.getTag();
        }
        private void attach(Activity activity) {
            if(activity instanceof PlayerActivity) return;
            ViewGroup content=activity.findViewById(android.R.id.content);
            if(content==null || content.getChildCount()==0) return;
            View panel=activity.getLayoutInflater().inflate(R.layout.view_mini_player,content,false);
            View nav=activity.findViewById(R.id.nav_library);
            if(nav!=null && nav.getParent() instanceof ViewGroup && ((ViewGroup)nav.getParent()).getParent() instanceof LinearLayout) {
                View navBar=(View)nav.getParent(); LinearLayout root=(LinearLayout)navBar.getParent();
                root.addView(panel,root.indexOfChild(navBar),new LinearLayout.LayoutParams(-1,-2));
            } else {
                View original=content.getChildAt(0); content.removeView(original);
                LinearLayout wrapper=new LinearLayout(activity); wrapper.setOrientation(LinearLayout.VERTICAL);
                wrapper.setBackgroundColor(activity.getColor(R.color.nexus_black));
                wrapper.addView(original,new LinearLayout.LayoutParams(-1,0,1));
                wrapper.addView(panel,new LinearLayout.LayoutParams(-1,-2));
                content.addView(wrapper,new ViewGroup.LayoutParams(-1,-1));
                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(wrapper,(view,insets) -> {
                    androidx.core.graphics.Insets bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
                    view.setPadding(bars.left,bars.top,bars.right,bars.bottom);
                    // Child screens receive the remaining insets, avoiding duplicate status/nav padding.
                    return insets.inset(bars.left,bars.top,bars.right,bars.bottom);
                });
                androidx.core.view.ViewCompat.requestApplyInsets(wrapper);
            }
            panel.setTag(new MiniPlayerDock(activity,panel));
        }
        @Override public void onActivityResumed(Activity activity) {
            // onActivityCreated can run inside super.onCreate, before setContentView.
            if(find(activity)==null) attach(activity);
            MiniPlayerDock dock=find(activity); if(dock!=null) dock.resume();
        }
        @Override public void onActivityCreated(Activity activity,Bundle state) {}
        @Override public void onActivityStopped(Activity activity) { MiniPlayerDock dock=find(activity); if(dock!=null) dock.stop(); }
        @Override public void onActivityDestroyed(Activity activity) { MiniPlayerDock dock=find(activity); if(dock!=null) dock.stop(); }
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity,Bundle state) {}
    }
}
