#!/system/bin/sh
# Remove the shared configuration written to Settings.Global.
settings delete global dnta_config >/dev/null 2>&1
settings delete global dnta_status >/dev/null 2>&1
rm -f "${0%/*}/.boot_count"
