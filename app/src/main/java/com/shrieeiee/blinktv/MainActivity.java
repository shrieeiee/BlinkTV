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

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int COLUMNS = 3;
    private static final long REFRESH_MS = 120_000;

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
    private AlertDialog dialog;

    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (!cameras.isEmpty()) refreshCameras(false);
            ui.postDelayed(this, REFRESH_MS);
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
        findViewById(R.id.btn_settings).setOnClickListener(v -> showSettings());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (!BlinkApi.signedIn(this)) {
            showSignedOut(null);
            return;
        }
        if (cameras.isEmpty()) load(); else refreshCameras(false);
        ui.removeCallbacks(refreshTick);
        ui.postDelayed(refreshTick, REFRESH_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(refreshTick);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_PROG_RED
                || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            showSettings();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ loading

    private void load() {
        if (!BlinkApi.signedIn(this)) { showSignedOut(null); return; }
        showMessage(true, "Loading cameras…", "Asking Blink what you've got.", null, null);
        status.setText("");
        refreshCameras(true);
    }

    /** @param fullReload rebuild the grid; otherwise refresh metadata + thumbnails in place. */
    private void refreshCameras(final boolean fullReload) {
        final int gen = ++loadGeneration;
        BlinkApi.IO.execute(() -> {
            try {
                final List<Camera> list = BlinkApi.cameras(this);
                ui.post(() -> {
                    if (gen != loadGeneration) return;
                    String email = BlinkApi.accountEmail(this);
                    status.setText(list.size() + (list.size() == 1 ? " camera" : " cameras")
                            + (email.isEmpty() ? "" : "  ·  " + email));
                    if (list.isEmpty()) {
                        showMessage(false, "No cameras on this Blink account",
                                "You're signed in but Blink didn't return any cameras.", "Check again", this::load);
                        return;
                    }
                    if (fullReload || !sameIds(list)) renderGrid(list); else updateGrid(list);
                    loadThumbnails();
                });
            } catch (BlinkApi.NotSignedInException e) {
                ui.post(() -> { if (gen == loadGeneration) showSignedOut(e.getMessage()); });
            } catch (BlinkApi.BlinkException e) {
                final String msg = e.getMessage();
                ui.post(() -> {
                    if (gen != loadGeneration) return;
                    if (fullReload || cameras.isEmpty()) {
                        showMessage(false, "Can't reach Blink", msg, "Try again", this::load);
                    } else {
                        status.setText("Couldn't refresh — showing last known cameras");
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

    private void showSignedOut(String why) {
        cameras.clear();
        cards.clear();
        grid.removeAllViews();
        status.setText("");
        showMessage(false, "Sign in to Blink",
                (why == null ? "" : why + "\n\n")
                        + "Use the same email and password as the Blink phone app. Blink will email you a code to finish.\n"
                        + "Tip: the Google TV app on your phone makes typing this a lot less painful.",
                "Sign in", this::showLogin);
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

    private void loadThumbnails() {
        for (int i = 0; i < cameras.size() && i < cards.size(); i++) {
            final Camera cam = cameras.get(i);
            final View card = cards.get(i);
            BlinkApi.IO.execute(() -> {
                byte[] data = BlinkApi.thumbnail(this, cam);
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
        Intent intent = new Intent(this, LiveActivity.class)
                .putExtra(LiveActivity.EXTRA_CAMERAS, Camera.listToJson(cameras))
                .putExtra(LiveActivity.EXTRA_INDEX, index);
        startActivityForResult(intent, 1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (data != null) lastOpened = data.getIntExtra(LiveActivity.EXTRA_INDEX, lastOpened);
        if (lastOpened >= 0 && lastOpened < cards.size()) cards.get(lastOpened).requestFocus();
    }

    // ------------------------------------------------------------------ sign in

    private void showLogin() {
        if (dialog != null && dialog.isShowing()) return;
        LinearLayout box = form();
        box.addView(label("Blink email"));
        final EditText email = field(BlinkApi.accountEmail(this), "you@example.com",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        box.addView(email);
        box.addView(label("Blink password"));
        final EditText password = field("", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setImeOptions(EditorInfo.IME_ACTION_DONE);
        box.addView(password);
        box.addView(label("Your password is only sent to Blink and isn't stored on the TV."));

        dialog = new AlertDialog.Builder(this, R.style.Dialog_BlinkTV)
                .setTitle("Sign in to Blink")
                .setView(box)
                .setPositiveButton("Sign in", (d, w) -> doLogin(email.getText().toString(), password.getText().toString()))
                .setNegativeButton("Cancel", null)
                .create();
        email.setOnEditorActionListener((v, id, ev) -> { password.requestFocus(); return true; });
        dialog.show();
        (email.getText().length() == 0 ? email : password).requestFocus();
    }

    private void doLogin(String email, String password) {
        if (email.trim().isEmpty() || password.isEmpty()) {
            showSignedOut("Need both an email and a password.");
            return;
        }
        showMessage(true, "Signing in…", "Talking to Blink.", null, null);
        BlinkApi.IO.execute(() -> {
            try {
                BlinkApi.LoginResult r = BlinkApi.login(this, email, password);
                ui.post(() -> {
                    if (r == BlinkApi.LoginResult.NEED_CODE) showCodeEntry(null); else load();
                });
            } catch (BlinkApi.BlinkException e) {
                final String msg = e.getMessage();
                ui.post(() -> showMessage(false, "Sign-in didn't work", msg, "Try again", this::showLogin));
            }
        });
    }

    private void showCodeEntry(String error) {
        showMessage(false, "Check your email",
                "Blink has emailed you a code." + (error == null ? "" : "\n\n" + error),
                "Enter the code", () -> showCodeEntry(null));
        if (dialog != null && dialog.isShowing()) return;
        LinearLayout box = form();
        box.addView(label("Code from Blink's email"));
        final EditText code = field("", "123456", InputType.TYPE_CLASS_NUMBER);
        code.setImeOptions(EditorInfo.IME_ACTION_DONE);
        box.addView(code);
        dialog = new AlertDialog.Builder(this, R.style.Dialog_BlinkTV)
                .setTitle("Enter Blink's code")
                .setView(box)
                .setPositiveButton("Verify", (d, w) -> doVerify(code.getText().toString()))
                .setNegativeButton("Start again", (d, w) -> showLogin())
                .create();
        dialog.show();
        code.requestFocus();
    }

    private void doVerify(String code) {
        if (code.trim().isEmpty()) { showCodeEntry("The code was empty."); return; }
        showMessage(true, "Checking the code…", "", null, null);
        BlinkApi.IO.execute(() -> {
            try {
                BlinkApi.verifyCode(this, code);
                ui.post(this::load);
            } catch (BlinkApi.BlinkException e) {
                final String msg = e.getMessage();
                ui.post(() -> showCodeEntry(msg));
            }
        });
    }

    // ------------------------------------------------------------------ settings

    private void showSettings() {
        if (dialog != null && dialog.isShowing()) return;
        LinearLayout box = form();
        String email = BlinkApi.accountEmail(this);
        box.addView(label(BlinkApi.signedIn(this)
                ? "Signed in" + (email.isEmpty() ? "" : " as " + email)
                : "Not signed in"));

        final int[] track = {BlinkApi.audioTrack(this)};
        box.addView(label("Camera microphone channel (only matters if a camera is silent)"));
        final Button audio = new Button(this, null, 0, R.style.TvButton);
        audio.setText("Audio channel " + (track[0] + 1));
        audio.setOnClickListener(v -> {
            track[0] = track[0] == 0 ? 1 : 0;
            audio.setText("Audio channel " + (track[0] + 1));
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        ap.topMargin = dp(6);
        box.addView(audio, ap);

        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.Dialog_BlinkTV)
                .setTitle("Settings")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> BlinkApi.setAudioTrack(this, track[0]))
                .setNegativeButton("Cancel", null);
        if (BlinkApi.signedIn(this)) {
            b.setNeutralButton("Sign out", (d, w) -> {
                BlinkApi.signOut(this);
                showSignedOut(null);
            });
        }
        dialog = b.create();
        dialog.show();
        audio.requestFocus();
    }

    // ------------------------------------------------------------------ bits

    private LinearLayout form() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(28), dp(12), dp(28), dp(4));
        return box;
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
