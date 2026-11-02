package com.stepwriter.shealth;

/** Names shared by the hook (inside Samsung Health) and the settings app. */
final class Keys {
    static final String MODULE_PKG = "com.stepwriter.shealth";
    static final String SH_PKG = "com.sec.android.app.shealth";
    static final String TESTED_VERSION = "7.1.0";

    // Config written by the app, read by the hook through XSharedPreferences.
    static final String PREFS = "config";
    static final String SIM_ON = "sim_on";
    static final String SIM_SPM = "sim_spm";
    static final String SIM_DAILY_MAX = "sim_daily_max";
    static final String BOOST_ON = "boost_on";
    static final String MULT = "mult";
    static final String MAX_SPM = "max_spm";

    static final boolean D_SIM_ON = false;
    static final int D_SIM_SPM = 110;
    static final int D_SIM_DAILY_MAX = 8000;
    static final boolean D_BOOST_ON = true;
    static final float D_MULT = 2.0f;
    static final int D_MAX_SPM = 180;

    /** Simulated minutes stay below this; Samsung Health reports minutes of 270+. */
    static final int SIM_HARD_MAX = 229;

    // Hook -> app status broadcasts, app -> hook ping.
    static final String ACTION_STATUS = MODULE_PKG + ".STATUS";
    static final String ACTION_PING = MODULE_PKG + ".PING";
    static final String X_TEXT = "text";
    static final String X_SIM_STEPS = "sim_steps";
    static final String X_SIM_DAY = "sim_day";
    static final String X_TOTAL = "total";
    static final String X_VERSION = "version";
    static final String X_PROCESS = "process";

    private Keys() {}
}
