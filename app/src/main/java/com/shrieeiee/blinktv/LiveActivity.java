package com.shrieeiee.blinktv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen live view. Asks the bridge for a raw MPEG-TS relay first (lowest latency),
 * falls back to HLS if the TV's decoder chokes, and quietly reconnects when Blink
 * cuts the live view at ~5 minutes.
 */
@OptIn(markerClass = UnstableApi.class)
public class LiveActivity extends Activity {

    public static final String EXTRA_IDS = "ids";
    public static final String EXTRA_NAMES = "names";
    public static final String EXTRA_INDEX = "index";

    private static final String PROTO_TS = "ts";
    private static final String PROTO_HLS = "hls";
    private static final long OVERLAY_MS = 5000;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private String[] ids;
    private String[] names;
    private int index;

    private PlayerView playerView;
    private ExoPlayer player;
    private DefaultHttpDataSource.Factory http;

    private View topBar, hint, loader, errorBox;
    private TextView livePill, camName, timer, loaderTitle, loaderBody, errorBody;

    /** Bumped on every (re)start; stale async results compare against it and bail. */
    private int generation = 0;
    private String protocol = PROTO_TS;
    private boolean hlsTried = false;
    private boolean isLive = false;
    private boolean audioOverrideDone = false;
    private boolean started = false;
    private long sessionStartedAt = 0;
    private long liveSince = 0;
    private int quickFailures = 0;
    private String activeCameraId = null;

    private final Runnable hideOverlay = () -> setOverlayVisible(false);

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (isLive && liveSince > 0) {
                long s = (SystemClock.elapsedRealtime() - liveSince) / 1000;
                timer.setText(String.format(Locale.UK, "%02d:%02d", s / 60, s % 60));
            } else {
                timer.setText("");
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live);

        ids = getIntent().getStringArrayExtra(EXTRA_IDS);
        names = getIntent().getStringArrayExtra(EXTRA_NAMES);
        index = getIntent().getIntExtra(EXTRA_INDEX, 0);
        if (ids == null || ids.length == 0) { finish(); return; }
        if (names == null || names.length != ids.length) names = ids;
        if (savedInstanceState != null) index = savedInstanceState.getInt(EXTRA_INDEX, index);
        index = Math.max(0, Math.min(index, ids.length - 1));

        // If this TV already proved it can't do raw TS, go straight to HLS.
        if (getSharedPreferences("player", MODE_PRIVATE).getBoolean("prefer_hls", false)) protocol = PROTO_HLS;

        playerView = findViewById(R.id.player_view);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        topBar = findViewById(R.id.top_bar);
        hint = findViewById(R.id.hint);
        loader = findViewById(R.id.loader);
        errorBox = findViewById(R.id.error_box);
        livePill = findViewById(R.id.live_pill);
        camName = findViewById(R.id.cam_name);
        timer = findViewById(R.id.timer);
        loaderTitle = findViewById(R.id.loader_title);
        loaderBody = findViewById(R.id.loader_body);
        errorBody = findViewById(R.id.error_body);

        http = new DefaultHttpDataSource.Factory()
                .setUserAgent("BlinkTV/1.0 (Android TV)")
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(EXTRA_INDEX, index);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (ids == null || ids.length == 0) return;
        started = true;
        ui.post(tick);
        startStream(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        generation++;
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
        // Give Blink's single live slot back so the phone app / doorbell can use it.
        if (activeCameraId != null) Bridge.stopLive(this, activeCameraId);
        activeCameraId = null;
    }

    // ------------------------------------------------------------------ stream lifecycle

