package com.stepwriter.shealth;

import android.app.Application;
import android.provider.Settings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Scales each one-minute pedometer bucket right before Samsung Health saves it.
 *
 * Both step sources (sensor hub and passive health services) build a
 * SContextPedometerData per minute and call makeHealthData() to turn it into
 * the stored record. We leave the object alone and rewrite the returned record,
 * so calling it twice on the same minute never double-scales.
 *
 * Tune from adb (no reboot needed, picked up within a minute):
 *   adb shell settings put global shealth_step_mult 2.5
 *   adb shell settings put global shealth_step_max_spm 180
 */
public class StepBoost implements IXposedHookLoadPackage {
    private static final String TAG = "StepBoost: ";
    private static final String PKG = "com.sec.android.app.shealth";
    private static final String DATA_CLASS =
            "com.samsung.android.app.shealth.tracker.pedometer.service.stepcounter.PedometerSContextManager$SContextPedometerData";
    private static final String KEYS_CLASS =
            "com.samsung.android.app.shealth.constant.PedometerDataConstants$PedometerExt";

    private static final double DEFAULT_MULT = 2.0;
    private static final int DEFAULT_MAX_SPM = 180; // brisk walk; keeps minutes believable

    private static double mult = DEFAULT_MULT;
    private static int maxSpm = DEFAULT_MAX_SPM;
    private static long lastConfigRead = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!PKG.equals(lp.packageName)) return;
        final ClassLoader cl = lp.classLoader;
        final Class<?> keys = XposedHelpers.findClass(KEYS_CLASS, cl);
        final String kCount = (String) keys.getField("COUNT").get(null);
        final String kWalk = (String) keys.getField("WALK_STEP").get(null);
        final String kRun = (String) keys.getField("RUN_STEP").get(null);
        final String kDist = (String) keys.getField("DISTANCE").get(null);
        final String kCal = (String) keys.getField("CALORIE").get(null);
        final String kSpeed = (String) keys.getField("SPEED").get(null);

        XposedHelpers.findAndHookMethod(DATA_CLASS, cl, "makeHealthData", boolean.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                Object hd = p.getResult();
                if (hd == null) return;
                refreshConfig();
                Object d = p.thisObject;
                // Simulated minutes are already sized as wanted; don't multiply them again.
                if (StepSimulator.isSimulated(getLong(d, "time"))) return;
                int total = getInt(d, "totalStep");
                if (total <= 0 || mult <= 1.0) return;

                // Never lower a real minute, never push a minute past maxSpm.
                int target = (int) Math.round(total * mult);
                target = Math.max(total, Math.min(target, maxSpm));
                if (target == total) return;
                double f = (double) target / total;

                int run = (int) Math.round(getInt(d, "runStep") * f);
                int walk = (int) Math.round(getInt(d, "walkStep") * f);
                if (walk + run > target) walk = Math.max(0, target - run);

                Method putInt = hd.getClass().getMethod("putInt", String.class, int.class);
                Method putFloat = hd.getClass().getMethod("putFloat", String.class, float.class);
                putInt.invoke(hd, kCount, target);
                putInt.invoke(hd, kWalk, walk);
                putInt.invoke(hd, kRun, run);
                putFloat.invoke(hd, kDist, (float) (getFloat(d, "distance") * f));
                putFloat.invoke(hd, kCal, (float) (getFloat(d, "calories") * f));
                Method getFloatM = hd.getClass().getMethod("getFloat", String.class);
                putFloat.invoke(hd, kSpeed, (float) ((Float) getFloatM.invoke(hd, kSpeed) * f));

                XposedBridge.log(TAG + total + " -> " + target + " steps (x" + mult + ", cap " + maxSpm + "/min)");
            }
        });
        XposedBridge.log(TAG + "hooked " + lp.processName);

        // PedometerService (and its step store) runs in the :remote process.
        if ((PKG + ":remote").equals(lp.processName)) StepSimulator.start(cl);
    }

    private static void refreshConfig() {
        long now = System.currentTimeMillis();
        if (now - lastConfigRead < 60_000) return;
        lastConfigRead = now;
        try {
            Application app = (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app == null) return;
            String m = Settings.Global.getString(app.getContentResolver(), "shealth_step_mult");
            String s = Settings.Global.getString(app.getContentResolver(), "shealth_step_max_spm");
            mult = m != null ? Double.parseDouble(m) : DEFAULT_MULT;
            maxSpm = s != null ? Integer.parseInt(s) : DEFAULT_MAX_SPM;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "config read failed, using defaults: " + t);
        }
    }

    private static int getInt(Object o, String name) throws Exception {
        Field f = o.getClass().getField(name);
        return f.getInt(o);
    }

    private static long getLong(Object o, String name) throws Exception {
        Field f = o.getClass().getField(name);
        return f.getLong(o);
    }

    private static float getFloat(Object o, String name) throws Exception {
        Field f = o.getClass().getField(name);
        return f.getFloat(o);
    }
}
