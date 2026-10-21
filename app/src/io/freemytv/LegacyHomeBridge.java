package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** TCL Android 11 keeps its privileged launcher priority. Redirect only its Home window; no screen content access. */
public final class LegacyHomeBridge extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable redirect = () -> open(this);
    private TvInputManager tvInputs;
    private String hdmi1Id;

    private final TvInputManager.TvInputCallback ps5Callback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) {
            if (inputId != null && inputId.equals(hdmi1Id) && state == TvInputManager.INPUT_STATE_CONNECTED) {
                onPs5TurnedOn(LegacyHomeBridge.this);
            }
        }
    };

    static void onPs5TurnedOn(Context c) {
        try {
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            boolean nightMode = (hour >= 23 || hour < 7);
            int targetVol = nightMode ? 10 : 18;
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int scaled = max > 0 ? Math.min(max, Math.round(targetVol * (max / 100f))) : targetVol;
                // On TCL Android TV, max volume for STREAM_MUSIC is 100
                if (max >= 50) scaled = Math.min(max, targetVol);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, scaled, 0);
            }
            String msg = nightMode
                    ? "🎮 PlayStation 5 ligado · Volume noturno ajustado (10)"
                    : "🎮 PlayStation 5 ligado · Volume de jogo ajustado (18)";
            HomeMode.notice(c, msg);
            Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            AppLog.w("PS5 auto-volume: " + e.getMessage());
        }
    }

    private void registerPs5Monitor() {
        try {
            tvInputs = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tvInputs == null) return;
            List<TvInputInfo> list = new ArrayList<>();
            for (TvInputInfo info : tvInputs.getTvInputList()) {
                if (info.getType() == TvInputInfo.TYPE_HDMI && info.getParentId() == null) list.add(info);
            }
            Collections.sort(list, (a, b) -> a.getId().compareTo(b.getId()));
            Pattern digit = Pattern.compile("(\\d)");
            for (int i = 0; i < list.size(); i++) {
                TvInputInfo info = list.get(i);
                int number = i + 1;
                Matcher m = digit.matcher(String.valueOf(info.loadLabel(this)));
                if (m.find()) number = Integer.parseInt(m.group(1));
                if (number == 1) {
                    hdmi1Id = info.getId();
                    break;
                }
            }
            tvInputs.registerCallback(ps5Callback, handler);
        } catch (RuntimeException ignored) {}
    }
    static boolean chosen(Context c) {
        return c.getSharedPreferences("tcl_home", Context.MODE_PRIVATE).getBoolean("chosen", false);
    }
    static void select(Context c, boolean free) {
        c.getSharedPreferences("tcl_home", Context.MODE_PRIVATE).edit().putBoolean("chosen", free).commit();
        if (free) {
            Watchdog.clear(c);
            ensureServiceEnabled(c);
        }
        open(c);
    }
    static void ensureServiceEnabled(Context c) {
        if (!chosen(c)) return;
        try {
            String svc = "io.freemytv/io.freemytv.LegacyHomeBridge";
            String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null || !cur.contains(svc)) {
                String next = (cur == null || cur.isEmpty()) ? svc : (cur + ":" + svc);
                Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            }
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (RuntimeException ignored) {}
    }
    private static void open(Context c) {
        boolean free = chosen(c) && !Watchdog.crashedTooOften(c);
        Intent i = new Intent().setClassName(free ? "io.freemytv" : "com.overdevs.at4k",
                free ? "io.freemytv.HomeActivity" : "com.overdevs.at4k.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try { c.startActivity(i); } catch (RuntimeException e) { AppLog.w("Home redirect: " + e.getMessage()); }
    }
    @Override protected void onServiceConnected() {
        Watchdog.install(this);
        registerPs5Monitor();
        android.content.SharedPreferences prefs = getSharedPreferences("tcl_home", MODE_PRIVATE);
        boolean first = !prefs.contains("chosen");
        if (first) prefs.edit().putBoolean("chosen", true).commit();
        int boot = -1;
        try { boot = Settings.Global.getInt(getContentResolver(), "boot_count", -1); }
        catch (RuntimeException e) { AppLog.w("Boot counter unavailable"); }
        boolean newBoot = boot >= 0 && prefs.getInt("last_boot", -1) != boot;
        // Record once per boot: late binding works, normal service restarts do not interrupt a film.
        if (first || newBoot) {
            if (boot >= 0) prefs.edit().putInt("last_boot", boot).commit();
            handler.postDelayed(redirect, 700);
        }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence name = event.getPackageName();
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && name != null &&
                (name.toString().equals("com.google.android.apps.tv.launcherx") ||
                 name.toString().equals("com.google.android.tungsten.setupwraith"))) {
            handler.removeCallbacks(redirect);
            handler.post(redirect);
        }
    }
    @Override public void onInterrupt() { handler.removeCallbacks(redirect); }
    @Override public void onDestroy() {
        handler.removeCallbacks(redirect);
        if (tvInputs != null) {
            try { tvInputs.unregisterCallback(ps5Callback); } catch (RuntimeException ignored) {}
        }
        super.onDestroy();
    }
}
