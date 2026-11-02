package com.stepwriter.shealth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** App side: stores progress sent from the hook inside Samsung Health. */
public class StatusReceiver extends BroadcastReceiver {
    static final String PREFS = "status";
    private static final int MAX_EVENTS = 25;

    @Override
    public void onReceive(Context ctx, Intent i) {
        if (!Keys.ACTION_STATUS.equals(i.getAction())) return;
        long now = System.currentTimeMillis();
        SharedPreferences s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = s.edit()
                .putLong("last_seen", now)
                .putString("sim_day", i.getStringExtra(Keys.X_SIM_DAY))
                .putInt("sim_steps", i.getIntExtra(Keys.X_SIM_STEPS, 0));

        int total = i.getIntExtra(Keys.X_TOTAL, -1);
        if (total >= 0) {
            e.putInt("total", total).putString("total_day", day(now));
        }
        String version = i.getStringExtra(Keys.X_VERSION);
        if (version != null) e.putString("version", version);

        String text = i.getStringExtra(Keys.X_TEXT);
        if (text != null) {
            String line = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(now)) + "  " + text;
            String old = s.getString("events", "");
            String all = old.isEmpty() ? line : line + "\n" + old;
            String[] lines = all.split("\n");
            if (lines.length > MAX_EVENTS) {
                StringBuilder b = new StringBuilder();
                for (int n = 0; n < MAX_EVENTS; n++) b.append(n == 0 ? "" : "\n").append(lines[n]);
                all = b.toString();
            }
            e.putString("events", all);
        }
        e.apply();
    }

    static String day(long t) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(t));
    }
}
