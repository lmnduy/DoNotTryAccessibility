#!/system/bin/sh
# Reached late_start: the boot survived, reset the crash counter.
MODDIR=${0%/*}
rm -f "$MODDIR/.boot_count"
