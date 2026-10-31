package com.exhish;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final String RS = "\u001b[0m";
    private static final String RED = "\u001b[91m";
    private static final String GRN = "\u001b[92m";
    private static final String YEL = "\u001b[93m";
    private static final String CYN = "\u001b[96m";
    private static final String MAG = "\u001b[95m";
    private static final String DIM = "\u001b[90m";
    private static final int TRANSCRIPT = 2000;

    private static final class Tweaks {
        volatile int scrollPct = 100;
        volatile boolean scrollInvert = false;
        volatile int scrollback = 2000;
        volatile int cursorStyle = 0;
        volatile boolean cursorBlink = false;
        volatile int blinkRate = 500;
        volatile boolean keepScreenOn = true;
        volatile int extraMode = 0;
        volatile boolean zoomRow = true;
        volatile boolean haptic = true;
        volatile int repeatDelay = 400;
        volatile int repeatRate = 80;
        volatile boolean popups = true;
        volatile boolean volumeKeys = false;
        volatile boolean backEsc = false;
        volatile int bell = 0;
        volatile boolean showKeyboard = true;
        volatile boolean tapKeyboard = true;
        volatile int orientation = 0;
    }

    private final Tweaks tw = new Tweaks();
    private long tweakStamp = -1;
    private boolean kbOpen = false;
    private boolean awakeOn = false;
    private int appliedOrientation = -1;
    private float scrollDownY;
    private boolean scrollMulti;
    private LinearLayout zoomRowView;
    private TerminalView tv;
    private LinearLayout extra;
    private Button ctrlB, altB;
    private final Client client = new Client();
    private volatile TerminalSession placeholder;
    private volatile TerminalSession shell;
    private volatile boolean shellPhase = false;
    private volatile boolean waiting = false;
    private final Mod ctrl = new Mod();
    private final Mod alt = new Mod();
    private volatile int pid = -1;
    private int textPx;
    private final Handler poll = new Handler();
    private long fontStamp = 0, sizeStamp = 0;
    private final Runnable pollCfg = new Runnable() {
        @Override
        public void run() {
            File d = new File(getFilesDir(), "rootfs/root/.exhish");
            File f = new File(d, "font.ttf");
            long fs = f.exists() ? f.lastModified() + f.length() : 0;
            if (fs != fontStamp) {
                fontStamp = fs;
                try {
                    tv.setTypeface(fs == 0 ? Typeface.MONOSPACE : Typeface.createFromFile(f));
                } catch (Throwable ignored) {
                }
            }
            File z = new File(d, "fontsize");
            long zs = z.exists() ? z.lastModified() + z.length() : 0;
            if (zs != sizeStamp) {
                sizeStamp = zs;
                try {
                    int sp = Integer.parseInt(readAll(z).trim());
                    float sd = getResources().getDisplayMetrics().scaledDensity;
                    textPx = (int) (Math.max(3, Math.min(40, sp)) * sd);
                    tv.setTextSize(textPx);
                } catch (Exception ignored) {
                }
            }
            File tf = new File(d, "tweaks");
            long ts = tf.exists() ? tf.lastModified() + tf.length() : 0;
            if (ts != tweakStamp) {
                tweakStamp = ts;
                loadTweaks();
                applyTweaks();
            }
            syncAwake();
            poll.postDelayed(this, 1500);
        }
    };
    private long shellStart;
    private long lastDraw;
    private final LinkedBlockingQueue<String> keys = new LinkedBlockingQueue<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        loadTweaks();
        Native.init(24, 80);
        textPx = (int) (14 * getResources().getDisplayMetrics().scaledDensity);
        tv = new TerminalView(this, null);
        tv.setTerminalViewClient(client);
        tv.setTextSize(textPx);
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setOnTouchListener((v, e) -> {
            float f = tw.scrollPct / 100f * (tw.scrollInvert ? -1f : 1f);
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                scrollMulti = false;
                scrollDownY = e.getY();
                return false;
            }
            if (a == MotionEvent.ACTION_POINTER_DOWN || e.getPointerCount() != 1) {
                scrollMulti = true;
                return false;
            }
            if (f == 1f || scrollMulti) return false;
            if (a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_UP) {
                MotionEvent m = MotionEvent.obtain(e);
                m.setLocation(e.getX(), scrollDownY + (e.getY() - scrollDownY) * f);
                v.onTouchEvent(m);
                m.recycle();
                return true;
            }
            return false;
        });
        extra = buildExtra();
        extra.setVisibility(View.GONE);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.addView(tv, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(extra, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect r = new Rect();
            root.getWindowVisibleDisplayFrame(r);
            int full = root.getRootView().getHeight();
            kbOpen = full - r.height() > full * 0.15f;
            updateExtra();
        });
        tv.requestFocus();
        if (tw.showKeyboard) {
            tv.postDelayed(() -> {
                InputMethodManager m = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                m.showSoftInput(tv, 0);
            }, 400);
        }
        applyTweaks();
        File flag = new File(getFilesDir(), "rootfs/etc/exhish/fs_enabled");
        if (flag.exists() && Build.VERSION.SDK_INT >= 23
            && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {
                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
        poll.post(pollCfg);
        new Thread(this::boot).start();
    }

    @Override
    protected void onPause() {
        syncAwake();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (awakeOn) {
            moveTaskToBack(true);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        stopService(new Intent(this, WakeService.class));
        poll.removeCallbacks(pollCfg);
        if (shell != null) shell.finishIfRunning();
        if (placeholder != null) placeholder.finishIfRunning();
        if (pid > 0) Native.kill(pid);
        super.onDestroy();
    }

    private static int tnum(Map<String, String> m, String k, int d, int lo, int hi) {
        try {
            int v = Integer.parseInt(m.get(k));
            return Math.max(lo, Math.min(hi, v));
        } catch (Exception e) {
            return d;
        }
    }

    private static boolean tflag(Map<String, String> m, String k, boolean d) {
        String v = m.get(k);
        if (v == null) return d;
        return v.equals("1") || v.equals("on") || v.equals("yes") || v.equals("true");
    }

    private static int tpick(Map<String, String> m, String k, String[] opts, int d) {
        String v = m.get(k);
        if (v == null) return d;
        for (int i = 0; i < opts.length; i++) if (opts[i].equals(v)) return i;
        return d;
    }

    private void loadTweaks() {
        File f = new File(getFilesDir(), "rootfs/root/.exhish/tweaks");
        Map<String, String> m = new HashMap<>();
        if (f.exists()) {
            try {
                for (String ln : readAll(f).split("\n")) {
                    int i = ln.indexOf('=');
                    if (i > 0) m.put(ln.substring(0, i).trim(), ln.substring(i + 1).trim());
                }
            } catch (IOException ignored) {
            }
        }
        tw.scrollPct = tnum(m, "scroll_speed", 100, 10, 500);
        tw.scrollInvert = tflag(m, "scroll_invert", false);
        tw.scrollback = tnum(m, "scrollback", 2000, 100, 50000);
        tw.cursorStyle = tpick(m, "cursor_style", new String[] {"block", "underline", "bar"}, 0);
        tw.cursorBlink = tflag(m, "cursor_blink", false);
        tw.blinkRate = tnum(m, "cursor_blink_rate", 500, 100, 2000);
        tw.keepScreenOn = tflag(m, "keep_screen_on", true);
        tw.extraMode = tpick(m, "extra_keys", new String[] {"auto", "always", "never"}, 0);
        tw.zoomRow = tflag(m, "zoom_row", true);
        tw.haptic = tflag(m, "key_haptic", true);
        tw.repeatDelay = tnum(m, "key_repeat_delay", 400, 100, 1500);
        tw.repeatRate = tnum(m, "key_repeat_rate", 80, 20, 500);
        tw.popups = tflag(m, "popups", true);
        tw.volumeKeys = tflag(m, "volume_keys", false);
        tw.backEsc = tflag(m, "back_is_esc", false);
        tw.bell = tpick(m, "bell", new String[] {"off", "haptic"}, 0);
        tw.showKeyboard = tflag(m, "show_keyboard", true);
        tw.tapKeyboard = tflag(m, "tap_keyboard", true);
        tw.orientation = tpick(m, "orientation", new String[] {"auto", "portrait", "landscape"}, 0);
    }

    private void updateExtra() {
        if (extra == null) return;
        boolean show = tw.extraMode == 1 || (tw.extraMode == 0 && kbOpen);
        extra.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void applyCursorBlink() {
        try {
            if (tw.cursorBlink) {
                tv.getClass().getMethod("setTerminalCursorBlinkerRate", int.class).invoke(tv, tw.blinkRate);
            }
            tv.getClass().getMethod("setTerminalCursorBlinkerState", boolean.class, boolean.class)
                .invoke(tv, tw.cursorBlink, true);
        } catch (Throwable ignored) {
        }
    }

    private void applyTweaks() {
        if (tw.keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (appliedOrientation != tw.orientation) {
            appliedOrientation = tw.orientation;
            setRequestedOrientation(tw.orientation == 1 ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : tw.orientation == 2 ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        if (zoomRowView != null) zoomRowView.setVisibility(tw.zoomRow ? View.VISIBLE : View.GONE);
        updateExtra();
        applyCursorBlink();
    }

    private void syncAwake() {
        boolean want = new File(getFilesDir(), "rootfs/root/.exhish/stayawake").exists();
        if (want == awakeOn) return;
        Intent i = new Intent(this, WakeService.class);
        try {
            if (want) {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            } else {
                stopService(i);
            }
            awakeOn = want;
        } catch (Exception e) {
            Log.w("exhish", "wake service", e);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout buildExtra() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF1B1B1B);
        box.addView(row(new String[] {"ESC", "/", "-", "HOME", "\u2191", "END", "PGUP"}));
        box.addView(row(new String[] {"TAB", "CTRL", "ALT", "\u2190", "\u2193", "\u2192", "PGDN"}));
        zoomRowView = row(new String[] {"A-", "A+"});
        box.addView(zoomRowView);
        return box;
    }

    private static final int COL_ACTIVE = 0xFF80CBC4;
    private static final int COL_LOCKED_BG = 0xFF2E4A47;

    private static Drawable keyBg(int normal) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_pressed}, new ColorDrawable(0xFF3A3A3A));
        bg.addState(new int[] {}, new ColorDrawable(normal));
        return bg;
    }

    private LinearLayout row(String[] labels) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (final String l : labels) {
            Button b = new Button(this);
            b.setText(l);
            b.setAllCaps(false);
            b.setTextSize(14);
            b.setTextColor(Color.WHITE);
            b.setBackground(keyBg(0x00000000));
            b.setPadding(0, 0, 0, 0);
            b.setMinHeight(0);
            b.setMinWidth(0);
            b.setFocusable(false);
            b.setHapticFeedbackEnabled(true);
            b.setGravity(Gravity.CENTER);
            b.setOnTouchListener(new KeyTouch(l));
            if (l.equals("CTRL")) ctrlB = b;
            if (l.equals("ALT")) altB = b;
            r.addView(b, new LinearLayout.LayoutParams(0, dp(40), 1f));
        }
        return r;
    }

    private static final class Mod {
        volatile boolean active;
        volatile boolean locked;

        void tap() {
            if (active) {
                active = false;
                locked = false;
            } else {
                active = true;
            }
        }

        void lock() {
            active = true;
            locked = true;
        }

        boolean read() {
            boolean v = active;
            if (v && !locked) active = false;
            return v;
        }
    }

    private static boolean repeats(String l) {
        switch (l) {
            case "\u2191": case "\u2193": case "\u2190": case "\u2192":
            case "PGUP": case "PGDN": case "A-": case "A+": return true;
            default: return false;
        }
    }

    private static String popupFor(String l) {
        switch (l) {
            case "-": return "|";
            case "/": return "\\";
            default: return null;
        }
    }

    private final class KeyTouch implements View.OnTouchListener {
        private final String key;
        private final boolean mod;
        private final boolean repeat;
        private String popup;
        private final Handler h = new Handler();
        private float downY;
        private boolean longDone;
        private boolean swiped;
        private PopupWindow pw;
        private View anchor;

        private final Runnable again = new Runnable() {
            @Override
            public void run() {
                press(key);
                h.postDelayed(this, tw.repeatRate);
            }
        };

        private final Runnable lockIt = new Runnable() {
            @Override
            public void run() {
                longDone = true;
                if (anchor != null) anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                modOf(key).lock();
                refreshMods();
            }
        };

        KeyTouch(String key) {
            this.key = key;
            this.mod = key.equals("CTRL") || key.equals("ALT");
            this.repeat = repeats(key);
            this.popup = null;
        }

        private void showPopup(View v) {
            if (pw != null) return;
            TextView t = new TextView(MainActivity.this);
            t.setText(popup);
            t.setTextColor(Color.WHITE);
            t.setTextSize(18);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(0xFF3A3A3A);
            pw = new PopupWindow(t, v.getWidth(), v.getHeight());
            pw.setTouchable(false);
            pw.showAsDropDown(v, 0, -2 * v.getHeight());
        }

        private void hidePopup() {
            if (pw != null) {
                pw.dismiss();
                pw = null;
            }
        }

        private void stop() {
            h.removeCallbacks(again);
            h.removeCallbacks(lockIt);
            hidePopup();
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    anchor = v;
                    downY = e.getY();
                    longDone = false;
                    swiped = false;
                    v.setPressed(true);
                    popup = tw.popups ? popupFor(key) : null;
                    if (tw.haptic) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    stop();
                    if (repeat) {
                        press(key);
                        h.postDelayed(again, tw.repeatDelay);
                    } else if (mod) {
                        h.postDelayed(lockIt, ViewConfiguration.getLongPressTimeout());
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (popup != null) {
                        boolean up = downY - e.getY() > dp(30);
                        if (up && !swiped) {
                            swiped = true;
                            showPopup(v);
                        } else if (!up && swiped) {
                            swiped = false;
                            hidePopup();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    stop();
                    if (repeat) return true;
                    if (mod) {
                        if (!longDone) {
                            modOf(key).tap();
                            refreshMods();
                        }
                    } else if (swiped) {
                        press(popup);
                    } else {
                        press(key);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    stop();
                    return true;
                default:
                    return true;
            }
        }
    }

    private Mod modOf(String key) {
        return key.equals("CTRL") ? ctrl : alt;
    }

    private void refreshMods() {
        runOnUiThread(() -> {
            paintMod(ctrlB, ctrl);
            paintMod(altB, alt);
        });
    }

    private static void paintMod(Button b, Mod m) {
        if (b == null) return;
        b.setTextColor(m.active ? COL_ACTIVE : Color.WHITE);
        b.setBackground(keyBg(m.locked ? COL_LOCKED_BG : 0x00000000));
    }

    private void sendKey(int keyCode) {
        TerminalSession t = shell;
        if (!shellPhase || t == null) return;
        boolean c = ctrl.read();
        boolean a = alt.read();
        refreshMods();
        int meta = 0;
        if (c) meta |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        if (a) meta |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        long now = SystemClock.uptimeMillis();
        tv.onKeyDown(keyCode, new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta));
        tv.onKeyUp(keyCode, new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta));
    }

    private void sendText(String s) {
        TerminalSession t = shell;
        if (!shellPhase || t == null || s.isEmpty()) return;
        boolean c = ctrl.read();
        boolean a = alt.read();
        refreshMods();
        int cp = s.codePointAt(0);
        if (c) {
            if (cp >= 'a' && cp <= 'z') cp = cp - 'a' + 1;
            else if (cp >= 'A' && cp <= 'Z') cp = cp - 'A' + 1;
            else if (cp == ' ' || cp == '2') cp = 0;
            else if (cp == '[' || cp == '3') cp = 27;
            else if (cp == '\\' || cp == '4') cp = 28;
            else if (cp == ']' || cp == '5') cp = 29;
            else if (cp == '^' || cp == '6') cp = 30;
            else if (cp == '_' || cp == '7' || cp == '/') cp = 31;
            else if (cp == '8') cp = 127;
        }
        t.write((a ? "\033" : "") + new String(Character.toChars(cp)));
    }

    private void press(String l) {
        if (!shellPhase) return;
        switch (l) {
            case "ESC": sendKey(KeyEvent.KEYCODE_ESCAPE); break;
            case "TAB": sendKey(KeyEvent.KEYCODE_TAB); break;
            case "HOME": sendKey(KeyEvent.KEYCODE_MOVE_HOME); break;
            case "END": sendKey(KeyEvent.KEYCODE_MOVE_END); break;
            case "PGUP": sendKey(KeyEvent.KEYCODE_PAGE_UP); break;
            case "PGDN": sendKey(KeyEvent.KEYCODE_PAGE_DOWN); break;
            case "\u2191": sendKey(KeyEvent.KEYCODE_DPAD_UP); break;
            case "\u2193": sendKey(KeyEvent.KEYCODE_DPAD_DOWN); break;
            case "\u2192": sendKey(KeyEvent.KEYCODE_DPAD_RIGHT); break;
            case "\u2190": sendKey(KeyEvent.KEYCODE_DPAD_LEFT); break;
            case "A-": zoom(-2); break;
            case "A+": zoom(2); break;
            default: sendText(l); break;
        }
    }

    private void zoom(int d) {
        int px = (int) (textPx + d * getResources().getDisplayMetrics().scaledDensity);
        int lo = (int) (3 * getResources().getDisplayMetrics().scaledDensity);
        int hi = (int) (40 * getResources().getDisplayMetrics().scaledDensity);
        textPx = Math.max(lo, Math.min(hi, px));
        tv.setTextSize(textPx);
    }

    private void say(String s) {
        final byte[] b = s.getBytes(StandardCharsets.UTF_8);
        final TerminalSession t = placeholder;
        runOnUiThread(() -> {
            if (t == null) return;
            TerminalEmulator em = t.getEmulator();
            if (em == null) return;
            em.append(b, b.length);
            tv.onScreenUpdated();
        });
    }

    private void startPlaceholder() {
        final CountDownLatch latch = new CountDownLatch(1);
        runOnUiThread(() -> {
            TerminalSession old = placeholder;
            TerminalSession t = new TerminalSession("/system/bin/sleep", getFilesDir().getPath(),
                new String[] {"sleep", "86400"},
                new String[] {"PATH=/system/bin", "TERM=xterm-256color"}, TRANSCRIPT, client);
            placeholder = t;
            tv.attachSession(t);
            if (old != null) old.finishIfRunning();
            latch.countDown();
        });
        try {
            latch.await();
            for (int i = 0; i < 300 && placeholder.getEmulator() == null; i++) Thread.sleep(50);
        } catch (InterruptedException e) {
            return;
        }
    }

    private void tag(String t, String col, String m) {
        say(DIM + "[" + col + t + DIM + "]" + RS + " " + m + "\r\n");
    }

    private void info(String m) {
        tag("info", CYN, m);
    }

    private void ok(String m) {
        tag(" ok ", GRN, m);
    }

    private void warn(String m) {
        tag("warn", YEL, m);
    }

    private void fail(String m) {
        tag("fail", RED, m);
    }

    private void kv(String k, String v) {
        say(DIM + "  " + String.format(Locale.US, "%-8s", k) + RS + v + "\r\n");
    }

    private void chunk(String s, int n) {
        for (int i = 0; i < s.length(); i += n) {
            say(DIM + "  " + s.substring(i, Math.min(s.length(), i + n)) + RS + "\r\n");
        }
    }

    private String waitKey() {
        keys.clear();
        waiting = true;
        try {
            return keys.take();
        } catch (InterruptedException e) {
            return "";
        } finally {
            waiting = false;
        }
    }

    private boolean askYes(String question) {
        say(YEL + question + RS + " ");
        String k = waitKey();
        say(k.trim() + "\r\n");
        return k.equalsIgnoreCase("y");
    }

    private void closePrompt() {
        say(DIM + "press any key to close" + RS + "\r\n");
        waitKey();
        runOnUiThread(this::finish);
    }

    private static String archName(String url) {
        if (url.contains("x86_64")) return "x86_64";
        if (url.contains("aarch64")) return "aarch64";
        if (url.contains("armv7")) return "armv7";
        return "unknown";
    }

    private static String gb(long b) {
        return String.format(Locale.US, "%.1f GB", b / 1073741824.0);
    }

    private static String clock(long s) {
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private static String dur(long ms) {
        long s = ms / 1000;
        return s >= 60 ? (s / 60) + "m" + (s % 60) + "s" : s + "s";
    }

    private String[] env() {
        String lib = getApplicationInfo().nativeLibraryDir;
        File tmp = new File(getFilesDir(), "tmp");
        tmp.mkdirs();
        java.util.ArrayList<String> e = new java.util.ArrayList<>();
        e.add("HOME=" + getFilesDir().getPath());
        e.add("PATH=/system/bin");
        e.add("TMPDIR=" + tmp.getPath());
        e.add("PROOT_TMP_DIR=" + tmp.getPath());
        if (new File(lib, "libproot_loader.so").exists()) e.add("PROOT_LOADER=" + lib + "/libproot_loader.so");
        e.add("TERM=xterm-256color");
        e.add("LD_LIBRARY_PATH=" + lib);
        return e.toArray(new String[0]);
    }

    private int helperRun(String exe, String[] args, String cwd) {
        return helperRun(exe, args, env(), cwd);
    }

    private int helperRun(String exe, String[] args, String[] environ, String cwd) {
        int[] r = Native.spawn(exe, args, environ, cwd, 24, 80);
        if (r[0] < 0) return -1;
        pid = r[1];
        while (Native.pump(r[0]) >= 0) {
        }
        int rc = Native.waitFor(r[1]);
        Native.closeFd(r[0]);
        pid = -1;
        return rc;
    }

    private void systemInfo() {
        String url = Native.tarballUrl();
        say(MAG + "system" + RS + "\r\n");
        kv("device", Build.MANUFACTURER + " " + Build.MODEL);
        kv("android", Build.VERSION.RELEASE + " api " + Build.VERSION.SDK_INT);
        kv("abis", TextUtils.join(" ", Build.SUPPORTED_ABIS));
        kv("app cpu", archName(url));
        kv("storage", gb(getFilesDir().getUsableSpace()) + " free");
        say("\r\n");
    }

    private boolean helper(String lib, String n, boolean required) {
        File f = new File(lib, n);
        if (f.exists()) {
            ok(n + " " + (f.length() >> 10) + " KB");
            return true;
        }
        if (required) fail(n + " missing from the apk");
        else warn(n + " missing, proot will use its own loader");
        return false;
    }

    private boolean detect(File root) {
        if (!root.isDirectory()) {
            fail("rootfs folder not found");
            return false;
        }
        boolean usr = new File(root, "usr").isDirectory();
        boolean etc = new File(root, "etc").isDirectory();
        boolean mark = new File(root, ".exhish_ok").exists();
        if (usr) ok("usr tree present");
        else fail("usr tree missing");
        if (etc) ok("etc tree present");
        else fail("etc tree missing");
        if (mark) ok("install marker present");
        else fail("install marker missing, install was not finished");
        return usr && etc && mark;
    }

    private boolean ready(File root) {
        return new File(root, "usr").isDirectory()
            && new File(root, "etc").isDirectory()
            && new File(root, ".exhish_ok").exists();
    }

    private void boot() {
        File root = new File(getFilesDir(), "rootfs");
        String lib = getApplicationInfo().nativeLibraryDir;
        if (new File(lib, "libproot.so").exists() && ready(root)) {
            try {
                provision(root);
            } catch (IOException e) {
                Log.w("exhish", "shell setup failed", e);
            }
            runOnUiThread(this::launchShell);
            return;
        }
        startPlaceholder();
        try {
            say("\u001b[2J\u001b[H");
            systemInfo();
            say(MAG + "helpers" + RS + "\r\n");
            boolean proot = helper(lib, "libproot.so", true);
            boolean busy = helper(lib, "libbusybox.so", true);
            helper(lib, "libproot_loader.so", false);
            say("\r\n" + MAG + "exherbo root" + RS + "\r\n");
            info("looking in");
            chunk(root.getPath(), 44);
            if (!detect(root)) {
                fail("no exherbo root found");
                if (!busy) {
                    fail("cannot install without libbusybox.so");
                    closePrompt();
                    return;
                }
                if (!askYes("install exherbo now? press y to install, any other key to exit")) {
                    info("nothing to do, exiting");
                    closePrompt();
                    return;
                }
                if (!installLoop(root)) {
                    closePrompt();
                    return;
                }
                if (!detect(root)) {
                    fail("root still not detected after install");
                    closePrompt();
                    return;
                }
            }
            ok("exherbo root detected");
            if (!proot) {
                fail("libproot.so is needed to start the shell");
                closePrompt();
                return;
            }
            try {
                provision(root);
            } catch (IOException e) {
                warn("shell setup failed: " + e.getMessage());
            }
            runOnUiThread(this::launchShell);
        } catch (Throwable ex) {
            fail(String.valueOf(ex));
            closePrompt();
        }
    }

    private static final String TTY_SHIM = "/etc/exhish/libexhishtty.so";

    private void launchShell() {
        File root = new File(getFilesDir(), "rootfs");
        String lib = getApplicationInfo().nativeLibraryDir;
        java.util.ArrayList<String> a = new java.util.ArrayList<>(java.util.Arrays.asList(
            "proot", "--link2symlink", "-i", "0:443", "-r", root.getPath(),
            "-b", "/dev", "-b", "/proc", "-b", "/sys"));
        if (new File("/system/fonts").isDirectory()) {
            a.add("-b");
            a.add("/system/fonts:/android-fonts");
        }
        boolean fs = new File(root, "etc/exhish/fs_enabled").exists();
        String phone = new File("/storage/emulated/0").isDirectory() ? "/storage/emulated/0" : "/sdcard";
        if (fs) {
            a.add("-b");
            a.add(phone + ":/sdcard");
        }
        a.addAll(java.util.Arrays.asList("-w", "/root",
            "/usr/bin/env", "-i", "HOME=/root", "TERM=xterm-256color",
            "COLORTERM=truecolor", "LANG=C.UTF-8",
            "PATH=/usr/host/bin:/usr/bin:/usr/sbin:/bin:/sbin",
            "INPUTRC=/etc/exhish/inputrc"));
        if (fs) a.add("EXHISH_FS=1");
        if (new File(root, TTY_SHIM).exists()) a.add("LD_PRELOAD=" + TTY_SHIM);
        a.add("bash");
        a.add("--login");
        a.add("-i");
        TerminalSession t = new TerminalSession(lib + "/libproot.so", getFilesDir().getPath(),
            a.toArray(new String[0]), env(), tw.scrollback, client);
        shell = t;
        shellStart = System.currentTimeMillis();
        shellPhase = true;
        tv.attachSession(t);
        TerminalSession old = placeholder;
        if (old != null) old.finishIfRunning();
        tv.requestFocus();
        applyCursorBlink();
    }

    private void shellFinished(TerminalSession t) {
        if (t != shell) return;
        int rc = t.getExitStatus();
        long ran = System.currentTimeMillis() - shellStart;
        if (rc == 0 && ran > 3000) {
            finish();
            return;
        }
        shellPhase = false;
        new Thread(() -> {
            startPlaceholder();
            if (rc == 0) ok("shell closed after " + dur(ran));
            else warn("shell closed with code " + rc + " after " + dur(ran));
            closePrompt();
        }).start();
    }

    private boolean installLoop(File root) {
        while (true) {
            try {
                wipe(root);
                install(root);
                return true;
            } catch (IOException e) {
                fail(e.getMessage() == null ? e.toString() : e.getMessage());
                if (!askYes("try again? press y to retry, any other key to exit")) return false;
            }
        }
    }

    private void wipe(File root) throws IOException {
        if (!root.exists()) return;
        info("removing old root files");
        String lib = getApplicationInfo().nativeLibraryDir;
        String cwd = getFilesDir().getPath();
        helperRun(lib + "/libbusybox.so", new String[] {"busybox", "chmod", "-R", "u+rwx", root.getPath()}, cwd);
        int rc = helperRun(lib + "/libbusybox.so", new String[] {"busybox", "rm", "-rf", root.getPath()}, cwd);
        if (rc != 0 || root.exists()) throw new IOException("could not remove the old root");
        ok("old root removed");
    }

    private void install(File root) throws IOException {
        String url = Native.tarballUrl();
        if (url.isEmpty()) throw new IOException("unsupported cpu architecture");
        String lib = getApplicationInfo().nativeLibraryDir;
        String cwd = getFilesDir().getPath();
        say("\r\n" + MAG + "install" + RS + "\r\n");
        info("cpu detected: " + archName(url));
        info("stage tarball");
        chunk(url, 44);
        long free = getFilesDir().getUsableSpace();
        info("free space " + gb(free));
        if (free < 3L * 1073741824L) warn("less than 3 GB free, extraction may fail");
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "exhish:install");
        wl.acquire(4L * 60 * 60 * 1000);
        try {
            long t0 = System.currentTimeMillis();
            File tar = download(url, new File(getFilesDir(), "stage.tar.xz"));
            ok("tarball ready, " + (tar.length() >> 20) + " MB, " + dur(System.currentTimeMillis() - t0));
            root.mkdirs();
            info("extracting, this takes a while");
            long t1 = System.currentTimeMillis();
            final AtomicBoolean busyFlag = new AtomicBoolean(true);
            Thread tick = new Thread(() -> {
                while (busyFlag.get()) {
                    say(DIM + "." + RS);
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            tick.start();
            int rc;
            try {
                rc = helperRun("@untar", new String[] {
                    "untar", tar.getPath(), lib + "/libbusybox.so", root.getPath()
                }, cwd);
            } finally {
                busyFlag.set(false);
                tick.interrupt();
            }
            say("\r\n");
            if (rc != 0) {
                tar.delete();
                throw new IOException("extraction failed with code " + rc);
            }
            ok("extracted in " + dur(System.currentTimeMillis() - t1));
            new File(root, "root").mkdirs();
            new File(root, "tmp").mkdirs();
            new File(root, "etc/exhish").mkdirs();
            put(new File(root, "etc/resolv.conf"), "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
            ok("dns configured");
            put(new File(root, "root/.bash_profile"), "[ -f ~/.bashrc ] && . ~/.bashrc\n");
            put(new File(root, "root/.bashrc"),
                "export PS1='\\[\\e[1;97m\\]exhish\\[\\e[0m\\] \\[\\e[1;34m\\]\\w\\[\\e[0m\\] \\$ '\n"
                + "alias ls='ls --color=auto'\n"
                + "alias grep='grep --color=auto'\n"
                + "alias ll='ls -la --color=auto'\n"
                + "alias paludis='cave'\n"
                + "if [ ! -f /root/.exhish_synced ]; then\n"
                + "echo 'first run, syncing paludis repositories'\n"
                + "cave sync && touch /root/.exhish_synced\n"
                + "fi\n");
            ok("shell profile written");
            put(new File(root, "etc/exhish/motd"), DEFAULT_MOTD);
            tar.delete();
            ok("tarball removed");
            put(new File(root, ".exhish_ok"), "1\n");
            ok("install finished in " + dur(System.currentTimeMillis() - t0));
        } finally {
            if (wl.isHeld()) wl.release();
        }
    }

    private void provision(File root) throws IOException {
        new File(root, "etc/exhish").mkdirs();
        new File(root, "etc/paludis").mkdirs();
        new File(root, "root").mkdirs();
        new File(root, "tmp").mkdirs();
        put(new File(root, "etc/exhish/profile.sh"), PROFILE_SH);
        put(new File(root, "etc/exhish/inputrc"), INPUTRC);
        put(new File(root, "etc/exhish/fix.sh"), FIX_SH);
        asset("ttyshim.c", new File(root, "etc/exhish/ttyshim.c"), false);
        asset("apply.sh", new File(root, "etc/exhish/apply.sh"), false);
        asset("extras.sh", new File(root, "etc/exhish/extras.sh"), false);
        asset("GUIDE.txt", new File(root, "etc/exhish/GUIDE.txt"), false);
        asset("settings_menu", new File(root, "etc/exhish/templates/settings_menu"), true);
        asset("exhish", new File(root, "etc/exhish/bin/exhish"), true);
        asset("exstart", new File(root, "etc/exhish/bin/exstart"), true);
        asset("exfs", new File(root, "etc/exhish/bin/exfs"), true);
        asset("exnable", new File(root, "etc/exhish/bin/exnable"), true);
        asset("exwake", new File(root, "etc/exhish/bin/exwake"), true);
        asset("exupd", new File(root, "etc/exhish/bin/exupd"), true);
        put(new File(root, "etc/exhish/version"), versionName() + "\n");
        new File(root, "sdcard").mkdirs();
        new File(root, "android-fonts").mkdirs();
        File motd = new File(root, "etc/exhish/motd");
        if (!motd.exists()) put(motd, DEFAULT_MOTD);
        File hn = new File(root, "etc/hostname");
        if (!hn.exists()) put(hn, "localhost\n");
        File hosts = new File(root, "etc/hosts");
        if (!hosts.exists()) put(hosts, "127.0.0.1 localhost\n::1 localhost\n");
        File rc = new File(root, "root/.bashrc");
        String cur = rc.exists() ? readAll(rc) : "";
        if (!cur.contains("/etc/exhish/profile.sh")) {
            if (cur.contains("exhish\\[\\e[0m\\]") || cur.contains("first run, syncing")) cur = "";
            put(rc, "# exhish\n[ -f /etc/exhish/profile.sh ] && . /etc/exhish/profile.sh\n" + cur);
        }
        put(new File(root, "root/.bash_profile"), "[ -f ~/.bashrc ] && . ~/.bashrc\n");
    }

    private void asset(String name, File dest, boolean exec) throws IOException {
        dest.getParentFile().mkdirs();
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        try (InputStream in = getAssets().open(name)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        put(dest, new String(o.toByteArray(), StandardCharsets.UTF_8));
        if (exec) dest.setExecutable(true, false);
    }

    private String readAll(File f) throws IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        }
        return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final String DEFAULT_MOTD =
        "\u001b[97m\n"
        + "Welcome to Exhish\n"
        + "edit /etc/exhish/motd to change this text\n"
        + "\n"
        + "         ^__^\n"
        + "         (oo)\\_______\n"
        + "         (__)\\       )\\/\\\n"
        + "             ||=====w |\n"
        + "             ||     ||\n";

    private static final String INPUTRC =
        "set bell-style none\n"
        + "set colored-stats on\n"
        + "set completion-ignore-case on\n"
        + "\"\\e[A\": previous-history\n\"\\eOA\": previous-history\n"
        + "\"\\e[B\": next-history\n\"\\eOB\": next-history\n"
        + "\"\\e[C\": forward-char\n\"\\eOC\": forward-char\n"
        + "\"\\e[D\": backward-char\n\"\\eOD\": backward-char\n"
        + "\"\\e[H\": beginning-of-line\n\"\\eOH\": beginning-of-line\n"
        + "\"\\e[F\": end-of-line\n\"\\eOF\": end-of-line\n"
        + "\"\\e[1~\": beginning-of-line\n\"\\e[4~\": end-of-line\n"
        + "\"\\e[3~\": delete-char\n"
        + "\"\\e[5~\": history-search-backward\n\"\\e[6~\": history-search-forward\n";

    private static final String PROFILE_SH =
        "# managed by Exhish, rewritten on every start. Put your own tweaks in ~/.bashrc\n"
        + "export TERM=xterm-256color COLORTERM=truecolor LANG=C.UTF-8\n"
        + "export INPUTRC=/etc/exhish/inputrc\n"
        + "export CLICOLOR=1 LESS=-RFX\n"
        + "export PALUDIS_DO_NOTHING_SANDBOXY=1\n"
        + "case $- in *i*) ;; *) return 0 2>/dev/null ;; esac\n"
        + "[ -z \"$EXHISH_FIXED\" ] && [ -f /etc/exhish/fix.sh ] && sh /etc/exhish/fix.sh 2>/dev/null\n"
        + "export EXHISH_FIXED=1\n"
        + "SHIM=/etc/exhish/libexhishtty.so\n"
        + "if [ ! -f $SHIM ] && [ -f /etc/exhish/ttyshim.c ] && command -v gcc >/dev/null 2>&1; then\n"
        + "gcc -O2 -shared -fPIC -o $SHIM /etc/exhish/ttyshim.c >/dev/null 2>&1 || rm -f $SHIM\n"
        + "fi\n"
        + "if [ -f $SHIM ] && [ -z \"$EXHISH_PRE\" ] && [ -z \"$LD_PRELOAD\" ]; then\n"
        + "export EXHISH_PRE=1 LD_PRELOAD=$SHIM\n"
        + "exec bash --login -i\n"
        + "fi\n"
        + "[ -f /root/.exhish/config ] && . /root/.exhish/config\n"
        + "if [ -z \"$EXHISH_MOTD\" ] && [ \"$EXH_MOTD\" != off ]; then\n"
        + "[ -f /etc/exhish/motd ] && head -c 65536 /etc/exhish/motd\n"
        + "printf '\\033[0m\\n'\n"
        + "fi\n"
        + "export EXHISH_MOTD=1\n"
        + "_h=$(head -n1 /etc/hostname 2>/dev/null); HOSTNAME=${_h:-localhost}; unset _h\n"
        + "export HOSTNAME\n"
        + "PS1='\\[\\e[1;${EXH_HC:-32}m\\]${HOSTNAME}\\[\\e[0m\\] \\[\\e[1;34m\\]\\w\\[\\e[0m\\] \\[\\e[1;31m\\]\\$\\[\\e[0m\\] '\n"
        + "export HISTFILE=$HOME/.bash_history HISTSIZE=5000 HISTFILESIZE=10000 HISTCONTROL=ignoredups\n"
        + "shopt -s histappend checkwinsize\n"
        + "PROMPT_COMMAND='history -a'\n"
        + "set -o emacs\n"
        + "bind '\"\\e[A\": previous-history' 2>/dev/null\n"
        + "bind '\"\\eOA\": previous-history' 2>/dev/null\n"
        + "bind '\"\\e[B\": next-history' 2>/dev/null\n"
        + "bind '\"\\eOB\": next-history' 2>/dev/null\n"
        + "alias ls='ls --color=auto'\n"
        + "alias ll='ls -la --color=auto'\n"
        + "alias grep='grep --color=auto'\n"
        + "alias diff='diff --color=auto'\n"
        + "alias paludis='cave'\n"
        + "[ -f /etc/exhish/extras.sh ] && . /etc/exhish/extras.sh\n";

    private static final String FIX_SH =
        "#!/bin/sh\n"
        + "grep -q '^paludisbuild:' /etc/group || echo 'paludisbuild:x:443:' >> /etc/group\n"
        + "grep -q '^paludisbuild:' /etc/passwd || echo 'paludisbuild:x:443:443:paludis build user:/var/tmp/paludis:/bin/false' >> /etc/passwd\n"
        + "mkdir -p /var/tmp/paludis/build /var/cache/paludis/distfiles /var/cache/paludis/names /var/cache/paludis/metadata 2>/dev/null\n"
        + "chmod -R 777 /var/tmp/paludis /var/cache/paludis 2>/dev/null\n"
        + "sed -i 's/^\\(paludisbuild:[^:]*:[^:]*:\\)[0-9]*:/\\1443:/' /etc/passwd\n"
        + "sed -i '/^reduced_username/d' /etc/paludis/general.conf 2>/dev/null\n"
        + "grep -q '^root:[^:]*:0:.*paludisbuild' /etc/group || sed -i '/^root:/{/:$/!s/$/,/;s/$/paludisbuild/}' /etc/group\n"
        + "if ! command -v sydbox >/dev/null 2>&1; then\n"
        + "mkdir -p /usr/host/bin\n"
        + "cat > /usr/host/bin/sydbox <<'EXHISH_EOF'\n"
        + "#!/bin/sh\n"
        + "while [ $# -gt 0 ]; do\n"
        + "case \"$1\" in\n"
        + "--) shift; break ;;\n"
        + "-V|--version) echo 'sydbox shim'; exit 0 ;;\n"
        + "-m|-c|-E|-p|-f|-o|-l) shift; shift ;;\n"
        + "-*) shift ;;\n"
        + "*) break ;;\n"
        + "esac\n"
        + "done\n"
        + "[ $# -gt 0 ] || exit 0\n"
        + "exec \"$@\"\n"
        + "EXHISH_EOF\n"
        + "chmod 755 /usr/host/bin/sydbox\n"
        + "fi\n"
        + "grep -q PALUDIS_DO_NOTHING_SANDBOXY /etc/paludis/bashrc 2>/dev/null || echo 'export PALUDIS_DO_NOTHING_SANDBOXY=1' >> /etc/paludis/bashrc\n";

    private String versionName() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "0" : v;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w("exhish", "version lookup", e);
            return "0";
        }
    }

    private void put(File f, String s) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void drawProgress(long got, long total, long base, long t0) {
        long now = System.currentTimeMillis();
        if (now - lastDraw < 400) return;
        lastDraw = now;
        double sp = (got - base) * 1000.0 / Math.max(1, now - t0);
        int pct = total > 0 ? (int) (got * 100 / total) : 0;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(i < pct / 10 ? '\u2588' : '\u2591');
        long eta = (total > 0 && sp > 1) ? (long) ((total - got) / sp) : -1;
        String size = total > 0 ? (got >> 20) + "/" + (total >> 20) + "MB" : (got >> 20) + "MB";
        say("\r\u001b[K" + CYN + "get " + GRN + b + RS + " " + pct + "% " + size + " "
            + String.format(Locale.US, "%.1f", sp / 1048576.0) + "MB/s " + (eta >= 0 ? clock(eta) : "..."));
    }

    private File download(String u, File out) throws IOException {
        if (out.exists()) {
            ok("cached tarball found, " + (out.length() >> 20) + " MB");
            return out;
        }
        File part = new File(out.getPath() + ".part");
        int fails = 0;
        boolean sized = false;
        while (true) {
            long have = part.exists() ? part.length() : 0;
            long start = have;
            long got = have;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setRequestProperty("Accept-Encoding", "identity");
                c.setRequestProperty("User-Agent", "exhish");
                if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
                int code = c.getResponseCode();
                if (code == 416) break;
                if (code != 200 && code != 206) throw new IOException("server answered " + code);
                boolean append = code == 206 && have > 0;
                if (!append) {
                    have = 0;
                    got = 0;
                    start = 0;
                }
                long len = c.getContentLengthLong();
                long total = len > 0 ? have + len : -1;
                if (!sized) {
                    info("download size " + (total > 0 ? (total >> 20) + " MB" : "unknown"));
                    sized = true;
                }
                if (have > 0) info("resuming at " + (have >> 20) + " MB");
                long t0 = System.currentTimeMillis();
                lastDraw = 0;
                try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(part, append)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        o.write(b, 0, n);
                        got += n;
                        drawProgress(got, total, start, t0);
                    }
                }
                if (total > 0 && got < total) throw new IOException("connection closed early");
                break;
            } catch (IOException e) {
                say("\r\u001b[K");
                fails = got > start ? 0 : fails + 1;
                if (fails >= 15) throw e;
                warn("interrupted by " + e.getClass().getSimpleName() + " at " + (got >> 20) + " MB");
                info("retrying in 3 seconds, attempt " + (fails + 1));
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    throw new IOException("canceled");
                }
            } finally {
                if (c != null) c.disconnect();
            }
        }
        say("\r\u001b[K");
        if (!part.renameTo(out)) throw new IOException("could not finish the tarball file");
        return out;
    }

    private static boolean isModifier(int k) {
        return k == KeyEvent.KEYCODE_SHIFT_LEFT || k == KeyEvent.KEYCODE_SHIFT_RIGHT
            || k == KeyEvent.KEYCODE_CTRL_LEFT || k == KeyEvent.KEYCODE_CTRL_RIGHT
            || k == KeyEvent.KEYCODE_ALT_LEFT || k == KeyEvent.KEYCODE_ALT_RIGHT
            || k == KeyEvent.KEYCODE_META_LEFT || k == KeyEvent.KEYCODE_META_RIGHT
            || k == KeyEvent.KEYCODE_CAPS_LOCK || k == KeyEvent.KEYCODE_FUNCTION;
    }

    private final class Client implements TerminalViewClient, TerminalSessionClient {
        public float onScale(float scale) {
            return 1.0f;
        }

        public void onSingleTapUp(MotionEvent e) {
            if (!tw.tapKeyboard) return;
            InputMethodManager m = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            m.showSoftInput(tv, InputMethodManager.SHOW_IMPLICIT);
        }

        public boolean shouldBackButtonBeMappedToEscape() {
            return tw.backEsc;
        }

        public boolean shouldEnforceCharBasedInput() {
            return true;
        }

        public boolean shouldUseCtrlSpaceWorkaround() {
            return false;
        }

        public boolean isTerminalViewSelected() {
            return true;
        }

        public void copyModeChanged(boolean copyMode) {
        }

        public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
            if (shellPhase && tw.volumeKeys
                && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
                if (e.getRepeatCount() == 0) {
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        ctrl.tap();
                        refreshMods();
                    } else {
                        sendKey(KeyEvent.KEYCODE_ESCAPE);
                    }
                }
                return true;
            }
            if (shellPhase || keyCode == KeyEvent.KEYCODE_BACK) return false;
            if (isModifier(keyCode)) return true;
            if (waiting) {
                int u = e.getUnicodeChar();
                keys.offer(u > 32 ? String.valueOf((char) u) : "\n");
            }
            return true;
        }

        public boolean onKeyUp(int keyCode, KeyEvent e) {
            if (shellPhase && tw.volumeKeys
                && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
                return true;
            }
            return !shellPhase && keyCode != KeyEvent.KEYCODE_BACK;
        }

        public boolean onLongPress(MotionEvent event) {
            return false;
        }

        public boolean readControlKey() {
            boolean v = ctrl.read();
            if (v) refreshMods();
            return v;
        }

        public boolean readAltKey() {
            boolean v = alt.read();
            if (v) refreshMods();
            return v;
        }

        public boolean readShiftKey() {
            return false;
        }

        public boolean readFnKey() {
            return false;
        }

        public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
            if (shellPhase) return false;
            if (waiting) keys.offer(new String(Character.toChars(codePoint)));
            return true;
        }

        public void onEmulatorSet() {
        }

        public void onTextChanged(TerminalSession s) {
            tv.onScreenUpdated();
        }

        public void onTitleChanged(TerminalSession s) {
        }

        public void onSessionFinished(TerminalSession s) {
            shellFinished(s);
        }

        public void onCopyTextToClipboard(TerminalSession s, String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("exhish", text));
        }

        public void onPasteTextFromClipboard(TerminalSession s) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || s == null) return;
            ClipData d = cm.getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return;
            CharSequence t = d.getItemAt(0).coerceToText(MainActivity.this);
            TerminalEmulator em = s.getEmulator();
            if (t != null && t.length() > 0 && em != null) em.paste(t.toString());
        }

        public void onBell(TerminalSession s) {
            if (tw.bell == 1) runOnUiThread(() -> tv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS));
        }

        public void onColorsChanged(TerminalSession s) {
            tv.onScreenUpdated();
        }

        public void onTerminalCursorStateChange(boolean state) {
        }

        public void setTerminalShellPid(TerminalSession s, int p) {
        }

        public Integer getTerminalCursorStyle() {
            return Integer.valueOf(tw.cursorStyle);
        }

        public void logError(String tag, String message) {
            Log.e(tag, message);
        }

        public void logWarn(String tag, String message) {
            Log.w(tag, message);
        }

        public void logInfo(String tag, String message) {
            Log.i(tag, message);
        }

        public void logDebug(String tag, String message) {
            Log.d(tag, message);
        }

        public void logVerbose(String tag, String message) {
            Log.v(tag, message);
        }

        public void logStackTraceWithMessage(String tag, String message, Exception e) {
            Log.e(tag, message, e);
        }

        public void logStackTrace(String tag, Exception e) {
            Log.e(tag, "error", e);
        }
    }
}
