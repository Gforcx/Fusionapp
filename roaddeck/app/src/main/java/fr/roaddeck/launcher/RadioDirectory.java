package fr.roaddeck.launcher;

import java.net.InetAddress;
import java.util.*;
import org.json.*;

final class RadioDirectory {
    private List<String> hosts;
    private long resolvedAt;
    private synchronized List<String> servers() {
        if (hosts!=null && System.currentTimeMillis()-resolvedAt<86400000) return new ArrayList<>(hosts);
        List<String> found=new ArrayList<>();
        try {
            for (InetAddress ip : InetAddress.getAllByName("all.api.radio-browser.info")) {
                String host=ip.getCanonicalHostName();
                if (host.matches("[a-zA-Z0-9.-]+\\.api\\.radio-browser\\.info") && !found.contains(host)) found.add(host);
            }
        } catch(Exception ignored) { }
        if (found.isEmpty()) found.addAll(Core.list("de1.api.radio-browser.info", "nl1.api.radio-browser.info", "at1.api.radio-browser.info"));
        Collections.shuffle(found); hosts=found; resolvedAt=System.currentTimeMillis(); return new ArrayList<>(hosts);
    }
    JSONArray search(String query, boolean franceOnly) throws Exception {
        String q=query.trim(); if(q.length()>100) q=q.substring(0,100);
        String path="/json/stations/search?hidebroken=true&limit=50&order=votes&reverse=true&name="+Http.enc(q);
        if (franceOnly) path += "&countrycode=FR";
        Exception last=null;
        for(String host:servers()) try {
            JSONArray raw=new JSONArray(Http.text("https://"+host+path,"GET","",java.util.Collections.emptyMap()));
            JSONArray result=new JSONArray();
            for(int i=0;i<raw.length();i++) {
                JSONObject s=raw.getJSONObject(i); String url=s.optString("url_resolved",s.optString("url"));
                try { url=Core.streamUrl(url); } catch(Exception e) {continue;}
                result.put(new JSONObject().put("name",s.optString("name","Radio"))
                    .put("url",url).put("uuid",s.optString("stationuuid"))
                    .put("codec",s.optString("codec")).put("bitrate",s.optInt("bitrate"))
                    .put("hls",s.optInt("hls")==1));
            }
            return result;
        } catch(Exception e) {last=e;}
        throw new Exception("Annuaire radio indisponible. Les favoris et les URL manuelles restent utilisables.",last);
    }
    void click(String uuid) {
        if(!uuid.matches("[A-Za-z0-9-]{1,64}"))return;
        for(String host:servers())try { Http.get("https://"+host+"/json/url/"+uuid);return; } catch(Exception ignored) { }
    }
}
