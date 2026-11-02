package com.stepwriter.shealth;

import android.app.Application;
import android.content.SharedPreferences;

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
 * Each minute gets the chosen pace +/- 12%, never 230 or more (Samsung reports 270+).
 * Class and method names below are obfuscated and match Samsung Health 7.1.0 only.
 */
final class StepSimulator {
    static final String PREFS = "stepboost_sim"; // inside Samsung Health's data
    private static final String DATA_CLASS =
            "com.samsung.android.app.shealth.tracker.pedometer.service.stepcounter.PedometerSContextManager$SContextPedometerData";
    private static final String STORE_CLASS = "iyp";   // PedometerRealTimeDataManager
    private static final String UTIL_CLASS = "qhf";    // has C(duration, distance) -> speed

    private static final double STRIDE_M = 0.72;
    private static final double KCAL_PER_STEP = 0.04;

    private static final Set<Long> simulatedMinutes = ConcurrentHashMap.newKeySet();
    private static final Random rnd = new Random();
    private static String lastSkip = "";

    static boolean isSimulated(long time) {
        return simulatedMinutes.contains(time);
    }

    static void start(final ClassLoader cl) {
        Thread t = new Thread(() -> {
            long lastMinute = 0;
            boolean greeted = false;
            while (true) {
                try {
                    Thread.sleep(5_000);
                    Application app = Reporter.app();
                    if (app == null) continue;
                    if (!greeted) {
                        greeted = true;
                        Reporter.installPingReceiver(app);
                        Config.refresh(app, true);
                        Reporter.event("Hook loaded");
                    }
                    long minute = System.currentTimeMillis() / 60_000 * 60_000;
                    if (minute == lastMinute) continue;
                    lastMinute = minute;
                    tick(cl, app, minute);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable e) {
                    XposedBridge.log("StepBoost[sim]: tick failed: " + e);
                }
            }
        }, "StepBoostSim");
        t.setDaemon(true);
        t.start();
    }

    private static void tick(ClassLoader cl, Application app, long minute) throws Exception {
        Config.refresh(app, false);
        if (!Config.simOn || Config.simSpm <= 0) { skip("off"); return; }
        int dailyMax = Config.simDailyMax;

        // Remember today's fake total across app restarts.
        SharedPreferences prefs = app.getSharedPreferences(PREFS, 0);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(minute));
        int doneToday = today.equals(prefs.getString("day", "")) ? prefs.getInt("steps", 0) : 0;
        if (doneToday >= dailyMax) { skip("target reached"); return; }

        Object store = cl.loadClass(STORE_CLASS).getMethod("g").invoke(null);
        if (store == null) { skip("store not ready"); return; }

        int steps = (int) Math.round(Config.simSpm * (0.88 + rnd.nextDouble() * 0.24));
        steps = Math.max(1, Math.min(steps, Math.min(Keys.SIM_HARD_MAX, dailyMax - doneToday)));

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
        store.getClass().getMethod("m", dataCls).invoke(store, d);

        prefs.edit().putString("day", today).putInt("steps", doneToday + steps).commit();
        lastSkip = "";
        Reporter.event("+" + steps + " simulated (" + (doneToday + steps) + "/" + dailyMax + ")");
    }

    /** Logs why a minute was skipped, only when the reason changes. */
    private static void skip(String why) {
        if (why.equals(lastSkip)) return;
        lastSkip = why;
        if (why.equals("target reached")) Reporter.event("Daily target reached");
        else XposedBridge.log("StepBoost[sim]: skip: " + why);
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
            XposedBridge.log("StepBoost[sim]: speed helper failed: " + t);
        }
        return distance / 60f; // fallback: metres per second
    }

    private StepSimulator() {}
}
