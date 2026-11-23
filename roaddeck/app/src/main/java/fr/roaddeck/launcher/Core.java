package fr.roaddeck.launcher;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Pure Java logic, tested without an Android runtime. */
public final class Core {
    private Core() {}
    @SafeVarargs public static <T> java.util.List<T> list(T... values) {
        return java.util.Arrays.asList(values);
    }
    @SafeVarargs public static <T> Set<T> set(T... values) {
        return java.util.Collections.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList(values)));
    }
    public static final Set<String> TUYA_ENDPOINTS = Core.set(
        "https://openapi.tuyaeu.com", "https://openapi-weaz.tuyaeu.com",
        "https://openapi.tuyaus.com", "https://openapi-ueaz.tuyaus.com",
        "https://openapi.tuyacn.com", "https://openapi.tuyain.com", "https://openapi-sg.iotbing.com");
    public static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }
    public static String sha256(String text) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String hmac(String key, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8))).toUpperCase(Locale.ROOT);
        } catch (Exception e) { throw new IllegalArgumentException("Clé de signature invalide", e); }
    }
    public static String sign(String client, String secret, String token, String time,
                              String nonce, String method, String path, String body) {
        String canonical = method + "\n" + sha256(body) + "\n\n" + path;
        return hmac(secret, client + token + time + nonce + canonical);
    }
    public static String safeId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,160}"))
            throw new IllegalArgumentException("Identifiant Tuya invalide");
        return id;
    }
    public static String endpoint(String endpoint) {
        if (!TUYA_ENDPOINTS.contains(endpoint)) throw new IllegalArgumentException("Région Tuya non reconnue");
        return endpoint;
    }
    public static String streamUrl(String url) {
        try {
            URI u = URI.create(url.trim());
            if (!Core.set("https", "http").contains(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null)
                throw new IllegalArgumentException();
            return u.toASCIIString();
        } catch (Exception e) { throw new IllegalArgumentException("Adresse du flux : http(s) requis, sans identifiants"); }
    }
    public static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    public static int bufferSeconds(int seconds) { return clamp(seconds, 5, 120); }
    public static int[] bufferDurations(int seconds, boolean hls) {
        int initial = (hls ? Math.min(bufferSeconds(seconds), 8) : bufferSeconds(seconds)) * 1000;
        return new int[]{Math.max(60000, initial + 15000), Math.max(180000, initial + 60000), initial, Math.min(initial, 10000)};
    }
    public static long retryDelay(int attempt) { return Math.min(5000L, Math.max(1, attempt) * 1000L); }
}
