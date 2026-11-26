#!/system/bin/sh

ui_print "********************************************"
ui_print " DoNotTryAccessibility (Zygisk) v2.0.0"
ui_print "********************************************"
ui_print "- No APK: configure with the module WebUI (KernelSU / APatch)."

if [ "${API:-0}" -lt 27 ]; then
  abort "! Android 8.1 (API 27) or newer is required"
fi

if [ ! -d /data/adb/modules/zygisksu ]; then
  ui_print "! Zygisk Next module not found."
  ui_print "! Make sure Zygisk is enabled before rebooting."
fi

ui_print "- Installed. Reboot, then open the module WebUI."
ui_print "- Logs: adb logcat -s DNTA-Zygisk"
