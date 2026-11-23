package fr.roaddeck.launcher;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.json.*;

final class Http {
    private Http() {}
    static String text(String url, String method, String body, Map<String,String> headers) throws IOException {
        URL u = new URL(url);
        if (!"https".equals(u.getProtocol())) throw new IOException("Connexion API HTTPS requise");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(15000);
        // Never forward a Tuya token or signature to a redirect target.
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", "RoadDeck/0.1 (personal Android launcher)");
        c.setRequestProperty("Accept", "application/json");
        for (Map.Entry<String,String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        try {
            if (!"GET".equals(method)) {
                c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) != -1) {
                    if (out.size() + n > 2_000_000) throw new IOException("Réponse API trop volumineuse");
                    out.write(b, 0, n);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally { c.disconnect(); }
    }
    static JSONObject get(String url) throws Exception { return new JSONObject(text(url, "GET", "", java.util.Collections.emptyMap())); }
    static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); } }
}
