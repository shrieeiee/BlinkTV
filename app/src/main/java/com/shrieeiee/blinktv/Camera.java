package com.shrieeiee.blinktv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One camera as reported by GET /api/cameras. */
public final class Camera {
    public final String id;
    public final String name;
    public final String kind;
    public final String model;
    public final boolean online;
    public final String battery;
    public final int wifiBars;
    public final Double temperatureC;
    public final boolean armed;
    public final String thumbnailPath;
    public final String thumbnailRev;

    Camera(JSONObject o) {
        id = o.optString("id");
        String display = o.optString("display_name", "");
        name = display.isEmpty() ? o.optString("name", "Camera") : display;
        kind = o.optString("kind", "camera");
        model = o.optString("model", "");
        online = o.optBoolean("online", true);
        battery = o.isNull("battery") ? "" : o.optString("battery", "");
        wifiBars = o.isNull("wifi_bars") ? -1 : o.optInt("wifi_bars", -1);
        temperatureC = o.isNull("temperature_c") ? null : o.optDouble("temperature_c");
        armed = o.optBoolean("armed", false);
        thumbnailPath = o.optString("thumbnail_path", "/api/cameras/" + id + "/thumbnail");
        thumbnailRev = o.optString("thumbnail_rev", "");
    }

    public static List<Camera> listFrom(JSONObject response) {
        List<Camera> out = new ArrayList<>();
        JSONArray arr = response.optJSONArray("cameras");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && !o.optString("id").isEmpty()) out.add(new Camera(o));
        }
        return out;
    }

    /** "Doorbell · Battery OK · Wi-Fi ▮▮▮ · 14°C" */
    public String meta() {
        StringBuilder sb = new StringBuilder();
        sb.append(model.isEmpty() ? pretty(kind) : model);
        if (!battery.isEmpty()) sb.append("  ·  Battery ").append(pretty(battery));
        if (wifiBars >= 0) {
            sb.append("  ·  Wi-Fi ");
            for (int i = 0; i < 5; i++) sb.append(i < wifiBars ? '▮' : '▯');
        }
        if (temperatureC != null && !temperatureC.isNaN()) {
            sb.append("  ·  ").append(Math.round(temperatureC)).append("°C");
        }
        return sb.toString();
    }

    private static String pretty(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
