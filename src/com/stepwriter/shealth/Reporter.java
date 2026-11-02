package com.stepwriter.shealth;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;

import de.robv.android.xposed.XposedBridge;

/** Hook side: sends progress to the settings app and answers its pings. */
final class Reporter {
    private static volatile int lastTotal = -1;
    private static volatile boolean pingReceiverInstalled = false;

    static Application app() {
        try {
            return (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Log a line and show it in the app's "Recent activity". */
    static void event(String text) {
        XposedBridge.log("StepBoost: " + text);
        send(text);
    }

    /** Today's combined step total, as Samsung Health just computed it. */
    static void total(int steps) {
        if (steps == lastTotal) return;
        lastTotal = steps;
        send(null);
    }

    /** Listen for pings from the app (in the step-service process only). */
    static void installPingReceiver(Context ctx) {
        if (pingReceiverInstalled) return;
        pingReceiverInstalled = true;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                Config.refresh(c, true); // the app just saved; pick it up now
                send(null);
            }
        };
        IntentFilter f = new IntentFilter(Keys.ACTION_PING);
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else ctx.registerReceiver(r, f);
    }

    private static void send(String text) {
        Application ctx = app();
        if (ctx == null) return;
        try {
            Intent i = new Intent(Keys.ACTION_STATUS)
                    .setComponent(new ComponentName(Keys.MODULE_PKG, Keys.MODULE_PKG + ".StatusReceiver"))
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            if (text != null) i.putExtra(Keys.X_TEXT, text);

            SharedPreferences sim = ctx.getSharedPreferences(StepSimulator.PREFS, 0);
            i.putExtra(Keys.X_SIM_DAY, sim.getString("day", ""));
            i.putExtra(Keys.X_SIM_STEPS, sim.getInt("steps", 0));
            i.putExtra(Keys.X_TOTAL, lastTotal);
            i.putExtra(Keys.X_PROCESS, Application.getProcessName());
            try {
                i.putExtra(Keys.X_VERSION, ctx.getPackageManager().getPackageInfo(Keys.SH_PKG, 0).versionName);
            } catch (Throwable ignored) {
            }
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            XposedBridge.log("StepBoost: status send failed: " + t);
        }
    }

    private Reporter() {}
}
