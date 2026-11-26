#!/system/bin/sh
# Safety net: if system_server keeps crashing before late_start service runs
# three times in a row, disable the module automatically so the device can boot.
MODDIR=${0%/*}
COUNT_FILE="$MODDIR/.boot_count"

count=0
[ -f "$COUNT_FILE" ] && count=$(cat "$COUNT_FILE" 2>/dev/null)
case "$count" in
  ''|*[!0-9]*) count=0 ;;
esac
count=$((count + 1))
echo "$count" > "$COUNT_FILE"

if [ "$count" -ge 3 ]; then
  touch "$MODDIR/disable"
fi
