package fr.roaddeck.launcher;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.*;

final class Config {
    private final SharedPreferences prefs;
    private static final String ALIAS = "roaddeck-tuyasecret-v1";
    Config(Context context) { prefs = context.getSharedPreferences("roaddeck", Context.MODE_PRIVATE); }
    synchronized JSONObject read() {
        try { return new JSONObject(prefs.getString("settings", "{}")); }
        catch (JSONException e) { return new JSONObject(); }
    }
    synchronized String string(String key, String fallback) { return read().optString(key, fallback); }
    synchronized int integer(String key, int fallback) { return read().optInt(key, fallback); }
    synchronized boolean bool(String key, boolean fallback) { return read().optBoolean(key, fallback); }
    synchronized JSONObject publicSettings() throws JSONException {
        JSONObject j = read();
        defaults(j, "musicPackage", "com.spotify.music");
        defaults(j, "musicName", "Spotify");
        j.put("wazeMode", "split"); defaults(j, "splitAutoStart", true); defaults(j, "splitCoverBars", true); defaults(j, "splitFrame", true);
        for (String k : Core.list("frameLeft", "frameTop", "frameRight", "frameBottom")) defaults(j, k, 12);
        defaults(j, "overlayWidth", 34);
        defaults(j, "keepScreen", true); defaults(j, "gateConfirm", true);
        defaults(j, "radioBuffer", 15); defaults(j, "tuyaEndpoint", "https://openapi.tuyaeu.com");
        // Gate identifiers must be supplied by the user in Settings.
        defaults(j, "tuyaHome", ""); defaults(j, "tuyaScene", "");
        defaults(j, "wazeOffsetX", 0); defaults(j, "wazeOffsetY", 0);
        defaults(j, "wazeWidthDelta", 0); defaults(j, "wazeHeightDelta", 0);
        j.put("hasSecret", prefs.contains("tuyaSecret"));
        return j;
    }
    private static void defaults(JSONObject j, String key, Object value) throws JSONException {
        if (!j.has(key)) j.put(key, value);
    }
    synchronized void save(JSONObject input) throws Exception {
        JSONObject old = read();
        Set<String> strings = Core.set("musicPackage", "musicName", "wazeMode", "tuyaEndpoint", "tuyaClient",
            "tuyaHome", "tuyaScene", "weatherCity", "weatherLat", "weatherLon");
        Set<String> bools = Core.set("splitAutoStart", "splitCoverBars", "keepScreen", "gateConfirm", "splitFrame");
        for (String k : strings) if (input.has(k)) {
            String value = input.optString(k, "").trim();
            if (value.length() > 256) throw new IllegalArgumentException("Champ trop long : " + k);
            if (k.equals("tuyaEndpoint")) Core.endpoint(value);
            if (k.equals("wazeMode") && !Core.set("split").contains(value))
                throw new IllegalArgumentException("Mode Waze invalide");
            if (k.equals("musicPackage") && !value.matches("[A-Za-z0-9_.]{1,200}"))
                throw new IllegalArgumentException("Application invalide");
            if ((k.equals("tuyaHome") || k.equals("tuyaScene")) && !value.isEmpty()) Core.safeId(value);
            if ((k.equals("weatherLat") || k.equals("weatherLon")) && !value.isEmpty()) {
                double v = Double.parseDouble(value); double bound = k.equals("weatherLat") ? 90 : 180;
                if (!Double.isFinite(v) || Math.abs(v) > bound) throw new IllegalArgumentException("Coordonnée invalide");
            }
            old.put(k, value);
        }
        for (String k : bools) if (input.has(k)) old.put(k, input.optBoolean(k));
        for (String k : Core.list("frameLeft", "frameTop", "frameRight", "frameBottom"))
            if (input.has(k)) old.put(k, Core.clamp(input.optInt(k, 12), 0, 40));
        if (input.has("overlayWidth")) old.put("overlayWidth", OverlayGeometry.percent(input.optInt("overlayWidth",34)));
        if (input.has("radioBuffer")) old.put("radioBuffer", Core.bufferSeconds(input.optInt("radioBuffer",15)));
        for (String k : Core.list("wazeOffsetX","wazeOffsetY","wazeWidthDelta","wazeHeightDelta"))
            if (input.has(k)) old.put(k, Core.clamp(input.optInt(k,0), -200, 200));
        SharedPreferences.Editor editor = prefs.edit();
        if (input.optBoolean("clearSecret", false)) editor.remove("tuyaSecret");
        else if (input.has("tuyaSecret") && !input.optString("tuyaSecret").isEmpty()) {
            String secret = input.getString("tuyaSecret").trim();
            if (secret.length() > 1024) throw new IllegalArgumentException("Secret trop long");
            editor.putString("tuyaSecret", encrypt(secret));
        }
        editor.putString("settings", old.toString()).apply();
    }
    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            kg.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }
    private String encrypt(String value) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key());
        return Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" +
            Base64.encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }
    synchronized String secret() throws Exception {
        String text = prefs.getString("tuyaSecret", ""); if (text.isEmpty()) return "";
        String[] parts = text.split(":", 2);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
    void cacheWeather(JSONObject j) { prefs.edit().putString("weather", j.toString()).apply(); }
    JSONObject weather() {
        try { return new JSONObject(prefs.getString("weather", "{}")); } catch (Exception e) { return new JSONObject(); }
    }
    synchronized JSONArray favorites() {
        try { return new JSONArray(prefs.getString("favorites", "[]")); } catch (Exception e) { return new JSONArray(); }
    }
    synchronized void favorite(JSONObject station) throws Exception {
        String url = Core.streamUrl(station.optString("url"));
        JSONArray old = favorites(), next = new JSONArray(); boolean removed = false;
        for (int i=0;i<old.length();i++) {
            JSONObject item = old.getJSONObject(i);
            if (item.optString("url").equals(url)) removed = true; else next.put(item);
        }
        if (!removed) {
            if (next.length() >= 100) throw new IllegalArgumentException("100 favoris maximum");
            next.put(new JSONObject().put("name", station.optString("name", "Radio")).put("url", url)
                .put("uuid", station.optString("uuid")).put("hls", station.optBoolean("hls")));
        }
        prefs.edit().putString("favorites", next.toString()).apply();
    }
    void lastStation(JSONObject station) { prefs.edit().putString("station", station.toString()).apply(); }
    JSONObject lastStation() {
        try { return new JSONObject(prefs.getString("station", "{}")); } catch (Exception e) { return new JSONObject(); }
    }
}
