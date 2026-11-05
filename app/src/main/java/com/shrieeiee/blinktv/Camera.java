package com.shrieeiee.blinktv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One Blink camera (doorbell, regular camera, or Mini) from the homescreen. */
public final class Camera {
    public String id = "";
    public String networkId = "";
    public String name = "Camera";
    /** "doorbell", "default" or "mini": decides the live-view endpoint. */
    public String cameraType = "default";
    /** Blink's model code, e.g. "lotus", "catalina", "owl". */
    public String productType = "";
    public String serial = "";
    public String thumbnailUrl = "";
    public boolean online = true;
    public String battery = "";
    public int wifiBars = -1;
    public boolean armed = false;

    static Camera fromHomescreen(JSONObject o, String cameraType, String baseUrl, String accountId) {
        Camera c = new Camera();
        c.id = o.optString("id");
        c.networkId = o.optString("network_id");
        c.name = o.optString("name", "Camera");
        c.cameraType = cameraType;
        c.productType = o.optString("type", "");
        c.serial = o.isNull("serial") ? "" : o.optString("serial", "");
        String status = o.optString("status", "online").toLowerCase();
        c.online = !"offline".equals(status);
        c.battery = o.isNull("battery") ? "" : o.optString("battery", "");
        c.armed = o.optBoolean("enabled", false);
        JSONObject signals = o.optJSONObject("signals");
        if (signals != null && signals.has("wifi")) c.wifiBars = signals.optInt("wifi", -1);

        // Same rules as blinkpy's update_images(): a bare timestamp means "build the v3 media URL".
        Object thumb = o.opt("thumbnail");
        String path = null;
        if (thumb instanceof Number) {
            path = v3Thumb(accountId, c, String.valueOf(((Number) thumb).longValue()));
        } else if (thumb instanceof String && !((String) thumb).isEmpty()) {
            String t = (String) thumb;
            if (t.matches("\\d+")) path = v3Thumb(accountId, c, t);
            else if (t.endsWith("&ext=") || t.contains(".jpg")) path = t;
            else path = t + ".jpg";
        }
        if (path != null) c.thumbnailUrl = path.startsWith("http") ? path : baseUrl + (path.startsWith("/") ? "" : "/") + path;
        return c;
    }

    private static String v3Thumb(String accountId, Camera c, String ts) {
        return "/api/v3/media/accounts/" + accountId + "/networks/" + c.networkId + "/" + c.productType + "/"
                + c.id + "/thumbnail/thumbnail.jpg?ts=" + ts + "&ext=";
    }

    /** "Doorbell · Battery OK · Wi-Fi ▮▮▮▯▯" */
    public String meta() {
        StringBuilder sb = new StringBuilder();
        sb.append(kindLabel());
        if (!battery.isEmpty()) sb.append("  ·  Battery ").append(pretty(battery));
        if (wifiBars >= 0) {
            sb.append("  ·  Wi-Fi ");
            for (int i = 0; i < 5; i++) sb.append(i < wifiBars ? '▮' : '▯');
        }
        return sb.toString();
    }

    public String kindLabel() {
        switch (cameraType) {
            case "doorbell": return "Doorbell";
            case "mini": return "Indoor Mini";
            default: return "Camera";
        }
    }

    private static String pretty(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    // ------------------------------------------------------------------ intent passing

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("network_id", networkId).put("name", name).put("camera_type", cameraType)
                    .put("product_type", productType).put("serial", serial).put("thumb", thumbnailUrl)
                    .put("online", online).put("battery", battery).put("wifi", wifiBars).put("armed", armed);
        } catch (JSONException ignored) { }
        return o;
    }

    static Camera fromJson(JSONObject o) {
        Camera c = new Camera();
        c.id = o.optString("id");
        c.networkId = o.optString("network_id");
        c.name = o.optString("name", "Camera");
        c.cameraType = o.optString("camera_type", "default");
        c.productType = o.optString("product_type", "");
        c.serial = o.optString("serial", "");
        c.thumbnailUrl = o.optString("thumb", "");
        c.online = o.optBoolean("online", true);
        c.battery = o.optString("battery", "");
        c.wifiBars = o.optInt("wifi", -1);
        c.armed = o.optBoolean("armed", false);
        return c;
    }

    static String listToJson(List<Camera> list) {
        JSONArray a = new JSONArray();
        for (Camera c : list) a.put(c.toJson());
        return a.toString();
    }

    static List<Camera> listFromJson(String json) {
        List<Camera> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) out.add(fromJson(a.getJSONObject(i)));
        } catch (JSONException | NullPointerException ignored) { }
        return out;
    }
}
