package com.shrieeiee.blinktv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to Blink's cloud directly — a Java port of the bits of blinkpy we need
 * (OAuth v2 + PKCE login with emailed 2FA, token refresh, homescreen, live view).
 * Every call blocks: run them on {@link #IO}.
 */
public final class BlinkApi {

    public static final ExecutorService IO = Executors.newFixedThreadPool(4);

    // Endpoints and client identity, as used by blinkpy (fronzbot/blinkpy, MIT).
    private static final String OAUTH = "https://api.oauth.blink.com";
    private static final String AUTHORIZE_URL = OAUTH + "/oauth/v2/authorize";
    private static final String SIGNIN_URL = OAUTH + "/oauth/v2/signin";
    private static final String TWOFA_URL = OAUTH + "/oauth/v2/2fa/verify";
    private static final String TOKEN_URL = OAUTH + "/oauth/token";
    private static final String TIER_URL = "https://rest-prod.immedia-semi.com/api/v1/users/tier_info";
    private static final String CLIENT_ID = "ios";
    private static final String REDIRECT_URI = "immedia-blink://applinks.blink.com/signin/callback";
    private static final String SCOPE = "client";
    private static final String UA_WEB = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.1 Mobile/15E148 Safari/604.1";
    private static final String UA_TOKEN = "Blink/2511191620 CFNetwork/3860.200.71 Darwin/25.1.0";
    private static final String UA_API = "27.0ANDROID_28373244";

    private static final String PREFS = "blink";
    private static final CookieManager COOKIES = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

    static {
        // The OAuth pages rely on a session cookie between steps.
        CookieHandler.setDefault(COOKIES);
    }

    // State kept between "sign in" and "enter the emailed code".
    private static String pendingCsrf;
    private static String pendingVerifier;

    /** Serials for regular cameras aren't on the homescreen; cache the extra lookup. */
    private static final Map<String, String> SERIALS = new HashMap<>();

    private BlinkApi() {}

    // ------------------------------------------------------------------ errors

    public static class BlinkException extends Exception {
        public final int status;
        public BlinkException(String msg, int status) { super(msg); this.status = status; }
    }

    /** Session gone (signed out, or Blink killed the refresh token): show the sign-in screen. */
    public static class NotSignedInException extends BlinkException {
        public NotSignedInException(String msg) { super(msg, 401); }
    }

    // ------------------------------------------------------------------ state

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean signedIn(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return !p.getString("refresh_token", "").isEmpty()
                && !p.getString("tier", "").isEmpty()
                && !p.getString("account_id", "").isEmpty();
    }

    public static String accountEmail(Context ctx) {
        return prefs(ctx).getString("email", "");
    }

    public static int audioTrack(Context ctx) {
        return prefs(ctx).getInt("audio_track", 0);
    }

    public static void setAudioTrack(Context ctx, int track) {
        prefs(ctx).edit().putInt("audio_track", track == 1 ? 1 : 0).apply();
    }

    public static synchronized void signOut(Context ctx) {
        // Keep hardware_id and audio choice; drop everything that grants access.
        prefs(ctx).edit()
                .remove("access_token").remove("refresh_token").remove("expires_at")
                .remove("tier").remove("account_id")
                .apply();
        COOKIES.getCookieStore().removeAll();
        SERIALS.clear();
    }

    /** Blink rejects non-UUID hardware ids; keep one stable per install. */
    private static String hardwareId(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String id = p.getString("hardware_id", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
            p.edit().putString("hardware_id", id).apply();
        }
        return id;
    }

    private static String baseUrl(Context ctx) {
        return "https://rest-" + prefs(ctx).getString("tier", "prod") + ".immedia-semi.com";
    }

    // ------------------------------------------------------------------ login

    public enum LoginResult { OK, NEED_CODE }

    public static synchronized LoginResult login(Context ctx, String email, String password) throws BlinkException {
        COOKIES.getCookieStore().removeAll();
        pendingCsrf = null;
        pendingVerifier = null;
        prefs(ctx).edit().putString("email", email.trim()).apply();

        // PKCE pair
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String verifier = b64url(raw);
        String challenge;
        try {
            challenge = b64url(MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BlinkException("This device can't do SHA-256 (?!)", 0);
        }

        // 1. Start the authorisation (sets the session cookie).
        Map<String, String> q = new LinkedHashMap<>();
        q.put("app_brand", "blink");
        q.put("app_version", "50.1");
        q.put("client_id", CLIENT_ID);
        q.put("code_challenge", challenge);
        q.put("code_challenge_method", "S256");
        q.put("device_brand", "Apple");
        q.put("device_model", "iPhone16,1");
        q.put("device_os_version", "26.1");
        q.put("hardware_id", hardwareId(ctx));
        q.put("redirect_uri", REDIRECT_URI);
        q.put("response_type", "code");
        q.put("scope", SCOPE);
        Resp r = http("GET", AUTHORIZE_URL + "?" + form(q), webHeaders("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
                null, true, 20000);
        if (r.status != 200) throw new BlinkException("Blink refused to start sign-in (HTTP " + r.status + ").", r.status);

        // 2. Sign-in page carries the CSRF token in <script id="oauth-args">.
        r = http("GET", SIGNIN_URL, webHeaders("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), null, true, 20000);
        if (r.status != 200) throw new BlinkException("Couldn't load Blink's sign-in page (HTTP " + r.status + ").", r.status);
        String csrf = extractCsrf(r.text());
        if (csrf == null) throw new BlinkException("Blink's sign-in page changed and the app can't read it any more.", 0);

        // 3. Credentials.
        Map<String, String> f = new LinkedHashMap<>();
        f.put("username", email.trim());
        f.put("password", password);
        f.put("csrf-token", csrf);
        Map<String, String> h = webHeaders("*/*");
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("Origin", OAUTH);
        h.put("Referer", SIGNIN_URL);
        r = http("POST", SIGNIN_URL, h, form(f).getBytes(StandardCharsets.UTF_8), false, 20000);

        boolean needCode = r.status == 412;
        if (r.status == 202) {
            JSONObject j = r.json();
            needCode = j.has("tsv_state") || j.has("tsv_methods") || j.has("next_time_in_secs");
        }
        if (needCode) {
            pendingCsrf = csrf;
            pendingVerifier = verifier;
            return LoginResult.NEED_CODE;
        }
        if (r.status >= 300 && r.status < 400) {
            finishLogin(ctx, verifier);
            return LoginResult.OK;
        }
        if (r.status == 400 || r.status == 401 || r.status == 403) {
            throw new BlinkException("Blink didn't accept that email and password.", r.status);
        }
        if (r.status == 429) throw new BlinkException("Blink says too many attempts — wait a few minutes.", 429);
        throw new BlinkException("Sign-in failed (HTTP " + r.status + "). " + trim(r.text()), r.status);
    }

    public static synchronized void verifyCode(Context ctx, String code) throws BlinkException {
        if (pendingCsrf == null || pendingVerifier == null) {
            throw new BlinkException("That sign-in attempt expired — start again.", 0);
        }
        Map<String, String> f = new LinkedHashMap<>();
        f.put("2fa_code", code.trim());
        f.put("csrf-token", pendingCsrf);
        f.put("remember_me", "false");
        Map<String, String> h = webHeaders("*/*");
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("Origin", OAUTH);
        h.put("Referer", SIGNIN_URL);
        Resp r = http("POST", TWOFA_URL, h, form(f).getBytes(StandardCharsets.UTF_8), false, 20000);
        if (r.status != 201 || !"auth-completed".equals(r.json().optString("status"))) {
            throw new BlinkException("Blink didn't accept that code (HTTP " + r.status + ").", r.status);
        }
        finishLogin(ctx, pendingVerifier);
        pendingCsrf = null;
        pendingVerifier = null;
    }

    private static void finishLogin(Context ctx, String verifier) throws BlinkException {
        // 4. Authorization code arrives in the redirect to the app's custom scheme.
        Map<String, String> h = webHeaders("*/*");
        h.put("Referer", SIGNIN_URL);
        Resp r = http("GET", AUTHORIZE_URL, h, null, false, 20000);
        String code = null;
        if (r.status >= 300 && r.status < 400 && r.location != null) {
            code = Uri.parse(r.location).getQueryParameter("code");
        }
        if (code == null || code.isEmpty()) {
            throw new BlinkException("Blink signed you in but didn't hand over an authorisation code (HTTP " + r.status + ").", r.status);
        }

        // 5. Swap it for tokens.
        Map<String, String> f = new LinkedHashMap<>();
        f.put("app_brand", "blink");
        f.put("client_id", CLIENT_ID);
        f.put("code", code);
        f.put("code_verifier", verifier);
        f.put("grant_type", "authorization_code");
        f.put("hardware_id", hardwareId(ctx));
        f.put("redirect_uri", REDIRECT_URI);
        f.put("scope", SCOPE);
        r = http("POST", TOKEN_URL, tokenHeaders(), form(f).getBytes(StandardCharsets.UTF_8), false, 20000);
        if (r.status != 200) throw new BlinkException("Couldn't get tokens from Blink (HTTP " + r.status + ").", r.status);
        saveTokens(ctx, r.json());

        // 6. Which regional server is this account on?
        Map<String, String> th = new HashMap<>();
        th.put("Authorization", "Bearer " + prefs(ctx).getString("access_token", ""));
        th.put("User-Agent", UA_API);
        th.put("Content-Type", "application/x-www-form-urlencoded");
        r = http("GET", TIER_URL, th, null, true, 20000);
        JSONObject tier = r.json();
        String region = tier.optString("tier", "");
        String account = tier.optString("account_id", "");
        if (r.status != 200 || region.isEmpty() || account.isEmpty()) {
            throw new BlinkException("Signed in, but Blink wouldn't say which server your account is on (HTTP " + r.status + ").", r.status);
        }
        prefs(ctx).edit().putString("tier", region).putString("account_id", account).apply();
    }

    private static void saveTokens(Context ctx, JSONObject t) throws BlinkException {
        String access = t.optString("access_token", "");
        if (access.isEmpty()) throw new BlinkException("Blink's token reply was missing the token.", 0);
        SharedPreferences.Editor e = prefs(ctx).edit()
                .putString("access_token", access)
                .putLong("expires_at", System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000L);
        String refresh = t.optString("refresh_token", "");
        if (!refresh.isEmpty()) e.putString("refresh_token", refresh);
        e.apply();
    }

    private static synchronized String accessToken(Context ctx, boolean forceRefresh) throws BlinkException {
        SharedPreferences p = prefs(ctx);
        String refresh = p.getString("refresh_token", "");
        if (refresh.isEmpty()) throw new NotSignedInException("Not signed in.");
        String access = p.getString("access_token", "");
        if (!forceRefresh && !access.isEmpty() && p.getLong("expires_at", 0) - System.currentTimeMillis() > 60_000) {
            return access;
        }
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "refresh_token");
        f.put("refresh_token", refresh);
        f.put("client_id", CLIENT_ID);
        f.put("scope", SCOPE);
        f.put("hardware_id", hardwareId(ctx));
        Resp r = http("POST", TOKEN_URL, tokenHeaders(), form(f).getBytes(StandardCharsets.UTF_8), false, 20000);
        if (r.status == 400 || r.status == 401) {
            signOut(ctx);
            throw new NotSignedInException("Blink ended the session — sign in again.");
        }
        if (r.status != 200) throw new BlinkException("Couldn't refresh the Blink session (HTTP " + r.status + ").", r.status);
        saveTokens(ctx, r.json());
        return prefs(ctx).getString("access_token", "");
    }

    // ------------------------------------------------------------------ API

    private static Resp api(Context ctx, String method, String pathOrUrl, JSONObject body, int timeoutMs)
            throws BlinkException {
        String url = pathOrUrl.startsWith("http") ? pathOrUrl : baseUrl(ctx) + pathOrUrl;
        byte[] bytes = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, String> h = new HashMap<>();
            h.put("Authorization", "Bearer " + accessToken(ctx, attempt > 0));
            h.put("Content-Type", "application/json");
            Resp r = http(method, url, h, bytes, true, timeoutMs);
            if (r.status == 401 && attempt == 0) continue; // token went stale early; refresh once
            if (r.status == 401) {
                signOut(ctx);
                throw new NotSignedInException("Blink ended the session — sign in again.");
            }
            return r;
        }
        throw new BlinkException("unreachable", 0);
    }

    public static List<Camera> cameras(Context ctx) throws BlinkException {
        String account = prefs(ctx).getString("account_id", "");
        Resp r = api(ctx, "GET", "/api/v3/accounts/" + account + "/homescreen", null, 20000);
        if (r.status != 200) throw new BlinkException("Blink wouldn't list your cameras (HTTP " + r.status + ").", r.status);
        JSONObject home = r.json();
        List<Camera> out = new ArrayList<>();
        addAll(ctx, out, home.optJSONArray("doorbells"), "doorbell");
        addAll(ctx, out, home.optJSONArray("cameras"), "default");
        addAll(ctx, out, home.optJSONArray("owls"), "mini");
        return out;
    }

    private static void addAll(Context ctx, List<Camera> out, JSONArray arr, String cameraType) {
        if (arr == null) return;
        String base = baseUrl(ctx);
        String account = prefs(ctx).getString("account_id", "");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || !o.has("id")) continue;
            Camera c = Camera.fromHomescreen(o, cameraType, base, account);
            if (c.serial.isEmpty()) c.serial = serialFor(ctx, c);
            out.add(c);
        }
    }

    private static String serialFor(Context ctx, Camera c) {
        String key = c.networkId + "/" + c.id;
        synchronized (SERIALS) {
            String cached = SERIALS.get(key);
            if (cached != null) return cached;
        }
        try {
            Resp r = api(ctx, "GET", "/network/" + c.networkId + "/camera/" + c.id + "/config", null, 15000);
            JSONArray arr = r.json().optJSONArray("camera");
            String serial = arr != null && arr.length() > 0 ? arr.optJSONObject(0).optString("serial", "") : "";
            if (!serial.isEmpty()) synchronized (SERIALS) { SERIALS.put(key, serial); }
            return serial;
        } catch (BlinkException e) {
            return "";
        }
    }

    /** Snapshot JPEG, or null. */
    public static byte[] thumbnail(Context ctx, Camera c) {
        if (c.thumbnailUrl.isEmpty()) return null;
        try {
            Resp r = api(ctx, "GET", c.thumbnailUrl, null, 30000);
            return r.status == 200 && r.body.length > 0 ? r.body : null;
        } catch (BlinkException e) {
            return null;
        }
    }

    /** Ask Blink to wake the camera and open a live view. Returns {server, command_id, polling_interval, ...}. */
    public static JSONObject startLiveview(Context ctx, Camera c) throws BlinkException {
        String account = prefs(ctx).getString("account_id", "");
        String path;
        switch (c.cameraType) {
            case "mini":
                path = "/api/v2/accounts/" + account + "/networks/" + c.networkId + "/owls/" + c.id + "/liveview";
                break;
            case "doorbell":
                path = "/api/v2/accounts/" + account + "/networks/" + c.networkId + "/doorbells/" + c.id + "/liveview";
                break;
            default:
                path = "/api/v6/accounts/" + account + "/networks/" + c.networkId + "/cameras/" + c.id + "/liveview";
        }
        JSONObject body = new JSONObject();
        try {
            body.put("intent", "liveview");
            body.put("motion_event_start_time", JSONObject.NULL);
        } catch (JSONException ignored) { }
        Resp r = api(ctx, "POST", path, body, 45000);
        JSONObject j = r.json();
        if (r.status == 409 || r.status == 307) {
            throw new BlinkException("Blink says the camera is busy — is live view open on a phone?", r.status);
        }
        if (r.status != 200 || !j.optString("server").startsWith("immis://")) {
            String msg = j.optString("message", "");
            throw new BlinkException("Blink wouldn't start live view"
                    + (msg.isEmpty() ? " (HTTP " + r.status + ")." : ": " + msg), r.status);
        }
        return j;
    }

    /** True while Blink still considers the live-view command running. */
    public static boolean commandRunning(Context ctx, String networkId, long commandId) throws BlinkException {
        Resp r = api(ctx, "GET", "/network/" + networkId + "/command/" + commandId, null, 15000);
        JSONObject j = r.json();
        if (j.optInt("status_code", 0) != 908) return false;
        JSONArray cmds = j.optJSONArray("commands");
        if (cmds == null) return true;
        for (int i = 0; i < cmds.length(); i++) {
            JSONObject c = cmds.optJSONObject(i);
            if (c != null && c.optLong("id") == commandId) {
                String state = c.optString("state_condition", "");
                return "new".equals(state) || "running".equals(state);
            }
        }
        return true;
    }

    public static void commandDone(Context ctx, String networkId, long commandId) {
        try {
            api(ctx, "POST", "/network/" + networkId + "/command/" + commandId + "/done/", null, 10000);
        } catch (BlinkException ignored) { }
    }

    // ------------------------------------------------------------------ HTTP plumbing

    private static final class Resp {
        int status;
        byte[] body = new byte[0];
        String location;

        String text() { return new String(body, StandardCharsets.UTF_8); }

        JSONObject json() {
            try {
                String t = text().trim();
                return t.startsWith("{") ? new JSONObject(t) : new JSONObject();
            } catch (JSONException e) {
                return new JSONObject();
            }
        }
    }

    private static Resp http(String method, String url, Map<String, String> headers, byte[] body,
                             boolean followRedirects, int timeoutMs) throws BlinkException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(followRedirects);
            conn.setConnectTimeout(Math.min(timeoutMs, 15000));
            conn.setReadTimeout(timeoutMs);
            conn.setUseCaches(false);
            for (Map.Entry<String, String> e : headers.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
            if (body != null) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = conn.getOutputStream()) { os.write(body); }
            } else if ("POST".equals(method)) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(0);
                conn.getOutputStream().close();
            }
            Resp r = new Resp();
            r.status = conn.getResponseCode();
            r.location = conn.getHeaderField("Location");
            InputStream in = r.status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in != null) {
                try (InputStream s = in) { r.body = readAll(s); }
            }
            return r;
        } catch (IOException e) {
            throw new BlinkException("Can't reach Blink — is the TV online? (" + e.getClass().getSimpleName() + ")", 0);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Map<String, String> webHeaders(String accept) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA_WEB);
        h.put("Accept", accept);
        h.put("Accept-Language", "en-US,en;q=0.9");
        return h;
    }

    private static Map<String, String> tokenHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA_TOKEN);
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("Accept", "*/*");
        return h;
    }

    private static final Pattern OAUTH_ARGS = Pattern.compile(
            "<script\\b[^>]*\\bid=[\"']oauth-args[\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    static String extractCsrf(String html) {
        Matcher m = OAUTH_ARGS.matcher(html);
        while (m.find()) {
            try {
                String token = new JSONObject(m.group(1).trim()).optString("csrf-token", "");
                if (!token.isEmpty()) return token;
            } catch (JSONException ignored) { }
        }
        return null;
    }

    private static String form(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (sb.length() > 0) sb.append('&');
                sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                        .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
            }
        } catch (java.io.UnsupportedEncodingException ignored) { }
        return sb.toString();
    }

    private static String b64url(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static String trim(String s) {
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