    private void startStream(final boolean reconnect) {
        final int gen = ++generation;
        final String cameraId = ids[index];
        final String previous = activeCameraId;
        final String proto = protocol;
        activeCameraId = cameraId;
        isLive = false;
        liveSince = 0;
        audioOverrideDone = false;
        sessionStartedAt = SystemClock.elapsedRealtime();

        if (player != null) player.stop();
        playerView.setVisibility(View.VISIBLE);
        errorBox.setVisibility(View.GONE);
        camName.setText(names[index]);
        updateHint();
        setPill(reconnect ? "RECONNECTING" : "WAKING", false);
        showLoader(reconnect ? "Reconnecting…" : "Waking " + names[index] + "…",
                reconnect
                        ? "Blink ends live views after about five minutes, grabbing a fresh one."
                        : "Battery cameras take a few seconds to wake. Blink only allows one live view at a time, "
                          + "so close it on your phone if it's open.");
        setOverlayVisible(true);

        Bridge.IO.execute(() -> {
            // Switching camera: free the old one first so Blink doesn't refuse the new one.
            if (previous != null && !previous.equals(cameraId)) {
                try {
                    Bridge.post(this, "/api/live/" + Bridge.enc(previous) + "/stop", null, 8000);
                } catch (Bridge.BridgeException ignored) { }
            }
            try {
                JSONObject body = new JSONObject();
                try { body.put("audio_track", Bridge.audioTrack(this)); } catch (JSONException ignored) { }
                JSONObject res = Bridge.post(this, "/api/live/" + Bridge.enc(cameraId) + "/" + proto, body, 75000);
                String path = PROTO_HLS.equals(proto) ? res.optString("playlist_url") : res.optString("stream_url");
                if (path.isEmpty()) throw new Bridge.BridgeException("Bridge didn't return a stream address.", 500, res);
                final String url = Bridge.abs(this, path);
                ui.post(() -> { if (gen == generation && started) play(url, proto); });
            } catch (Bridge.BridgeException e) {
                final String msg = e.getMessage();
                ui.post(() -> {
                    if (gen != generation || !started) return;
                    if (PROTO_TS.equals(proto) && !hlsTried && e.status != 0) {
                        // Bridge is up but TS start failed: try the HLS route before giving up.
                        switchToHls();
                        return;
                    }
                    showError(msg);
                });
            }
        });
    }

