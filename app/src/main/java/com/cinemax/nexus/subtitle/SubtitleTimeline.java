package com.cinemax.nexus.subtitle;
import java.util.*;
import java.util.regex.*;
/** Immutable, Android-independent SRT/WebVTT timeline. Times are milliseconds. */
public final class SubtitleTimeline {
    public static final class Entry {
        public final long startMs, endMs;
        public final String text;
        Entry(long start, long end, String value) {
            startMs=start;
            endMs=end;
            text=value;
        }
    }
    private static final Pattern TIME = Pattern.compile("(?:(\\d{1,3}):)?(\\d{2}):(\\d{2})[.,](\\d{3})");
    public final List<Entry> entries;
    private final long[] maximumEnd;
    private SubtitleTimeline(List<Entry> values) {
        values.sort(Comparator.comparingLong(e -> e.startMs));
        entries=Collections.unmodifiableList(values);
        maximumEnd=new long[values.size()];
        long end=0;
        for(int i=0;i<values.size();i++) {
            end=Math.max(end,values.get(i).endMs);
            maximumEnd[i]=end;
        }
    }
    /** Reject oversized/invalid files, accept cue identifiers, markup and VTT settings. */
    public static SubtitleTimeline parse(String source) {
        if(source==null || source.length()>2_000_000) throw new IllegalArgumentException("Subtitle exceeds 2 MB");
        String normalized=source.replace("\uFEFF", "").replace("\r\n","\n").replace('\r','\n');
        List<Entry> cues=new ArrayList<>();
        for(String block:normalized.split("\n[ \\t]*\n")) {
            String[] lines=block.split("\n");
            if(lines.length==0 || lines[0].startsWith("NOTE") || lines[0].startsWith("STYLE") || lines[0].startsWith("REGION")) continue;
            for(int i=0;i<lines.length;i++) {
                if(!lines[i].contains("-->")) continue;
                String[] parts=lines[i].split("-->",2);
                long start=time(parts[0].trim()),end=time(parts[1].trim().split("\\s+")[0]);
                if(end<=start) throw new IllegalArgumentException("Cue must end after it starts");
                StringBuilder text=new StringBuilder();
                for(int j=i+1;j<lines.length;j++) {
                    if(text.length()>0)text.append('\n');
                    text.append(lines[j]);
                }
                String plain=text.toString().replaceAll("<[^>]*>","")                         .replace("&lt;","<").replace("&gt;",">").replace("&amp;","&").replace("&nbsp;"," ");
                if(!plain.trim().isEmpty()) cues.add(new Entry(start,end,plain));
                if(cues.size()>10_000) throw new IllegalArgumentException("Too many subtitle cues");
                break;
            }
        }
        if(cues.isEmpty()) throw new IllegalArgumentException("No readable SRT or WebVTT cues");
        return new SubtitleTimeline(cues);
    }
    private static long time(String value) {
        Matcher m=TIME.matcher(value);
        if(!m.matches()) throw new IllegalArgumentException("Invalid subtitle timestamp");
        long hours=m.group(1)==null?0:Long.parseLong(m.group(1));
        int minutes=Integer.parseInt(m.group(2)),seconds=Integer.parseInt(m.group(3));
        if(minutes>59 || seconds>59) throw new IllegalArgumentException("Invalid subtitle timestamp");
        return ((hours*60+minutes)*60+seconds)*1000+Integer.parseInt(m.group(4));
    }
    /** Positive delay displays captions later; overlapping cues are combined in source order. */
    public String activeAt(long positionMs,long delayMs) {
        long time=positionMs-delayMs;
        int lo=0,hi=entries.size();
        while(lo<hi) {
            int mid=(lo+hi)>>>1;
            if(entries.get(mid).startMs<=time)lo=mid+1;
            else hi=mid;
        }
        List<String> active=new ArrayList<>();
        for(int i=lo-1;i>=0 && maximumEnd[i]>time;i--) {
            Entry e=entries.get(i);
            if(e.endMs>time)active.add(e.text);
        }
        Collections.reverse(active);
        return String.join("\n",active);
    }
    public List<Entry> search(String query) {
        String needle=query.toLowerCase(Locale.ROOT);
        List<Entry> result=new ArrayList<>();
        for(Entry e:entries)if(e.text.toLowerCase(Locale.ROOT).contains(needle))result.add(e);
        return result;
    }
    /** Export a new corrected file; the original subtitle is never overwritten. */
    public String toSrt(long delayMs) {
        StringBuilder out=new StringBuilder();
        int index=1;
        for(Entry e:entries) {
            long end=e.endMs+delayMs;
            if(end<=0)continue;
            out.append(index++).append('\n').append(stamp(Math.max(0,e.startMs+delayMs)))                     .append(" --> ").append(stamp(end)).append('\n').append(e.text).append("\n\n");
        }
        return out.toString();
    }
    /** Captions intersecting a clip, rebased to its actual first sample timestamp. */
    public String toSrtRange(long startMs, long endMs) {
        if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("Invalid clip range");
        StringBuilder out = new StringBuilder(); int number = 1;
        for (Entry entry : entries) {
            long start = Math.max(startMs, entry.startMs), end = Math.min(endMs, entry.endMs);
            if (end <= start) continue;
            out.append(number++).append('\n').append(stamp(start - startMs)).append(" --> ")
                    .append(stamp(end - startMs)).append('\n').append(entry.text).append("\n\n");
        }
        return out.toString();
    }
    private static String stamp(long ms) {
        return String.format(Locale.ROOT,"%02d:%02d:%02d,%03d",ms/3600000,(ms/60000)%60,(ms/1000)%60,ms%1000);
    }
}
