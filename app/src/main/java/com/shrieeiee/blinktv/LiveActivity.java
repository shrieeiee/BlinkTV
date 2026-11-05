package com.shrieeiee.blinktv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
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
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen live view straight from Blink: asks Blink to start a live view, opens the
 * IMMIS relay ({@link ImmisStream}) and plays the MPEG-TS it unwraps. Reconnects quietly
 * when Blink cuts the live view at ~5 minutes.
 */
@OptIn(markerClass = UnstableApi.class)
public class LiveActivity extends Activity {

    public static final String EXTRA_CAMERAS = "cameras";
    public static final String EXTRA_INDEX = "index";

    private static final String TAG = "BlinkLive";
    private static final long OVERLAY_MS = 5000;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private List<Camera> cams = new ArrayList<>();
    private int index;

    private PlayerView playerView;
    private ExoPlayer player;
    private DefaultHttpDataSource.Factory http;
    private ImmisStream stream;

    private View topBar, hint, loader, errorBox;
    private TextView livePill, camName, timer, loaderTitle, loaderBody, errorBody;

    /** Bumped on every (re)start; stale async results compare against it and bail. */
    private int generation = 0;
    private boolean isLive = false;
    private boolean audioOverrideDone = false;
    private boolean started = false;
    private long sessionStartedAt = 0;
    private long liveSince = 0;
    private int quickFailures = 0;

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

        cams = Camera.listFromJson(getIntent().getStringExtra(EXTRA_CAMERAS));
        index = getIntent().getIntExtra(EXTRA_INDEX, 0);
        if (cams.isEmpty()) { finish(); return; }
        if (savedInstanceState != null) index = savedInstanceState.getInt(EXTRA_INDEX, index);
        index = Math.max(0, Math.min(index, cams.size() - 1));

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

        // Only ever talks to our own loopback server.
        http = new DefaultHttpDataSource.Factory()
                .setUserAgent("BlinkTV/1.0")
                .setConnectTimeoutMs(5000)
                .setReadTimeoutMs(30000);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(EXTRA_INDEX, index);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (cams.isEmpty()) return;
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
        if (stream != null) stream.stop();
        stream = null;
    }

    // ------------------------------------------------------------------ stream lifecycle

    private void startStream(final boolean reconnect) {
        final int gen = ++generation;
        final Camera cam = cams.get(index);
        final ImmisStream previous = stream;
        stream = null;
        isLive = false;
        liveSince = 0;
        audioOverrideDone = false;
        sessionStartedAt = SystemClock.elapsedRealtime();

        if (player != null) player.stop();
        errorBox.setVisibility(View.GONE);
        camName.setText(cam.name);
        updateHint();
        setPill(reconnect ? "RECONNECTING" : "WAKING", false);
        showLoader(reconnect ? "Reconnecting…" : "Waking " + cam.name + "…",
                reconnect
                        ? "Blink ends live views after about five minutes, grabbing a fresh one."
                        : "Battery cameras take a few seconds to wake. Blink only allows one live view at a time, "
                          + "so close it on your phone if it's open.");
        setOverlayVisible(true);

        final Context app = getApplicationContext();
        BlinkApi.IO.execute(() -> {
            // Free the old live view first, or Blink may refuse the next one.
            if (previous != null) previous.stop(true);
            if (gen != generation) return;
            ImmisStream s = null;
            try {
                JSONObject lv = BlinkApi.startLiveview(app, cam);
                final long commandId = lv.optLong("command_id", 0);
                final String network = cam.networkId;
                s = new ImmisStream(lv.optString("server"), cam.serial, commandId,
                        lv.optInt("polling_interval", 15), new ImmisStream.Hooks() {
                    @Override public boolean commandRunning() throws Exception {
                        return BlinkApi.commandRunning(app, network, commandId);
                    }
                    @Override public void commandDone() {
                        BlinkApi.commandDone(app, network, commandId);
                    }
                    @Override public void log(String msg) {
                        Log.i(TAG, cam.name + ": " + msg);
                    }
                });
                final String url = s.start();
                final ImmisStream ready = s;
                ui.post(() -> {
                    if (gen != generation || !started) { ready.stop(); return; }
                    stream = ready;
                    play(url);
                });
            } catch (BlinkApi.NotSignedInException e) {
                if (s != null) s.stop();
                ui.post(() -> { if (gen == generation && started) showError("Signed out of Blink. Go back and sign in again."); });
            } catch (Exception e) {
                if (s != null) s.stop();
                final String msg = e instanceof BlinkApi.BlinkException ? e.getMessage()
                        : "Couldn't connect to Blink's live-view server (" + e.getClass().getSimpleName()
                          + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ").";
                ui.post(() -> { if (gen == generation && started) showError(msg); });
            }
        });
    }

    private void play(String url) {
        if (player == null) buildPlayer();
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                        | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS);
        player.setMediaSource(new ProgressiveMediaSource.Factory(http, extractors)
                .createMediaSource(new MediaItem.Builder()
                        .setUri(url)
                        .setMimeType(MimeTypes.VIDEO_MP2T)
                        .build()));
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
                }
                hideLoader();
                setPill("● LIVE", true);
                scheduleOverlayHide();
            } else if (state == Player.STATE_BUFFERING && isLive) {
                setPill("BUFFERING", false);
            } else if (state == Player.STATE_ENDED) {
                // Blink's ~5 minute cut (relay closed). Grab a new session.
                onSessionDropped(null);
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            onSessionDropped(error.getErrorCodeName());
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            applyAudioChoice(tracks);
        }
    };

    /** Blink can expose two mic tracks; honour the chosen channel if there are two. */
    private void applyAudioChoice(Tracks tracks) {
        if (audioOverrideDone || player == null) return;
        List<Tracks.Group> audio = new ArrayList<>();
        for (Tracks.Group g : tracks.getGroups()) if (g.getType() == C.TRACK_TYPE_AUDIO) audio.add(g);
        if (audio.size() < 2) return;
        audioOverrideDone = true;
        int want = Math.min(BlinkApi.audioTrack(this), audio.size() - 1);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setOverrideForType(new TrackSelectionOverride(audio.get(want).getMediaTrackGroup(), 0))
                .build());
    }

    private void onSessionDropped(String why) {
        long lasted = SystemClock.elapsedRealtime() - sessionStartedAt;
        if (isLive && lasted > 30_000) quickFailures = 0; else quickFailures++;
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
        if (stream != null) { stream.stop(); stream = null; }
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
        String nav = cams.size() > 1 ? "◀ ▶  switch camera (" + (index + 1) + " of " + cams.size() + ")      " : "";
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
                if (cams.size() > 1) { index = (index - 1 + cams.size()) % cams.size(); restartFresh(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (cams.size() > 1) { index = (index + 1) % cams.size(); restartFresh(); }
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