    private void play(String url, String proto) {
        if (player == null) buildPlayer();
        MediaSource source;
        if (PROTO_HLS.equals(proto)) {
            source = new HlsMediaSource.Factory(http)
                    .setExtractorFactory(new DefaultHlsExtractorFactory(
                            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES, true))
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(new MediaItem.Builder()
                            .setUri(url)
                            .setMimeType(MimeTypes.APPLICATION_M3U8)
                            .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                                    .setTargetOffsetMs(2000)
                                    .setMinPlaybackSpeed(0.97f)
                                    .setMaxPlaybackSpeed(1.04f)
                                    .build())
                            .build());
        } else {
            DefaultExtractorsFactory extractors = new DefaultExtractorsFactory()
                    .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                            | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS);
            source = new ProgressiveMediaSource.Factory(http, extractors)
                    .createMediaSource(new MediaItem.Builder()
                            .setUri(url)
                            .setMimeType(MimeTypes.VIDEO_MP2T)
                            .build());
        }
        player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void buildPlayer() {
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                // Small buffers: this is a doorbell, not a film. Start as soon as we have half a second.
                .setBufferDurationsMs(1500, 8000, 500, 1000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        player = new ExoPlayer.Builder(this).setLoadControl(loadControl).build();
        player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        player.addListener(listener);
        playerView.setPlayer(player);
    }

    private void releasePlayer() {
        if (player != null) {
            player.removeListener(listener);
            player.release();
            player = null;
        }
        playerView.setPlayer(null);
    }

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY) {
                if (!isLive) {
                    isLive = true;
                    liveSince = SystemClock.elapsedRealtime();
                    if (PROTO_HLS.equals(protocol) && hlsTried) {
                        getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("prefer_hls", true).apply();
                    }
                }
                hideLoader();
                setPill("● LIVE", true);
                scheduleOverlayHide();
            } else if (state == Player.STATE_BUFFERING && isLive) {
                setPill("BUFFERING", false);
            } else if (state == Player.STATE_ENDED) {
                // Blink's ~5 minute cut, or the bridge's guard. Grab a new session.
                onSessionDropped(null);
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            if (PROTO_TS.equals(protocol) && !hlsTried && !isLive) {
                // Raw TS rejected before a single frame: this TV wants HLS.
                switchToHls();
                return;
            }
            onSessionDropped(error.getErrorCodeName());
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            applyAudioChoice(tracks);
        }
    };

    /** Raw TS keeps both Blink mic tracks; honour the chosen channel if there are two. */
    private void applyAudioChoice(Tracks tracks) {
        if (audioOverrideDone || player == null || PROTO_HLS.equals(protocol)) return;
        List<Tracks.Group> audio = new ArrayList<>();
        for (Tracks.Group g : tracks.getGroups()) if (g.getType() == C.TRACK_TYPE_AUDIO) audio.add(g);
        if (audio.size() < 2) return;
        audioOverrideDone = true;
        int want = Math.min(Bridge.audioTrack(this), audio.size() - 1);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setOverrideForType(new TrackSelectionOverride(audio.get(want).getMediaTrackGroup(), 0))
                .build());
    }

    private void switchToHls() {
        hlsTried = true;
        protocol = PROTO_HLS;
        startStream(true);
    }

    private void onSessionDropped(String why) {
        long lasted = SystemClock.elapsedRealtime() - sessionStartedAt;
        boolean wasLive = isLive;
        if (wasLive && lasted > 30_000) quickFailures = 0; else quickFailures++;
        if (quickFailures >= 3) {
            quickFailures = 0;
            showError("The stream keeps dropping" + (why == null ? "." : " (" + why + ").")
                    + " The camera may be out of Wi-Fi range or the battery low.");
            return;
        }
        startStream(true);
    }

    // ------------------------------------------------------------------ UI

    private void showLoader(String title, String body) {
        loader.setVisibility(View.VISIBLE);
        loaderTitle.setText(title);
        loaderBody.setText(body);
    }

    private void hideLoader() {
        loader.setVisibility(View.GONE);
    }

    private void showError(String msg) {
        isLive = false;
        if (player != null) player.stop();
        hideLoader();
        setPill("OFFLINE", false);
        errorBody.setText(msg == null ? "Unknown error." : msg);
        errorBox.setVisibility(View.VISIBLE);
        setOverlayVisible(true);
        ui.removeCallbacks(hideOverlay);
    }

    private void setPill(String text, boolean live) {
        livePill.setText(text);
        livePill.setBackgroundResource(live ? R.drawable.live_pill : R.drawable.pill);
    }

    private void updateHint() {
        String nav = ids.length > 1 ? "◀ ▶  switch camera (" + (index + 1) + " of " + ids.length + ")      " : "";
        ((TextView) hint).setText(nav + "OK  show/hide info      BACK  camera grid");
    }

    private void setOverlayVisible(boolean visible) {
        float a = visible ? 1f : 0f;
        topBar.animate().alpha(a).setDuration(200).start();
        hint.animate().alpha(a).setDuration(200).start();
    }

    private void scheduleOverlayHide() {
        ui.removeCallbacks(hideOverlay);
        if (isLive) ui.postDelayed(hideOverlay, OVERLAY_MS);
    }

    // ------------------------------------------------------------------ remote

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (ids.length > 1) { index = (index - 1 + ids.length) % ids.length; restartFresh(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (ids.length > 1) { index = (index + 1) % ids.length; restartFresh(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (errorBox.getVisibility() == View.VISIBLE) {
                    restartFresh();
                } else {
                    boolean shown = topBar.getAlpha() > 0.5f;
                    setOverlayVisible(!shown);
                    if (!shown) scheduleOverlayHide();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_INFO:
                setOverlayVisible(true);
                scheduleOverlayHide();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void restartFresh() {
        quickFailures = 0;
        startStream(false);
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_INDEX, index));
        super.onBackPressed();
    }
}
