#!/system/bin/sh
# Portal Overlays silent-install daemon.
#
# Runs as the shell user (uid 2000), started once over ADB by enable_portal_permissions
# (or by hand). The shell user can `pm install` silently, so this lets Portal Overlays
# self-update with no on-device installer dialog: the app drops an APK in the queue, this
# installs it and writes a heartbeat so the app knows the silent path is available.
#
# Like every non-root helper, it does NOT survive a reboot — re-run the helper to restart it.
# When it's not running, the app falls back to the one-tap PackageInstaller flow.

Q="$1"
[ -n "$Q" ] || Q=/sdcard/Android/data/com.portal.overlays/files/installq
mkdir -p "$Q" 2>/dev/null

while true; do
  for f in "$Q"/*.apk; do
    [ -e "$f" ] || continue
    # Copy to shell-owned storage first and capture pm's output via a pipe. Handing the
    # system package service a /sdcard FD makes the install fail with "Failed transaction",
    # so we stage in /data/local/tmp and write the log ourselves.
    t=/data/local/tmp/po_install.apk
    cp "$f" "$t" 2>/dev/null
    out="$(pm install -r "$t" 2>&1)"
    rm -f "$t" 2>/dev/null
    echo "$out" > "$f.log" 2>/dev/null
    case "$out" in
      *Success*) mv "$f" "$f.done" 2>/dev/null ;;
      *)         mv "$f" "$f.failed" 2>/dev/null ;;
    esac
  done
  date +%s > "$Q/.heartbeat" 2>/dev/null
  sleep 2
done
