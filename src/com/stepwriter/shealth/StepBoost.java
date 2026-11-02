package com.stepwriter.shealth;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook entry point (loaded by Vector/LSPosed into Samsung Health).
 *
 * - Boost: scales each one-minute pedometer bucket right before it is saved.
 *   Both step sources build a SContextPedometerData per minute and call
 *   makeHealthData(); we rewrite the returned record and leave the object
 *   alone, so a minute saved twice is never boosted twice.
 * - Total: reads today's combined count when TodayDataManager refreshes it.
 * - Simulation: see StepSimulator.
 *
 * Obfuscated names (q940 = TodayDataManager) match Samsung Health 7.1.0.
 */
public class StepBoost implements IXposedHookLoadPackage {
    private static final String DATA_CLASS =
            "com.samsung.android.app.shealth.tracker.pedometer.service.stepcounter.PedometerSContextManager$SContextPedometerData";
    private static final String KEYS_CLASS =
            "com.samsung.android.app.shealth.constant.PedometerDataConstants$PedometerExt";
    private static final String TODAY_MANAGER = "q940";
    private static final String DAY_STEP_DATA = "com.samsung.android.app.shealth.step.data.DayStepData";
    private static final int DEVICE_ALL = 100003; // combined phone + wearables

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!Keys.SH_PKG.equals(lp.packageName)) return;
        final ClassLoader cl = lp.classLoader;
        hookBoost(cl);
        XposedBridge.log("StepBoost: hooked " + lp.processName);

        // PedometerService (and the step store) runs in the :remote process.
        if ((Keys.SH_PKG + ":remote").equals(lp.processName)) {
            hookTotal(cl);
            StepSimulator.start(cl);
        }
    }

    private static void hookBoost(ClassLoader cl) throws Throwable {
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
                Object d = p.thisObject;
                // Simulated minutes are already sized as wanted; don't multiply them again.
                if (StepSimulator.isSimulated(getLong(d, "time"))) return;
                Config.refresh(Reporter.app(), false);
                if (!Config.boostOn || Config.mult <= 1.0) return;
                int total = getInt(d, "totalStep");
                if (total <= 0) return;

                // Never lower a real minute, never push a minute past maxSpm.
                int target = (int) Math.round(total * Config.mult);
                target = Math.max(total, Math.min(target, Config.maxSpm));
                if (target == total) return;
                double f = (double) target / total;

                int run = (int) Math.round(getInt(d, "runStep") * f);
                int walk = (int) Math.round(getInt(d, "walkStep") * f);
                if (walk + run > target) walk = Math.max(0, target - run);

                Method putInt = hd.getClass().getMethod("putInt", String.class, int.class);
                Method putFloat = hd.getClass().getMethod("putFloat", String.class, float.class);
                Method getFloatM = hd.getClass().getMethod("getFloat", String.class);
                putInt.invoke(hd, kCount, target);
                putInt.invoke(hd, kWalk, walk);
                putInt.invoke(hd, kRun, run);
                putFloat.invoke(hd, kDist, (float) (getFloat(d, "distance") * f));
                putFloat.invoke(hd, kCal, (float) (getFloat(d, "calories") * f));
                putFloat.invoke(hd, kSpeed, (float) ((Float) getFloatM.invoke(hd, kSpeed) * f));

                Reporter.event("Boost " + total + " → " + target + " steps");
            }
        });
    }

    private static void hookTotal(ClassLoader cl) {
        try {
            Class<?> day = XposedHelpers.findClass(DAY_STEP_DATA, cl);
            XposedHelpers.findAndHookMethod(TODAY_MANAGER, cl, "d", day, java.util.ArrayList.class, long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Object d = p.args[0];
                                int type = ((Number) d.getClass().getMethod("getDeviceType").invoke(d)).intValue();
                                if (type != DEVICE_ALL) return;
                                int steps = ((Number) d.getClass().getMethod("getStepCount").invoke(d)).intValue();
                                Reporter.total(steps);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            // Only the "today total" display depends on this; boost and simulation still work.
            XposedBridge.log("StepBoost: total hook failed: " + t);
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
