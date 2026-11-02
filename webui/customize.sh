# KernelSU / Magisk install script: also installs the bundled hook APK.
if [ -f "$MODPATH/StepBoost.apk" ]; then
  ui_print "- Installing Step Boost hook APK"
  cp "$MODPATH/StepBoost.apk" /data/local/tmp/StepBoost.apk
  chmod 644 /data/local/tmp/StepBoost.apk
  if pm install -r /data/local/tmp/StepBoost.apk >/dev/null 2>&1; then
    ui_print "  Done. Enable it in Vector/LSPosed with Samsung Health in scope."
  else
    ui_print "  ! APK install failed; install build/StepBoost.apk manually."
  fi
  rm -f /data/local/tmp/StepBoost.apk "$MODPATH/StepBoost.apk"
fi
set_perm "$MODPATH/status.sh" 0 0 0755
