package com.stepwriter.shealth;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Calendar;
import java.util.Date;

/** Settings and progress screen, opened from Vector/LSPosed or the launcher. */
public class SettingsActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int SPM_MIN = 20;
    private static final long PING_TIMEOUT_MS = 6_000;

    private SharedPreferences config;   // world-readable through LSPosed, null if module inactive
    private SharedPreferences status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastPing = 0;

    // Colours
    private int cBg, cCard, cText, cMuted, cLine, cAccent, cAccentSoft, cWarn, cWarnSoft, cBad, cBadSoft, cInput;

    // Views
    private TextView pill, totalTv, totalSub, progressTv, simStepsTv, rateTv, etaTv, warnTv, inactiveTv;
    private TextView vVersion, vHook, vSeen, logTv;
    private ProgressBar bar;
    private Switch simOn, boostOn;
    private SeekBar spmBar;
    private EditText spmEt, dailyEt, multEt, maxSpmEt;
    private final Button[] presets = new Button[4];
    private static final int[] PRESET_VALUES = {10_000, 25_000, 50_000, 100_000};
    private boolean filling = false;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - lastPing > 30_000) ping();
            render();
            handler.postDelayed(this, 5_000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (getActionBar() != null) getActionBar().hide(); // the screen has its own header
        setupColors();
        try {
            // Only works while the module is enabled in LSPosed/Vector, which makes it readable to the hook.
            //noinspection deprecation
            config = getSharedPreferences(Keys.PREFS, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            config = null;
        }
        status = getSharedPreferences(StatusReceiver.PREFS, MODE_PRIVATE);
        setContentView(buildUi());
        fillForm();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        status.registerOnSharedPreferenceChangeListener(this);
        ping();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        status.unregisterOnSharedPreferenceChangeListener(this);
        handler.removeCallbacks(ticker);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        render();
    }

    // ---------------------------------------------------------------- config

    private static final class Values {
        boolean simOn, boostOn;
        int spm, dailyMax, maxSpm;
        float mult;
    }

    /** Saved values: app prefs first, then the old adb settings, then defaults. */
    private Values saved() {
        Values v = new Values();
        if (config != null && config.contains(Keys.SIM_SPM)) {
            v.simOn = config.getBoolean(Keys.SIM_ON, Keys.D_SIM_ON);
            v.spm = config.getInt(Keys.SIM_SPM, Keys.D_SIM_SPM);
            v.dailyMax = config.getInt(Keys.SIM_DAILY_MAX, Keys.D_SIM_DAILY_MAX);
            v.boostOn = config.getBoolean(Keys.BOOST_ON, Keys.D_BOOST_ON);
            v.mult = config.getFloat(Keys.MULT, Keys.D_MULT);
            v.maxSpm = config.getInt(Keys.MAX_SPM, Keys.D_MAX_SPM);
            return v;
        }
        int legacySpm = (int) global("shealth_sim_spm", 0);
        v.simOn = legacySpm > 0;
        v.spm = legacySpm > 0 ? legacySpm : Keys.D_SIM_SPM;
        v.dailyMax = (int) global("shealth_sim_daily_max", Keys.D_SIM_DAILY_MAX);
        v.mult = (float) global("shealth_step_mult", Keys.D_MULT);
        v.boostOn = v.mult > 1.0f;
        v.maxSpm = (int) global("shealth_step_max_spm", Keys.D_MAX_SPM);
        return v;
    }

    private double global(String key, double def) {
        try {
            String s = Settings.Global.getString(getContentResolver(), key);
            return s != null ? Double.parseDouble(s.trim()) : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private void fillForm() {
        filling = true;
        Values v = saved();
        simOn.setChecked(v.simOn);
        spmBar.setProgress(clamp(v.spm, SPM_MIN, Keys.SIM_HARD_MAX) - SPM_MIN);
        spmEt.setText(String.valueOf(v.spm));
        dailyEt.setText(String.valueOf(v.dailyMax));
        boostOn.setChecked(v.boostOn);
        multEt.setText(trimFloat(v.mult));
        maxSpmEt.setText(String.valueOf(v.maxSpm));
        markPresets();
        filling = false;
    }

    private void save() {
        if (config == null) {
            toast("Enable the module in Vector/LSPosed first");
            return;
        }
        Integer spm = parseInt(spmEt, SPM_MIN, Keys.SIM_HARD_MAX);
        Integer daily = parseInt(dailyEt, 0, 300_000);
        Float mult = parseFloat(multEt, 1f, 10f);
        Integer maxSpm = parseInt(maxSpmEt, 60, 260);
        if (spm == null || daily == null || mult == null || maxSpm == null) {
            toast("Check the highlighted numbers");
            return;
        }
        config.edit()
                .putBoolean(Keys.SIM_ON, simOn.isChecked())
                .putInt(Keys.SIM_SPM, spm)
                .putInt(Keys.SIM_DAILY_MAX, daily)
                .putBoolean(Keys.BOOST_ON, boostOn.isChecked())
                .putFloat(Keys.MULT, mult)
                .putInt(Keys.MAX_SPM, maxSpm)
                .commit();
        fillForm();
        ping(); // the hook reloads settings when pinged
        toast(simOn.isChecked() ? "Saved · simulating ~" + spm + "/min" : "Saved · simulation off");
        render();
    }

    // ---------------------------------------------------------------- hook comms

    private void ping() {
        lastPing = System.currentTimeMillis();
        sendBroadcast(new Intent(Keys.ACTION_PING).setPackage(Keys.SH_PKG));
        handler.postDelayed(this::render, PING_TIMEOUT_MS + 200);
    }

    private void restartSamsungHealth() {
        new Thread(() -> {
            boolean ok;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                        "am force-stop " + Keys.SH_PKG + "; sleep 1; monkey -p " + Keys.SH_PKG
                                + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"});
                ok = p.waitFor() == 0;
            } catch (Throwable t) {
                ok = false;
            }
            final boolean done = ok;
            handler.post(() -> {
                if (done) {
                    toast("Samsung Health restarted");
                    handler.postDelayed(this::ping, 8_000);
                } else {
                    // No root for this app: open Samsung Health's app info so it can be force-stopped by hand.
                    toast("No root access · force-stop Samsung Health here");
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + Keys.SH_PKG)));
                }
            });
        }).start();
    }

    // ---------------------------------------------------------------- render

    private void render() {
        if (totalTv == null) return;
        NumberFormat nf = NumberFormat.getIntegerInstance();
        long now = System.currentTimeMillis();
        String today = StatusReceiver.day(now);
        Values v = saved();

        int simToday = today.equals(status.getString("sim_day", "")) ? status.getInt("sim_steps", 0) : 0;
        boolean haveTotal = today.equals(status.getString("total_day", ""));
        int total = haveTotal ? status.getInt("total", 0) : simToday;
        totalTv.setText(nf.format(total));
        totalSub.setText(haveTotal ? "steps in Samsung Health today" : "simulated steps today (total not reported yet)");
        simStepsTv.setText(nf.format(simToday));

        int remaining = Math.max(0, v.dailyMax - simToday);
        int pct = v.dailyMax > 0 ? (int) Math.min(1000, simToday * 1000L / v.dailyMax) : 0;
        bar.setProgress(pct);
        progressTv.setText(v.dailyMax > 0
                ? nf.format(simToday) + " of " + nf.format(v.dailyMax) + " simulated · " + (pct / 10) + "." + (pct % 10)
                + "% · " + nf.format(remaining) + " to go"
                : "No daily target set");

        int rate = v.simOn ? Math.min(v.spm, Keys.SIM_HARD_MAX) : 0;
        rateTv.setText(v.simOn ? nf.format(rate) : "off");
        warnTv.setVisibility(View.GONE);
        if (remaining == 0) {
            etaTv.setText("done");
        } else if (!v.simOn) {
            etaTv.setText("paused");
        } else {
            long mins = (remaining + rate - 1) / rate;
            Date end = new Date(now + mins * 60_000);
            etaTv.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(end));
            Calendar midnight = Calendar.getInstance();
            midnight.add(Calendar.DAY_OF_YEAR, 1);
            midnight.set(Calendar.HOUR_OF_DAY, 0);
            midnight.set(Calendar.MINUTE, 0);
            midnight.set(Calendar.SECOND, 0);
            if (end.getTime() > midnight.getTimeInMillis()) {
                long reachable = simToday + (midnight.getTimeInMillis() - now) / 60_000 * rate;
                warnTv.setText("At " + rate + "/min this won't finish before midnight. You'll reach about "
                        + nf.format(reachable) + " today; raise the pace to finish sooner.");
                warnTv.setVisibility(View.VISIBLE);
            }
        }

        // Status
        String version = status.getString("version", null);
        boolean versionOk = version != null && version.startsWith(Keys.TESTED_VERSION);
        setValue(vVersion, version == null ? "unknown" : version + (versionOk ? "" : " (untested)"),
                version == null ? cMuted : versionOk ? cAccent : cWarn);

        long seen = status.getLong("last_seen", 0);
        boolean answered = seen >= lastPing && lastPing > 0;
        boolean waiting = !answered && now - lastPing < PING_TIMEOUT_MS;
        if (answered) setValue(vHook, "loaded", cAccent);
        else if (waiting) setValue(vHook, "checking…", cMuted);
        else setValue(vHook, "no response", cBad);
        setValue(vSeen, seen == 0 ? "never" : DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(seen)), cText);

        inactiveTv.setVisibility(config == null ? View.VISIBLE : View.GONE);
        if (config == null) setPill("Module inactive", cBad, cBadSoft);
        else if (!answered && !waiting) setPill("Hook not responding", cBad, cBadSoft);
        else if (v.simOn && remaining > 0) setPill("Simulating", cAccent, cAccentSoft);
        else setPill("Active", cAccent, cAccentSoft);

        String events = status.getString("events", "");
        logTv.setText(events.isEmpty() ? "No activity yet." : events);
    }

    // ---------------------------------------------------------------- UI building

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cBg);
        scroll.setFillViewport(true);
        LinearLayout root = vertical();
        root.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getInsets(WindowInsets.Type.systemBars()).top;
            int bottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
            root.setPadding(dp(16), top + dp(12), dp(16), bottom + dp(32));
            return insets;
        });

        // Header
        LinearLayout header = horizontal();
        TextView title = text("Step Boost", 22, cText, true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        pill = text("…", 12, cMuted, true);
        pill.setPadding(dp(10), dp(4), dp(10), dp(4));
        header.addView(pill);
        root.addView(header, matchWrap(0, 4));

        inactiveTv = notice("This module isn't enabled in Vector/LSPosed. Enable it with Samsung Health in scope, "
                + "then reopen this screen.", cBad, cBadSoft);
        root.addView(inactiveTv, matchWrap(12, 0));

        // Today
        LinearLayout today = card(root, "Today");
        totalTv = text("–", 40, cText, true);
        today.addView(totalTv);
        totalSub = text("", 13, cMuted, false);
        today.addView(totalSub);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(cAccent));
        today.addView(bar, matchWrap(12, 4));
        progressTv = text("", 13, cMuted, false);
        today.addView(progressTv);
        LinearLayout stats = horizontal();
        simStepsTv = stat(stats, "simulated");
        rateTv = stat(stats, "steps / min");
        etaTv = stat(stats, "finishes");
        today.addView(stats, matchWrap(12, 0));
        warnTv = notice("", cWarn, cWarnSoft);
        today.addView(warnTv, matchWrap(10, 0));

        // Simulation
        LinearLayout sim = card(root, "Simulation");
        simOn = toggle();
        simOn.setOnCheckedChangeListener((b, on) -> { if (!filling) save(); });
        sim.addView(row("Simulate steps", "Adds walking minutes while the phone is still", simOn));
        spmEt = number(false);
        sim.addView(row("Pace", "Each minute varies ±12%, never above " + Keys.SIM_HARD_MAX, withUnit(spmEt, "/min")));
        spmBar = new SeekBar(this);
        spmBar.setMax(Keys.SIM_HARD_MAX - SPM_MIN);
        spmBar.setProgressTintList(android.content.res.ColorStateList.valueOf(cAccent));
        spmBar.setThumbTintList(android.content.res.ColorStateList.valueOf(cAccent));
        spmBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) spmEt.setText(String.valueOf(p + SPM_MIN));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        sim.addView(spmBar, matchWrap(0, 8));
        dailyEt = number(false);
        dailyEt.addTextChangedListener(new SimpleWatcher(this::markPresets));
        sim.addView(row("Daily target", "Stops once this many fake steps are added today", withUnit(dailyEt, "steps")));
        LinearLayout presetRow = horizontal();
        for (int i = 0; i < presets.length; i++) {
            final int value = PRESET_VALUES[i];
            Button b = button(value / 1000 + "k", false);
            b.setOnClickListener(x -> dailyEt.setText(String.valueOf(value)));
            presets[i] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1);
            lp.setMargins(i == 0 ? 0 : dp(6), 0, 0, 0);
            presetRow.addView(b, lp);
        }
        sim.addView(presetRow, matchWrap(4, 0));

        // Boost
        LinearLayout boost = card(root, "Boost real walking");
        boostOn = toggle();
        boostOn.setOnCheckedChangeListener((b, on) -> { if (!filling) save(); });
        boost.addView(row("Boost steps", "Multiplies the steps you actually walk", boostOn));
        multEt = number(true);
        boost.addView(row("Multiplier", "2 = double", withUnit(multEt, "×")));
        maxSpmEt = number(false);
        boost.addView(row("Cap per minute", "270+ gets flagged by Samsung Health", withUnit(maxSpmEt, "/min")));

        // Save / revert
        LinearLayout actions = horizontal();
        actions.setGravity(Gravity.END);
        Button revert = button("Revert", false);
        revert.setOnClickListener(x -> fillForm());
        Button saveBtn = button("Save settings", true);
        saveBtn.setOnClickListener(x -> save());
        actions.addView(revert, wrap(0));
        actions.addView(saveBtn, wrap(8));
        root.addView(actions, matchWrap(14, 0));

        // Status
        LinearLayout st = card(root, "Status");
        vVersion = statusRow(st, "Samsung Health");
        vHook = statusRow(st, "Hook");
        vSeen = statusRow(st, "Last heard from hook");
        LinearLayout stActions = horizontal();
        stActions.setGravity(Gravity.END);
        Button restart = button("Restart Samsung Health", false);
        restart.setOnClickListener(x -> restartSamsungHealth());
        Button refresh = button("Check", false);
        refresh.setOnClickListener(x -> { ping(); render(); });
        stActions.addView(restart, wrap(0));
        stActions.addView(refresh, wrap(8));
        st.addView(stActions, matchWrap(12, 0));
        st.addView(text("Restarting reloads the hook. Simulation pauses for a few seconds, then carries on "
                + "from today's count. Uses root if granted, otherwise opens app info.", 12, cMuted, false), matchWrap(8, 0));

        // Log
        LinearLayout log = card(root, "Recent activity");
        logTv = text("", 12, cMuted, false);
        logTv.setTypeface(Typeface.MONOSPACE);
        log.addView(logTv);

        return scroll;
    }

    private LinearLayout card(LinearLayout parent, String heading) {
        LinearLayout c = vertical();
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        c.setBackground(rounded(cCard, cLine, 16));
        TextView h = text(heading.toUpperCase(), 12, cMuted, true);
        h.setLetterSpacing(0.06f);
        c.addView(h, matchWrap(0, 10));
        parent.addView(c, matchWrap(14, 0));
        return c;
    }

    private View row(String label, String hint, View control) {
        LinearLayout r = horizontal();
        r.setPadding(0, dp(8), 0, dp(8));
        LinearLayout texts = vertical();
        texts.addView(text(label, 15, cText, false));
        texts.addView(text(hint, 12, cMuted, false));
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(control, wrap(12));
        return r;
    }

    private View withUnit(EditText et, String unit) {
        LinearLayout l = horizontal();
        l.addView(et, new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView u = text(unit, 12, cMuted, false);
        u.setMinWidth(dp(36));
        u.setPadding(dp(6), 0, 0, 0);
        l.addView(u);
        return l;
    }

    private TextView stat(LinearLayout parent, String label) {
        LinearLayout s = vertical();
        s.setPadding(dp(10), dp(10), dp(10), dp(10));
        s.setBackground(rounded(cInput, 0, 12));
        TextView value = text("–", 17, cText, true);
        s.addView(value);
        s.addView(text(label, 12, cMuted, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(parent.getChildCount() == 0 ? 0 : dp(8), 0, 0, 0);
        parent.addView(s, lp);
        return value;
    }

    private TextView statusRow(LinearLayout parent, String label) {
        LinearLayout r = horizontal();
        r.setPadding(0, dp(4), 0, dp(4));
        r.addView(text(label, 14, cMuted, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = text("–", 14, cText, true);
        r.addView(v);
        parent.addView(r);
        return v;
    }

    private TextView notice(String msg, int fg, int bg) {
        TextView t = text(msg, 13, fg, false);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.setBackground(rounded(bg, 0, 10));
        t.setVisibility(View.GONE);
        return t;
    }

    private EditText number(boolean decimal) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        e.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        e.setTextColor(cText);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setSingleLine(true);
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        e.setBackground(rounded(cInput, cLine, 10));
        return e;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? Color.WHITE : cText);
        b.setBackground(rounded(primary ? cAccent : cInput, primary ? cAccent : cLine, 10));
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setMinHeight(dp(42));
        b.setMinimumHeight(dp(42));
        b.setStateListAnimator(null);
        return b;
    }

    private void markPresets() {
        Integer v = null;
        try { v = Integer.parseInt(dailyEt.getText().toString().trim()); } catch (Throwable ignored) { }
        for (int i = 0; i < presets.length; i++) {
            if (presets[i] == null) continue;
            boolean active = v != null && v == PRESET_VALUES[i];
            presets[i].setTextColor(active ? cAccent : cText);
            presets[i].setBackground(rounded(active ? cAccentSoft : cInput, active ? cAccent : cLine, 10));
        }
    }

    private void setPill(String label, int fg, int bg) {
        pill.setText(label);
        pill.setTextColor(fg);
        pill.setBackground(rounded(bg, 0, 99));
    }

    private void setValue(TextView t, String s, int color) {
        t.setText(s);
        t.setTextColor(color);
    }

    // ---------------------------------------------------------------- helpers

    private void setupColors() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        cBg = dark ? 0xFF0F1418 : 0xFFF4F6F8;
        cCard = dark ? 0xFF182027 : 0xFFFFFFFF;
        cText = dark ? 0xFFE6EDF2 : 0xFF16202A;
        cMuted = dark ? 0xFF93A2AE : 0xFF5D6B78;
        cLine = dark ? 0xFF26313A : 0xFFE2E7EC;
        cAccent = dark ? 0xFF3CCF7A : 0xFF1F9D55;
        cAccentSoft = dark ? 0xFF173326 : 0xFFE3F5EA;
        cWarn = dark ? 0xFFE2A54A : 0xFFB7791F;
        cWarnSoft = dark ? 0xFF33281A : 0xFFFDF3E1;
        cBad = dark ? 0xFFF26B6B : 0xFFC53030;
        cBadSoft = dark ? 0xFF3A1D1D : 0xFFFDE8E8;
        cInput = dark ? 0xFF121A20 : 0xFFF7F9FA;
        getWindow().setStatusBarColor(cBg);
        getWindow().setNavigationBarColor(cBg);
        int lightBars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getInsetsController().setSystemBarsAppearance(dark ? 0 : lightBars, lightBars);
    }

    private Switch toggle() {
        Switch s = new Switch(this);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new android.content.res.ColorStateList(states, new int[]{cAccent, cCard}));
        s.setTrackTintList(new android.content.res.ColorStateList(states, new int[]{(cAccent & 0x00FFFFFF) | 0x66000000, cLine}));
        return s;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams matchWrap(int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(topDp), 0, dp(bottomDp));
        return lp;
    }

    private LinearLayout.LayoutParams wrap(int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(leftDp), 0, 0, 0);
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private Integer parseInt(EditText e, int min, int max) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            if (v >= min && v <= max) { e.setError(null); return v; }
        } catch (Throwable ignored) { }
        e.setError(min + "–" + max);
        return null;
    }

    private Float parseFloat(EditText e, float min, float max) {
        try {
            float v = Float.parseFloat(e.getText().toString().trim());
            if (v >= min && v <= max) { e.setError(null); return Math.round(v * 10) / 10f; }
        } catch (Throwable ignored) { }
        e.setError(trimFloat(min) + "–" + trimFloat(max));
        return null;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String trimFloat(float f) {
        return f == (int) f ? String.valueOf((int) f) : String.valueOf(f);
    }

    private void toast(String s) {
        if (!TextUtils.isEmpty(s)) Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** TextWatcher that only cares about "after text changed". */
    private static final class SimpleWatcher implements android.text.TextWatcher {
        private final Runnable r;
        SimpleWatcher(Runnable r) { this.r = r; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(android.text.Editable s) { r.run(); }
    }
}
