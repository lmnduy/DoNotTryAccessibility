#!/system/bin/sh

ui_print "********************************************"
ui_print " DoNotTryAccessibility (Zygisk) v2.0.0"
ui_print "********************************************"
ui_print "- 无 APK：配置请使用 KernelSU / APatch 的模块 WebUI"

if [ "$ARCH" != "arm64" ] && [ "$ARCH" != "arm" ]; then
  abort "! Unsupported architecture: $ARCH (arm64/arm only)"
fi

if [ "$API" -lt 27 ]; then
  abort "! Android 8.1 (API 27) or newer is required"
fi

if [ ! -d /data/adb/modules/zygisksu ] && ! grep -q "zygisk" /proc/mounts 2>/dev/null; then
  ui_print "! Zygisk does not look enabled."
  ui_print "! Enable Zygisk in the manager before rebooting."
fi

ui_print "- Installed. Reboot, then open the module WebUI."
ui_print "- Logs: adb logcat -s DNTA-Zygisk"
