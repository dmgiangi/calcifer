#!/bin/sh
# Never print wg output: it includes peer identities. Only Home initiates recovery.
set -eu

state_dir=${WG_HEALTH_STATE_DIR:-/run/wireguard-health}
uptime_s=$(cut -d. -f1 /proc/uptime)

case ${1:-} in
    init)
        mkdir -p "$state_dir"
        printf '%s\n' "$uptime_s" >"$state_dir/started"
        exit 0
        ;;
    ready|live) ;;
    *) exit 2 ;;
esac

now=$(date +%s)
# This topology has exactly one peer. Empty, failed or malformed output is unhealthy.
if handshake_output=$(wg show wg0 latest-handshakes 2>/dev/null); then
    handshake=$(printf '%s\n' "$handshake_output" | awk '
        NF == 2 && $2 ~ /^[0-9]+$/ { timestamp = $2; valid++ }
        { total++ }
        END { if (total == 1 && valid == 1) print timestamp; else print 0 }
    ')
else
    handshake=0
fi
age=$((now - handshake))
if [ "$handshake" -gt 0 ] && [ "$age" -ge 0 ]; then
    if [ "$1" = ready ] && [ "$age" -le 180 ]; then exit 0; fi
    if [ "$1" = live ] && [ "$age" -le 300 ]; then exit 0; fi
fi

# Readiness always reports the real link state, including during recovery cooldown.
if [ "$1" = ready ]; then exit 1; fi

started=$(cat "$state_dir/started")
case $started in ''|*[!0-9]*) exit 1 ;; esac
if [ $((uptime_s - started)) -lt 300 ]; then exit 0; fi

# /run is an emptyDir: this survives container restarts, but not Pod replacement.
# Use node uptime for cooldown so wall-clock adjustments cannot trigger a burst.
if [ -f "$state_dir/last-recovery" ]; then
    last_recovery=$(cat "$state_dir/last-recovery")
    case $last_recovery in ''|*[!0-9]*) exit 1 ;; esac
    if [ $((uptime_s - last_recovery)) -lt 1800 ]; then exit 0; fi
fi

printf '%s\n' "$uptime_s" >"$state_dir/last-recovery"
printf '%s\n' 'wireguard health status=unhealthy action=restart-requested'
# failureThreshold must remain 1: this invocation consumes the recovery budget.
exit 1