package com.stepwriter.shealth;

import android.app.Application;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * Feeds made-up walking minutes into Samsung Health's own step store, the same
 * call the real pedometer uses (PedometerRealTimeDataManager.m(data)).
 *
 * Off by default. Control it from adb:
 *   adb shell settings put global shealth_sim_spm 120        # steps per minute, 0 = off
 *   adb shell settings put global shealth_sim_daily_max 8000 # stop after this many fake steps today
 *
 * Each minute gets spm +/- 12% steps, never 230 or more (Samsung reports 270+ minutes).
 * Class and method names below are obfuscated and match Samsung Health 7.1.0 only.
 */
final class StepSimulator {
    private static final String TAG = "StepBoost[sim]: ";
    private static final String DATA_CLASS =
            "com.samsung.android.app.shealth.tracker.pedometer.service.stepcounter.PedometerSContextManager$SContextPedometerData";
    private static final String STORE_CLASS = "iyp";   // PedometerRealTimeDataManager
    private static final String UTIL_CLASS = "qhf";    // has C(duration, distance) -> speed

    private static final int HARD_MAX_PER_MIN = 229;
    private static final double STRIDE_M = 0.72;
    private static final double KCAL_PER_STEP = 0.04;

    private static final Set<Long> simulatedMinutes = ConcurrentHashMap.newKeySet();
    private static final Random rnd = new Random();

    static boolean isSimulated(long time) {
        return simulatedMinutes.contains(time);
    }

    static void start(final ClassLoader cl) {
        Thread t = new Thread(() -> {
            XposedBridge.log(TAG + "thread started");
            long lastMinute = 0;
            while (true) {
                try {
                    Thread.sleep(5_000);
                    long minute = System.currentTimeMillis() / 60_000 * 60_000;
                    if (minute == lastMinute) continue;
                    lastMinute = minute;
                    tick(cl, minute);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable e) {
                    XposedBridge.log(TAG + "tick failed: " + e);
                }
            }
        }, "StepBoostSim");
        t.setDaemon(true);
        t.start();
    }

    private static void tick(ClassLoader cl, long minute) throws Exception {
        Application app = (Application) Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null);
        if (app == null) { skip("no app context"); return; }

        int spm = readInt(app, "shealth_sim_spm", 0);
        if (spm <= 0) { skip("spm=" + spm); return; }
        int dailyMax = readInt(app, "shealth_sim_daily_max", 8000);

        // Remember today's fake total across app restarts.
        SharedPreferences prefs = app.getSharedPreferences("stepboost_sim", 0);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(minute));
        int doneToday = today.equals(prefs.getString("day", "")) ? prefs.getInt("steps", 0) : 0;
        if (doneToday >= dailyMax) { skip("daily max reached " + doneToday); return; }

        Object store = cl.loadClass(STORE_CLASS).getMethod("g").invoke(null);
        if (store == null) { skip("store not ready"); return; }

        int steps = (int) Math.round(spm * (0.88 + rnd.nextDouble() * 0.24));
        steps = Math.max(1, Math.min(steps, Math.min(HARD_MAX_PER_MIN, dailyMax - doneToday)));

        Class<?> dataCls = cl.loadClass(DATA_CLASS);
        Object d = dataCls.getConstructor().newInstance();
        float distance = (float) (steps * STRIDE_M);
        double duration = 60_000d;
        dataCls.getField("time").setLong(d, minute);
        dataCls.getField("totalStep").setInt(d, steps);
        dataCls.getField("walkStep").setInt(d, steps);
        dataCls.getField("runStep").setInt(d, 0);
        dataCls.getField("distance").setFloat(d, distance);
        dataCls.getField("calories").setFloat(d, (float) (steps * KCAL_PER_STEP));
        dataCls.getField("duration").setDouble(d, duration);
        dataCls.getField("speed").setFloat(d, speed(cl, duration, distance));

        simulatedMinutes.add(minute);
        Method store_m = store.getClass().getMethod("m", dataCls);
        store_m.invoke(store, d);

        prefs.edit().putString("day", today).putInt("steps", doneToday + steps).apply();
        XposedBridge.log(TAG + "+" + steps + " steps at " + minute + " (today " + (doneToday + steps) + "/" + dailyMax + ")");
    }

    private static String lastSkip = "";

    /** Logs why a minute was skipped, only when the reason changes. */
    private static void skip(String why) {
        if (!why.equals(lastSkip)) XposedBridge.log(TAG + "skip: " + why);
        lastSkip = why;
    }

    /** Uses Samsung Health's own speed formula so simulated minutes match real ones. */
    private static float speed(ClassLoader cl, double duration, float distance) {
        try {
            for (Method m : cl.loadClass(UTIL_CLASS).getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!m.getName().equals("C") || p.length != 2 || m.getReturnType() != float.class) continue;
                m.setAccessible(true);
                Object a0 = p[0] == double.class ? (Object) duration : (Object) (long) duration;
                return (Float) m.invoke(null, a0, distance);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "speed helper failed: " + t);
        }
        return distance / 60f; // fallback: metres per second
    }

    private static int readInt(Application app, String key, int def) {
        try {
            String v = Settings.Global.getString(app.getContentResolver(), key);
            return v != null ? Integer.parseInt(v.trim()) : def;
        } catch (Throwable t) {
            return def;
        }
    }
}
