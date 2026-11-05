package com.shrieeiee.blinktv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny client for the blink-live bridge (softstaticx/blink-tizen-security-dashboard backend).
 * All calls are blocking: run them on {@link #IO}.
 */
public final class Bridge {

    public static final ExecutorService IO = Executors.newFixedThreadPool(4);

    private static final String PREFS = "bridge";

    private Bridge() {}

    // ------------------------------------------------------------------ settings

    public static String url(Context ctx) {
        return prefs(ctx).getString("url", "");
    }

    public static String token(Context ctx) {
        return prefs(ctx).getString("token", "");
    }

    public static int audioTrack(Context ctx) {
        return prefs(ctx).getInt("audio_track", 0);
    }

    public static void save(Context ctx, String url, String token, int audioTrack) {
        prefs(ctx).edit()
                .putString("url", normaliseUrl(url))
                .putString("token", token == null ? "" : token.trim())
                .putInt("audio_track", audioTrack == 1 ? 1 : 0)
                .apply();
    }

    public static boolean configured(Context ctx) {
        return !url(ctx).isEmpty();
    }

    /** "192.168.0.50" -> "http://192.168.0.50:8099", strips trailing slashes. */
    public static String normaliseUrl(String raw) {
        if (raw == null) return "";
        String u = raw.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        // Add the default port if the user only typed a host.
        String afterScheme = u.substring(u.indexOf("://") + 3);
        if (!afterScheme.contains(":") && !afterScheme.contains("/")) u = u + ":8099";
        return u;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Absolute URL for a bridge path ("/api/..."), leaves absolute URLs alone. */
    public static String abs(Context ctx, String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        return url(ctx) + path;
    }

    // ------------------------------------------------------------------ HTTP

    /** Thrown for anything that went wrong talking to the bridge; message is human-readable. */
    public static class BridgeException extends Exception {
        public final int status;
        public final JSONObject body;

        BridgeException(String msg, int status, JSONObject body) {
            super(msg);
            this.status = status;
            this.body = body;
        }
    }

    public static JSONObject get(Context ctx, String path, int timeoutMs) throws BridgeException {
        return request(ctx, "GET", path, null, timeoutMs);
    }

    public static JSONObject post(Context ctx, String path, JSONObject body, int timeoutMs) throws BridgeException {
        return request(ctx, "POST", path, body == null ? new JSONObject() : body, timeoutMs);
    }

    private static JSONObject request(Context ctx, String method, String path, JSONObject body, int timeoutMs)
            throws BridgeException {
        String base = url(ctx);
        if (base.isEmpty()) throw new BridgeException("No bridge address set.", 0, null);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + path).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(Math.min(timeoutMs, 6000));
            conn.setReadTimeout(timeoutMs);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            String token = token(ctx);
            if (!token.isEmpty()) conn.setRequestProperty("X-Bridge-Token", token);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
            JSONObject json;
            try {
                json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            } catch (JSONException e) {
                json = new JSONObject();
            }
            if (status >= 400) {
                String msg = json.optString("error", json.optString("message", ""));
                if (msg.isEmpty()) msg = "Bridge returned HTTP " + status + ".";
                throw new BridgeException(msg, status, json);
            }
            return json;
        } catch (IOException e) {
            throw new BridgeException("Can't reach the bridge at " + base + " (" + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : "") + ").", 0, null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Raw bytes (thumbnails). Returns null on any failure. */
    public static byte[] bytes(Context ctx, String path, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(abs(ctx, path)).openConnection();
            conn.setConnectTimeout(Math.min(timeoutMs, 6000));
            conn.setReadTimeout(timeoutMs);
            conn.setUseCaches(false);
            String token = token(ctx);
            if (!token.isEmpty()) conn.setRequestProperty("X-Bridge-Token", token);
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream()) {
                return readAll(in);
            }
        } catch (IOException e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Fire-and-forget POST, used to release Blink's single live slot. */
    public static void stopLive(Context ctx, String cameraId) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            try {
                post(app, "/api/live/" + enc(cameraId) + "/stop", null, 8000);
            } catch (BridgeException ignored) {
                // Bridge watchdog cleans up anyway.
            }
        });
    }

    public static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
