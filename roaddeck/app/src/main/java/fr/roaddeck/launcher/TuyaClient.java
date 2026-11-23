package fr.roaddeck.launcher;

import java.util.*;
import org.json.*;

/** Direct, signed Tuya Cloud calls. The trigger POST is NEVER automatically retried. */
final class TuyaClient {
    private final Config config;
    private String token = "", credentialFingerprint = "";
    private long tokenDeadline;
    TuyaClient(Config config) { this.config = config; }
    private JSONObject call(String method, String path, boolean withToken) throws Exception {
        JSONObject cfg = config.publicSettings();
        String endpoint = Core.endpoint(cfg.getString("tuyaEndpoint"));
        String client = cfg.optString("tuyaClient").trim(), secret = config.secret();
        if (client.isEmpty() || secret.isEmpty()) throw new IllegalArgumentException("Renseigner Access ID et Access Secret dans Réglages > Portail");
        String fingerprint = Core.sha256(endpoint + client + secret);
        if (!fingerprint.equals(credentialFingerprint)) { token=""; tokenDeadline=0; credentialFingerprint=fingerprint; }
        if (withToken && (token.isEmpty() || System.currentTimeMillis() >= tokenDeadline)) {
            JSONObject r = call("GET", "/v1.0/token?grant_type=1", false).getJSONObject("result");
            token = r.getString("access_token");
            tokenDeadline = System.currentTimeMillis() + Math.max(30, r.optLong("expire_time", 7200)-60)*1000;
        }
        String time = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String accessToken = withToken ? token : "";
        String body = "";
        Map<String,String> headers = new HashMap<>();
        headers.put("client_id", client); headers.put("t", time); headers.put("nonce", nonce);
        headers.put("sign_method", "HMAC-SHA256");
        headers.put("sign", Core.sign(client,secret,accessToken,time,nonce,method,path,body));
        if (withToken) headers.put("access_token", accessToken);
        JSONObject result;
        try { result = new JSONObject(Http.text(endpoint+path,method,body,headers)); }
        catch (Exception e) {
            if ("POST".equals(method)) throw new Exception("Réponse non reçue. Vérifier le portail avant de renvoyer une commande. Aucun nouvel envoi automatique.",e);
            throw e;
        }
        if (!result.optBoolean("success")) {
            String code=result.optString("code");
            if (code.equals("1010") || code.equals("1011")) { token=""; tokenDeadline=0; }
            throw new Exception("Tuya " + code + " : " + result.optString("msg", "Requête refusée"));
        }
        return result;
    }
    synchronized JSONArray scenes() throws Exception {
        String home = Core.safeId(config.publicSettings().getString("tuyaHome"));
        return call("GET", "/v1.0/homes/"+home+"/scenes", true).getJSONArray("result");
    }
    synchronized void trigger() throws Exception {
        JSONObject cfg=config.publicSettings();
        String home=Core.safeId(cfg.getString("tuyaHome")), scene=Core.safeId(cfg.getString("tuyaScene"));
        JSONObject r=call("POST", "/v1.0/homes/"+home+"/scenes/"+scene+"/trigger", true);
        if (!r.optBoolean("result")) throw new Exception("Tuya n’a pas confirmé l’exécution de la scène");
    }
}
