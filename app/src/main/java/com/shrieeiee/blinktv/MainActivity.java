package com.shrieeiee.blinktv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int COLUMNS = 3;
    private static final long THUMB_REFRESH_MS = 90_000;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private LinearLayout grid;
    private View scroll;
    private View messageBox;
    private ProgressBar messageSpinner;
    private TextView messageTitle;
    private TextView messageBody;
    private Button messageAction;

    private final List<Camera> cameras = new ArrayList<>();
    private final List<View> cards = new ArrayList<>();
    private int loadGeneration = 0;
    private int lastOpened = 0;
    private boolean resumed = false;
    private AlertDialog settingsDialog;

    private final Runnable thumbTick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (!cameras.isEmpty()) refreshCameras(false);
            ui.postDelayed(this, THUMB_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        grid = findViewById(R.id.grid);
        scroll = findViewById(R.id.scroll);
        messageBox = findViewById(R.id.message_box);
        messageSpinner = findViewById(R.id.message_spinner);
        messageTitle = findViewById(R.id.message_title);
        messageBody = findViewById(R.id.message_body);
        messageAction = findViewById(R.id.message_action);

        findViewById(R.id.btn_refresh).setOnClickListener(v -> load());
        findViewById(R.id.btn_settings).setOnClickListener(v -> showSettings(false));
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (!Bridge.configured(this)) {
            showMessage(false, "Set up the bridge",
                    "Blink Live talks to a small bridge running on a computer on your network "
                            + "(the blink-live Docker container). Enter its address to get going.",
                    "Enter bridge address", () -> showSettings(true));
            showSettings(true);
            return;
        }
        if (cameras.isEmpty()) load(); else refreshCameras(false);
        ui.removeCallbacks(thumbTick);
        ui.postDelayed(thumbTick, THUMB_REFRESH_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(thumbTick);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_PROG_RED
                || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            showSettings(false);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ loading

    private void load() {
        if (!Bridge.configured(this)) { showSettings(true); return; }
        showMessage(true, "Connecting…", Bridge.url(this), null, null);
        status.setText("");
        refreshCameras(true);
    }

    /** @param fullReload rebuild the grid; otherwise just refresh metadata + thumbnails in place. */
    private void refreshCameras(final boolean fullReload) {
        final int gen = ++loadGeneration;
        Bridge.IO.execute(() -> {
            try {
                JSONObject health = Bridge.get(this, "/api/health", 8000);
                JSONObject blink = health.optJSONObject("blink");
                boolean ready = blink != null && blink.optBoolean("ready", false);
                if (!ready) {
                    String err = blink == null ? "" : blink.optString("last_error", "");
                    ui.post(() -> { if (gen == loadGeneration) showNotLinked(err); });
                    return;
                }
                final List<Camera> list = Camera.listFrom(Bridge.get(this, "/api/cameras", 15000));
                final String account = blink.optString("account", "");
                ui.post(() -> {
                    if (gen != loadGeneration) return;
                    status.setText(list.size() + (list.size() == 1 ? " camera" : " cameras")
                            + (account.isEmpty() || "null".equals(account) ? "" : "  ·  " + account)
                            + "  ·  " + Bridge.url(this).replaceFirst("^https?://", ""));
                    if (list.isEmpty()) {
                        showMessage(false, "No cameras on this Blink account",
                                "The bridge is linked but Blink didn't return any cameras.", "Check again", this::load);
                        return;
                    }
                    if (fullReload || !sameIds(list)) renderGrid(list); else updateGrid(list);
                    loadThumbnails(gen);
                });
            } catch (Bridge.BridgeException e) {
                final String msg = e.getMessage();
                ui.post(() -> {
                    if (gen != loadGeneration) return;
                    if (e.status == 401) {
                        showMessage(false, "Bridge wants a token",
                                "This bridge has BRIDGE_TOKEN set. Put the same value in Settings.",
                                "Open settings", () -> showSettings(false));
                    } else if (fullReload || cameras.isEmpty()) {
                        showMessage(false, "Bridge offline", msg
                                        + "\n\nCheck the blink-live container is running and the TV is on the same network.",
                                "Try again", this::load);
                    } else {
                        status.setText("Bridge offline — showing last known cameras");
                    }
                });
            }
        });
    }

    private boolean sameIds(List<Camera> list) {
        if (list.size() != cameras.size()) return false;
        for (int i = 0; i < list.size(); i++) if (!list.get(i).id.equals(cameras.get(i).id)) return false;
        return true;
    }

    private void showNotLinked(String lastError) {
        String setup = Bridge.url(this) + "/setup";
        showMessage(false, "Link your Blink account",
                "On your phone or PC, open\n\n" + setup + "\n\nsign in with your Blink account and enter the code Blink emails you. "
                        + "You only have to do this once."
                        + (lastError == null || lastError.isEmpty() || "null".equals(lastError) ? "" : "\n\nLast error: " + lastError),
                "I've done it — check again", this::load);
    }

    private void showMessage(boolean spinner, String title, String body, String action, Runnable onAction) {
        scroll.setVisibility(View.GONE);
        messageBox.setVisibility(View.VISIBLE);
        messageSpinner.setVisibility(spinner ? View.VISIBLE : View.GONE);
        messageTitle.setText(title);
        messageBody.setText(body == null ? "" : body);
        if (action != null && onAction != null) {
            messageAction.setText(action);
            messageAction.setVisibility(View.VISIBLE);
            messageAction.setOnClickListener(v -> onAction.run());
            messageAction.requestFocus();
        } else {
            messageAction.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------ grid

    private void renderGrid(List<Camera> list) {
        cameras.clear();
        cameras.addAll(list);
        cards.clear();
        grid.removeAllViews();
        messageBox.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);

        int gap = dp(14);
        int usable = getResources().getDisplayMetrics().widthPixels - dp(112);
        int cardW = (usable - gap * (COLUMNS - 1)) / COLUMNS;
        int thumbH = (cardW - dp(20)) * 9 / 16;

        LayoutInflater inf = LayoutInflater.from(this);
        LinearLayout row = null;
        for (int i = 0; i < cameras.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rp.topMargin = gap;
                grid.addView(row, rp);
            }
            final int index = i;
            View card = inf.inflate(R.layout.item_camera, row, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % COLUMNS != 0) lp.leftMargin = gap;
            card.findViewById(R.id.thumb_frame).getLayoutParams().height = thumbH;
            card.setOnClickListener(v -> openLive(index));
            card.setOnFocusChangeListener((v, has) -> {
                v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(140).start();
                v.setElevation(has ? dp(8) : 0);
            });
            row.addView(card, lp);
            cards.add(card);
            bindCard(card, cameras.get(i), i);
        }
        // Fill the last row so cards keep their width alignment.
        int focus = Math.min(lastOpened, cards.size() - 1);
        if (focus >= 0) cards.get(focus).requestFocus();
    }

    private void updateGrid(List<Camera> list) {
        cameras.clear();
        cameras.addAll(list);
        for (int i = 0; i < cards.size() && i < cameras.size(); i++) bindCard(cards.get(i), cameras.get(i), i);
        if (scroll.getVisibility() != View.VISIBLE) {
            messageBox.setVisibility(View.GONE);
            scroll.setVisibility(View.VISIBLE);
            int focus = Math.min(lastOpened, cards.size() - 1);
            if (focus >= 0) cards.get(focus).requestFocus();
        }
    }

    private void bindCard(View card, Camera cam, int index) {
        ((TextView) card.findViewById(R.id.name)).setText(cam.name);
        ((TextView) card.findViewById(R.id.meta)).setText(cam.meta());
        ((TextView) card.findViewById(R.id.channel)).setText(String.format("CH %02d", index + 1));
        TextView badge = card.findViewById(R.id.badge);
        if (cam.online) {
            badge.setText("● ONLINE");
            badge.setTextColor(getColor(R.color.ok));
        } else {
            badge.setText("● OFFLINE");
            badge.setTextColor(getColor(R.color.live));
        }
        card.setContentDescription(cam.name + (cam.online ? "" : ", offline"));
    }

    private void loadThumbnails(final int gen) {
        for (int i = 0; i < cameras.size(); i++) {
            final Camera cam = cameras.get(i);
            final View card = cards.get(i);
            Bridge.IO.execute(() -> {
                byte[] data = Bridge.bytes(this, cam.thumbnailPath + "?rev=" + Bridge.enc(cam.thumbnailRev), 20000);
                if (data == null) return;
                final Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bmp == null) return;
                ui.post(() -> {
                    if (!cards.contains(card)) return; // grid was rebuilt meanwhile
                    ((ImageView) card.findViewById(R.id.thumb)).setImageBitmap(bmp);
                    card.findViewById(R.id.placeholder).setVisibility(View.GONE);
                });
            });
        }
    }

    private void openLive(int index) {
        lastOpened = index;
        String[] ids = new String[cameras.size()];
        String[] names = new String[cameras.size()];
        for (int i = 0; i < cameras.size(); i++) {
            ids[i] = cameras.get(i).id;
            names[i] = cameras.get(i).name;
        }
        Intent intent = new Intent(this, LiveActivity.class)
                .putExtra(LiveActivity.EXTRA_IDS, ids)
                .putExtra(LiveActivity.EXTRA_NAMES, names)
                .putExtra(LiveActivity.EXTRA_INDEX, index);
        startActivityForResult(intent, 1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (data != null) lastOpened = data.getIntExtra(LiveActivity.EXTRA_INDEX, lastOpened);
        if (lastOpened >= 0 && lastOpened < cards.size()) cards.get(lastOpened).requestFocus();
    }

    // ------------------------------------------------------------------ settings

    private void showSettings(final boolean firstRun) {
        if (settingsDialog != null && settingsDialog.isShowing()) return;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(28), dp(12), dp(28), dp(4));

        box.addView(label("Bridge address  (e.g. 192.168.0.50 — port 8099 is added for you)"));
        final EditText url = field(Bridge.url(this), "192.168.0.x", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(url);

        box.addView(label("Bridge token  (leave blank unless you set BRIDGE_TOKEN)"));
        final EditText token = field(Bridge.token(this), "optional", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        box.addView(token);

        final int[] track = {Bridge.audioTrack(this)};
        box.addView(label("Camera microphone channel (only matters if a camera is silent)"));
        final Button audio = new Button(this, null, 0, R.style.TvButton);
        audio.setText("Audio channel " + (track[0] + 1));
        audio.setOnClickListener(v -> {
            track[0] = track[0] == 0 ? 1 : 0;
            audio.setText("Audio channel " + (track[0] + 1));
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        ap.topMargin = dp(6);
        box.addView(audio, ap);

        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.Dialog_BlinkTV)
                .setTitle(firstRun ? "Where's your blink-live bridge?" : "Settings")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String u = url.getText().toString();
                    if (Bridge.normaliseUrl(u).isEmpty()) return;
                    Bridge.save(this, u, token.getText().toString(), track[0]);
                    cameras.clear();
                    load();
                })
                .setNegativeButton("Cancel", null);
        if (!firstRun && Bridge.configured(this)) {
            b.setNeutralButton("Re-link Blink", (d, w) -> showNotLinked(null));
        }
        settingsDialog = b.create();
        url.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE) {
                token.requestFocus();
                return true;
            }
            return false;
        });
        settingsDialog.show();
        url.requestFocus();
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(getColor(R.color.ink_dim));
        t.setPadding(0, dp(14), 0, dp(2));
        return t;
    }

    private EditText field(String value, String hint, int inputType) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setHint(hint);
        e.setText(value);
        e.setSelection(e.getText().length());
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        e.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        return e;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
