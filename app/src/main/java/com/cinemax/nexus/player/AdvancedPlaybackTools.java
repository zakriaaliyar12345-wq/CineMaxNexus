package com.cinemax.nexus.player;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import com.cinemax.nexus.R;
import com.cinemax.nexus.ui.StudioSheet;
import java.util.Locale;
/** Device-observed diagnostics and independent time-stretch/pitch controls. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class AdvancedPlaybackTools implements AnalyticsListener {
    private String videoDecoder="—",audioDecoder="—";
    private int droppedFrames;
    private android.app.Dialog activeDialog;
    private final Handler refreshHandler=new Handler(Looper.getMainLooper());
    public void close(){
        refreshHandler.removeCallbacksAndMessages(null);
        if(activeDialog!=null)activeDialog.dismiss();
    }
    @Override public void onVideoDecoderInitialized(EventTime time,String name,long at,long duration){
        videoDecoder=name;
    }
    @Override public void onAudioDecoderInitialized(EventTime time,String name,long at,long duration){
        audioDecoder=name;
    }
    @Override public void onDroppedVideoFrames(EventTime time,int count,long elapsed){
        droppedFrames+=count;
    }
    public void showDiagnostics(Activity activity,ExoPlayer player) {
        LinearLayout box=StudioSheet.column(activity);
        StudioSheet.heading(box,activity.getString(R.string.playback_diagnostics),activity.getString(R.string.diagnostics_note));
        TextView output=StudioSheet.text(activity,"",14);
        output.setTextIsSelectable(true);
        box.addView(output);
        if(activeDialog!=null)activeDialog.dismiss();
        android.app.Dialog dialog=StudioSheet.show(activity,box);
        activeDialog=dialog;
        Handler handler=refreshHandler;
        Runnable refresh=new Runnable(){
            public void run(){
                if(!dialog.isShowing())return;
                Format video=player.getVideoFormat(),audio=player.getAudioFormat();
                String data=activity.getString(R.string.engine_state)+": "+activity.getString(state(player.getPlaybackState()))
                        +"\n\n"+activity.getString(R.string.diagnostics_video_decoder)+"\n"+videoDecoder+"\n"+format(video)
                        +"\n\n"+activity.getString(R.string.diagnostics_audio_decoder)+"\n"+audioDecoder+"\n"+format(audio)
                        +"\n\n"+activity.getString(R.string.diagnostics_buffer,player.getTotalBufferedDuration())
                        +"\n"+activity.getString(R.string.diagnostics_dropped,droppedFrames)
                        +"\n"+activity.getString(R.string.diagnostics_tuning,player.getPlaybackParameters().speed,player.getPlaybackParameters().pitch);
                output.setText(data);
                handler.postDelayed(this,1000);
            }
        }
        ;
        dialog.setOnDismissListener(d -> handler.removeCallbacks(refresh));
        refresh.run();
    }
    private static int state(int value){
        switch(value){
            case Player.STATE_READY:return R.string.state_ready;
            case Player.STATE_BUFFERING:return R.string.state_buffering;
            case Player.STATE_ENDED:return R.string.state_ended;
            default:return R.string.state_idle;
        }
    }
    private static String format(Format f){
        if(f==null)return "—";
        String value=f.sampleMimeType==null?"—":f.sampleMimeType;
        if(f.width>0)value+=" · "+f.width+" × "+f.height;
        if(f.frameRate>0)value+=String.format(Locale.ROOT," · %.2f fps",f.frameRate);
        if(f.channelCount>0)value+=" · "+f.channelCount+" ch";
        if(f.sampleRate>0)value+=" · "+f.sampleRate+" Hz";
        if(f.bitrate>0)value+=" · "+f.bitrate/1000+" kb/s";
        return value;
    }
    public static void showTuning(Activity activity,ExoPlayer player) {
        LinearLayout box=StudioSheet.column(activity);
        StudioSheet.heading(box,activity.getString(R.string.precision_playback),activity.getString(R.string.precision_note));
        float[] values={
            player.getPlaybackParameters().speed,player.getPlaybackParameters().pitch
        }
        ;
        slider(box,activity.getString(R.string.playback_speed),25,400,values[0],v -> values[0]=v);
        slider(box,activity.getString(R.string.audio_pitch),50,200,values[1],v -> values[1]=v);
        new AlertDialog.Builder(activity).setView(box).setPositiveButton(R.string.apply,(d,w) ->                 player.setPlaybackParameters(new PlaybackParameters(values[0],values[1])))                 .setNeutralButton(R.string.reset,(d,w) -> player.setPlaybackParameters(PlaybackParameters.DEFAULT))                 .setNegativeButton(R.string.cancel,null).show();
    }
    private interface Value {
        void set(float value);
    }
    private static void slider(LinearLayout box,String title,int min,int max,float value,Value callback) {
        TextView label=StudioSheet.text(box.getContext(),"",16);
        box.addView(label);
        SeekBar bar=new SeekBar(box.getContext());
        bar.setContentDescription(title);
        bar.setMax(max-min);
        bar.setProgress(Math.round(value*100)-min);
        label.setText(String.format(Locale.getDefault(),"%s · %.2fx",title,value));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int progress,boolean user){
                float current=(progress+min)/100f;callback.set(current);label.setText(String.format(Locale.getDefault(),"%s · %.2fx",title,current));
            }
            public void onStartTrackingTouch(SeekBar b){
            }
            public void onStopTrackingTouch(SeekBar b){
            }
        }
        );
        box.addView(bar);
    }
}
