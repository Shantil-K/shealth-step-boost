#!/system/bin/sh
# Prints the current Step Boost state as key=value lines for the WebUI.
PKG=com.sec.android.app.shealth
PREFS=/data/user/0/$PKG/shared_prefs/stepboost_sim.xml

get() { v=$(settings get global "$1" 2>/dev/null); [ "$v" = "null" ] && v=""; echo "$1=$v"; }
get shealth_sim_spm
get shealth_sim_daily_max
get shealth_step_mult
get shealth_step_max_spm
get shealth_sim_spm_saved

if [ -f "$PREFS" ]; then
  echo "sim_day=$(sed -n 's/.*name="day">\([^<]*\)<.*/\1/p' "$PREFS")"
  echo "sim_steps=$(sed -n 's/.*name="steps" value="\([0-9]*\)".*/\1/p' "$PREFS")"
fi

REMOTE=$(pidof $PKG:remote)
echo "remote_pid=$REMOTE"
echo "sh_version=$(dumpsys package $PKG | sed -n 's/.*versionName=//p' | head -1)"
echo "module_installed=$(pm path com.stepwriter.shealth >/dev/null 2>&1 && echo 1 || echo 0)"

LOGS=/data/adb/lspd/log
HOOKED=0
if [ -n "$REMOTE" ] && grep -h "hooked $PKG:remote" $LOGS/modules_*.log 2>/dev/null | grep -q " $REMOTE: "; then HOOKED=1; fi
echo "hooked=$HOOKED"

# Today's total as Samsung Health last wrote it (only if logged today).
LAST=$(logcat -d -s SHEALTH#CombinedDataHandler:I 2>/dev/null | grep "DaySummary update" | tail -1)
case "$LAST" in *"$(date +%Y-%m-%d)("*) echo "today_total=$(echo "$LAST" | sed -n 's/.*newCount=\([0-9]*\).*/\1/p')";; esac

echo "today=$(date +%Y-%m-%d)"
echo "now=$(date +%s)"
echo "---LOG---"
grep -h "StepBoost" $LOGS/modules_*.log 2>/dev/null | grep -v "Loading class" | tail -12 \
  | sed 's/^\[ \([0-9-]*\)T\([0-9:]*\)\.[0-9]* .*\] /\2 /'
