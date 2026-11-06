package com.cinemax.nexus.util;
import java.net.URI;
import java.util.*;
/** Validated stream request. Header values are intentionally never logged or persisted. */
public final class StreamRequest {
    public final String url;
    public final Map<String,String> headers;
    public StreamRequest(String address,String headerText) {
        String raw=address.trim();
        if(!raw.contains("://"))raw="https://"+raw;
        try {
            URI uri=new URI(raw);
            if(!Arrays.asList("http","https").contains(uri.getScheme()) || uri.getHost()==null                     || uri.getUserInfo()!=null || uri.getFragment()!=null)throw new IllegalArgumentException();
        }
        catch(Exception e) {
            throw new IllegalArgumentException("Enter a direct HTTP(S) media URL without spaces or embedded credentials");
        }
        url=raw;
        Map<String,String> parsed=new LinkedHashMap<>();
        if(headerText.length()>8192)throw new IllegalArgumentException("Headers are too long");
        for(String line:headerText.split("\n")) {
            if(line.trim().isEmpty())continue;
            int colon=line.indexOf(':');
            if(colon<=0)throw new IllegalArgumentException("Use one Header-Name: value per line");
            String name=line.substring(0,colon).trim(),value=line.substring(colon+1).trim();
            if(!name.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+") || value.chars().anyMatch(c -> c<32 || c==127))                 throw new IllegalArgumentException("Invalid header characters");
            if(Arrays.asList("host","content-length","connection","range").contains(name.toLowerCase(Locale.ROOT)))                 throw new IllegalArgumentException("This header is managed by the player: "+name);
            if(parsed.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(name)))throw new IllegalArgumentException("Duplicate header: "+name);
            parsed.put(name,value);
        }
        headers=Collections.unmodifiableMap(parsed);
    }
}
