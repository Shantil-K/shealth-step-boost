package com.stepwriter.shealth;

import android.content.Context;
import android.provider.Settings;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * Hook-side view of the settings. Reads the app's prefs through LSPosed/Vector
 * (XSharedPreferences). Until the app has saved once, it falls back to the old
 * adb `settings put global shealth_*` keys, then to defaults.
 */
final class Config {
    static volatile boolean simOn = Keys.D_SIM_ON;
    static volatile int simSpm = Keys.D_SIM_SPM;
    static volatile int simDailyMax = Keys.D_SIM_DAILY_MAX;
    static volatile boolean boostOn = Keys.D_BOOST_ON;
    static volatile double mult = Keys.D_MULT;
    static volatile int maxSpm = Keys.D_MAX_SPM;

    private static XSharedPreferences prefs;
    private static long lastRead = 0;
    private static String lastSource = "";

    static synchronized void refresh(Context ctx, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastRead < 30_000) return;
        lastRead = now;

        String source;
        try {
            if (prefs == null) prefs = new XSharedPreferences(Keys.MODULE_PKG, Keys.PREFS);
            else if (prefs.hasFileChanged()) prefs.reload();
        } catch (Throwable t) {
            prefs = null;
        }

        if (prefs != null && prefs.contains(Keys.SIM_SPM)) {
            simOn = prefs.getBoolean(Keys.SIM_ON, Keys.D_SIM_ON);
            simSpm = prefs.getInt(Keys.SIM_SPM, Keys.D_SIM_SPM);
            simDailyMax = prefs.getInt(Keys.SIM_DAILY_MAX, Keys.D_SIM_DAILY_MAX);
            boostOn = prefs.getBoolean(Keys.BOOST_ON, Keys.D_BOOST_ON);
            mult = prefs.getFloat(Keys.MULT, Keys.D_MULT);
            maxSpm = prefs.getInt(Keys.MAX_SPM, Keys.D_MAX_SPM);
            source = "app";
        } else if (ctx != null) {
            int legacySpm = globalInt(ctx, "shealth_sim_spm", 0);
            simOn = legacySpm > 0;
            simSpm = legacySpm > 0 ? legacySpm : Keys.D_SIM_SPM;
            simDailyMax = globalInt(ctx, "shealth_sim_daily_max", Keys.D_SIM_DAILY_MAX);
            mult = globalDouble(ctx, "shealth_step_mult", Keys.D_MULT);
            boostOn = mult > 1.0;
            maxSpm = globalInt(ctx, "shealth_step_max_spm", Keys.D_MAX_SPM);
            source = "adb settings";
        } else {
            source = "defaults";
        }

        if (!source.equals(lastSource)) {
            XposedBridge.log("StepBoost: config from " + source);
            lastSource = source;
        }
    }

    private static int globalInt(Context ctx, String key, int def) {
        try {
            String v = Settings.Global.getString(ctx.getContentResolver(), key);
            return v != null ? Integer.parseInt(v.trim()) : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static double globalDouble(Context ctx, String key, double def) {
        try {
            String v = Settings.Global.getString(ctx.getContentResolver(), key);
            return v != null ? Double.parseDouble(v.trim()) : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private Config() {}
}
