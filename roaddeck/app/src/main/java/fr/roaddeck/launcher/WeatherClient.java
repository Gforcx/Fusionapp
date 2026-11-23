package fr.roaddeck.launcher;

import org.json.*;
final class WeatherClient {
    static JSONArray cities(String query) throws Exception {
        if(query.trim().length()<2) return new JSONArray();
        JSONObject j=Http.get("https://geocoding-api.open-meteo.com/v1/search?count=8&language=fr&format=json&name="+Http.enc(query.trim()));
        JSONArray r=j.optJSONArray("results"); return r==null?new JSONArray():r;
    }
    static JSONObject current(JSONObject cfg) throws Exception {
        String lat=cfg.optString("weatherLat"),lon=cfg.optString("weatherLon");
        if(lat.isEmpty()||lon.isEmpty()) throw new IllegalArgumentException("Choisir une ville dans les réglages météo");
        double la=Double.parseDouble(lat),lo=Double.parseDouble(lon);
        if(!Double.isFinite(la)||!Double.isFinite(lo)||Math.abs(la)>90||Math.abs(lo)>180)throw new IllegalArgumentException("Coordonnées invalides");
        String url="https://api.open-meteo.com/v1/forecast?latitude="+la+"&longitude="+lo+
            "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto";
        JSONObject j=Http.get(url), c=j.getJSONObject("current"),d=j.getJSONObject("daily");
        return new JSONObject().put("temperature",c.getDouble("temperature_2m"))
            .put("code",c.getInt("weather_code")).put("day",c.optInt("is_day",1)==1)
            .put("high",d.getJSONArray("temperature_2m_max").getDouble(0))
            .put("low",d.getJSONArray("temperature_2m_min").getDouble(0))
            .put("city",cfg.optString("weatherCity")).put("lat",lat).put("lon",lon)
            .put("updatedAt",System.currentTimeMillis()).put("sourceTime",c.optString("time"));
    }
}
