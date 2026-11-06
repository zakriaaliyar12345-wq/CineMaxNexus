package com.cinemax.nexus.subtitle;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.cinemax.nexus.R;
import com.cinemax.nexus.ui.StudioSheet;
import com.cinemax.nexus.util.FormatUtils;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
/** Owns external text captions, dual-language display, sync, transcript and safe exports. */
public final class SubtitleStudio {
    public interface Host {
        long position();
        void seek(long milliseconds);
        void loop(long start,long end);
        void captions(String primary,String secondary);
    }
    private static final int PRIMARY=2501,SECONDARY=2502,EXPORT=2503;
    private final Activity activity;
    private final Host host;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final SubtitleTimeline[] tracks=new SubtitleTimeline[2];
    private final long[] delays=new long[2];
    private String key="",lastPrimary="",lastSecondary="",pendingExport;
    private int generation;
    private boolean closed;
    private android.app.Dialog activeSheet;
    private void openSheet(LinearLayout content) {
        if(activeSheet != null) activeSheet.dismiss();
        activeSheet=StudioSheet.show(activity,content);
    }
    public SubtitleStudio(Activity screen,Host callbacks) {
        activity=screen;
        host=callbacks;
    }
    private String s(int id) {
        return activity.getString(id);
    }
    public boolean hasPrimary() {
        return tracks[0]!=null;
    }
    public void useEmbedded() {
        tracks[0]=null;
        activity.getSharedPreferences("subtitle_studio",0).edit().remove(key+":uri:0").apply();
        lastPrimary="";
        update(host.position());
    }
    public void disableAll() {
        generation++;
        tracks[0]=tracks[1]=null;
        activity.getSharedPreferences("subtitle_studio",0).edit().remove(key+":uri:0").remove(key+":uri:1").apply();
        lastPrimary="";
        lastSecondary="";
        host.captions(null,"");
    }
    public void reset(String mediaKey) {
        generation++;
        key=mediaKey;
        tracks[0]=tracks[1]=null;
        android.content.SharedPreferences prefs=activity.getSharedPreferences("subtitle_studio",0);
        for(int i=0;i<2;i++) {
            delays[i]=prefs.getLong(key+":delay:"+i,0);
            String uri=prefs.getString(key+":uri:"+i,null);
            if(uri!=null)load(Uri.parse(uri),i,false);
        }
        lastPrimary=lastSecondary="";
        host.captions(null,"");
    }
    public void update(long position) {
        String first=tracks[0]==null?null:tracks[0].activeAt(position,delays[0]);
        String second=tracks[1]==null?"":tracks[1].activeAt(position,delays[1]);
        if(!Objects.equals(first,lastPrimary)||!second.equals(lastSecondary)) {
            lastPrimary=first;
            lastSecondary=second;
            host.captions(first,second);
        }
    }
    public void show() {
        LinearLayout box=StudioSheet.column(activity);
        StudioSheet.heading(box,s(R.string.subtitle_studio),s(R.string.subtitle_studio_note));
        for(int i=0;i<2;i++) {
            final int slot=i;
            String name=s(i==0?R.string.primary_subtitle:R.string.secondary_subtitle);
            box.addView(StudioSheet.text(activity,name+" · "+(tracks[i]==null?s(R.string.not_loaded):tracks[i].entries.size()+" "+s(R.string.cues)),16));
            StudioSheet.action(box,s(R.string.load_srt_vtt),v -> pick(slot));
            StudioSheet.action(box,s(R.string.sync_subtitles)+String.format(Locale.getDefault()," (%+.1f s)",delays[i]/1000f),v -> sync(slot));
        }
        StudioSheet.action(box,s(R.string.transcript_search),v -> transcript());
        StudioSheet.action(box,s(R.string.export_corrected),v -> {
            if(tracks[0]==null){
                message(s(R.string.load_first));return;
            }
            pendingExport=tracks[0].toSrt(delays[0]);             activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/x-subrip")                     .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"CineMax-corrected.srt"),EXPORT);
        }
        );
        StudioSheet.action(box,s(R.string.clear_external),v -> {
            disableAll();message(s(R.string.done));
        }
        );
        openSheet(box);
    }
    private void pick(int slot) {
        activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")                 .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION                         |Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),slot==0?PRIMARY:SECONDARY);
    }
    public boolean onResult(int request,int result,Intent data) {
        if(request!=PRIMARY && request!=SECONDARY && request!=EXPORT)return false;
        if(result!=Activity.RESULT_OK || data==null || data.getData()==null)return true;
        Uri uri=data.getData();
        if(request==EXPORT) {
            final String text=pendingExport;
            pendingExport=null;
            if(text!=null)worker.execute(() -> {
                try(OutputStream output=activity.getContentResolver().openOutputStream(uri,"wt")) {
                    if(output==null)throw new IOException();output.write(text.getBytes(StandardCharsets.UTF_8));                     activity.runOnUiThread(() -> {
                        if(!closed)message(s(R.string.export_success));
                    }
                    );
                }
                catch(Exception e) {
                    activity.runOnUiThread(() -> {
                        if(!closed)message(s(R.string.export_failed));
                    }
                    );
                }
            }
            );
        }
        else {
            try {
                activity.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            catch(SecurityException ignored) {
                /* Temporary providers still work in this session. */
            }
            load(uri,request==PRIMARY?0:1,true);
        }
        return true;
    }
    public void load(Uri uri,int slot,boolean notify) {
        final int expected=generation;
        worker.execute(() -> {
            try(InputStream stream=activity.getContentResolver().openInputStream(uri)) {
                if(stream==null)throw new IOException();                 ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;                 while((count=stream.read(buffer))!=-1) {
                    if(bytes.size()+count>2_000_000)throw new IOException("File exceeds 2 MB");                     bytes.write(buffer,0,count);
                }
                SubtitleTimeline timeline=SubtitleTimeline.parse(bytes.toString(StandardCharsets.UTF_8.name()));                 activity.runOnUiThread(() -> {
                    if(closed || expected!=generation)return;                     tracks[slot]=timeline;                     activity.getSharedPreferences("subtitle_studio",0).edit().putString(key+":uri:"+slot,uri.toString()).apply();                     update(host.position());if(notify)message(s(R.string.sub_sync_success));
                }
                );
            }
            catch(Exception error) {
                activity.runOnUiThread(() -> {
                    if(!closed && expected==generation && notify)message(s(R.string.subtitle_parse_failed));
                }
                );
            }
        }
        );
    }
    private void sync(int slot) {
        LinearLayout box=StudioSheet.column(activity);
        StudioSheet.heading(box,s(R.string.sync_subtitles),s(R.string.sync_note));
        TextView value=StudioSheet.text(activity,"",24);
        box.addView(value);
        Runnable refresh=() -> value.setText(String.format(Locale.getDefault(),"%+.1f s",delays[slot]/1000f));
        refresh.run();
        for(int step:new int[]{
            -100,100,-1000,1000,0
        }
        ) {
            String label=step==0?s(R.string.reset):String.format(Locale.getDefault(),"%+.1f s",step/1000f);
            StudioSheet.action(box,label,v -> {
                delays[slot]=step==0?0:Math.max(-600000,Math.min(600000,delays[slot]+step));                 activity.getSharedPreferences("subtitle_studio",0).edit().putLong(key+":delay:"+slot,delays[slot]).apply();                 refresh.run();update(host.position());
            }
            );
        }
        openSheet(box);
    }
    private void transcript() {
        if(tracks[0]==null){
            message(s(R.string.load_first));
            return;
        }
        final SubtitleTimeline timeline=tracks[0];
        final long delay=delays[0];
        LinearLayout box=StudioSheet.column(activity);
        StudioSheet.heading(box,s(R.string.transcript_search),s(R.string.transcript_note));
        EditText search=new EditText(activity);
        search.setSingleLine();
        search.setHint(R.string.search_transcript);
        box.addView(search);
        RecyclerView list=new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        final List<SubtitleTimeline.Entry> visible=new ArrayList<>(timeline.entries);
        RecyclerView.Adapter<RecyclerView.ViewHolder> adapter=new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent,int type) {
                TextView row=StudioSheet.text(activity,"",15);
                row.setMinHeight(StudioSheet.dp(activity,64));
                row.setBackgroundResource(R.drawable.bg_studio_panel);
                row.setPadding(StudioSheet.dp(activity,12),StudioSheet.dp(activity,12),StudioSheet.dp(activity,12),StudioSheet.dp(activity,12));
                row.setLayoutParams(new RecyclerView.LayoutParams(-1,-2));
                return new RecyclerView.ViewHolder(row){
                }
                ;
            }
            public void onBindViewHolder(RecyclerView.ViewHolder holder,int position) {
                SubtitleTimeline.Entry cue=visible.get(position);
                ((TextView)holder.itemView).setText(FormatUtils.formatDuration(Math.max(0,cue.startMs+delay))+"  ·  "+cue.text);
                holder.itemView.setOnClickListener(v -> host.seek(Math.max(0,cue.startMs+delay)));
                holder.itemView.setOnLongClickListener(v -> {
                    host.loop(Math.max(0,cue.startMs+delay),Math.max(1,cue.endMs+delay));message(s(R.string.cue_loop));return true;
                }
                );
            }
            public int getItemCount(){
                return visible.size();
            }
        }
        ;
        list.setAdapter(adapter);
        box.addView(list,new LinearLayout.LayoutParams(-1,StudioSheet.dp(activity,320)));
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){
            }
            public void onTextChanged(CharSequence s,int start,int before,int count){
                visible.clear();visible.addAll(timeline.search(s.toString()));adapter.notifyDataSetChanged();
            }
            public void afterTextChanged(Editable s){
            }
        }
        );
        openSheet(box);
    }
    private void message(String text){
        Toast.makeText(activity,text,Toast.LENGTH_LONG).show();
    }
    public void close(){
        closed=true;
        if(activeSheet != null) activeSheet.dismiss();
        generation++;
        worker.shutdownNow();
    }
}
