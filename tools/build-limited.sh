#!/bin/bash
# Build Arisu Companion while keeping the host inside safe limits.
#
# Limits (2-core VPS, 4 GB RAM):
#   CPU  : hard ceiling 150% => 75% of the machine. A watchdog stops the build
#          if the average climbs above 80% for 10 s.
#   RAM  : MemoryHigh 2200M, MemoryMax 2800M, no swap. Watchdog stops the build
#          if MemAvailable drops below 300 MB.
#
# The watchdog stops the BUILD UNIT (systemctl stop arisu-build); it never
# powers off the VPS, because a build that trips a limit is a build problem.
#
# Usage: tools/build-limited.sh [gradle-task ...]
#   default tasks: assembleRelease testReleaseUnitTest
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE="${GRADLE_BIN:-/opt/gradle-8.7/bin/gradle}"
UNIT="arisu-build"
GUARD_LOG="${GUARD_LOG:-/tmp/guard.log}"
TASKS=("$@")
[ ${#TASKS[@]} -eq 0 ] && TASKS=(assembleRelease testReleaseUnitTest)

if ! command -v systemd-run >/dev/null 2>&1; then
  echo "systemd-run not available; run the build manually:" >&2
  echo "  cd $ROOT && $GRADLE ${TASKS[*]} --no-daemon --max-workers=1" >&2
  exit 1
fi

systemctl reset-failed "$UNIT" 2>/dev/null
"$GRADLE" --stop >/dev/null 2>&1
: > "$GUARD_LOG"

# Opt-in live-test switches (and the key they need) are passed through only
# when the caller exported them; nothing is read from disk.
ENV_ARGS=()
for var in GEMINI_LIVE_TESTS GEMINI_IMAGE_TEST GEMINI_API_KEY; do
  [ -n "${!var:-}" ] && ENV_ARGS+=(--setenv="$var=${!var}")
done

systemd-run --unit="$UNIT" --collect --service-type=exec \
  "${ENV_ARGS[@]}" \
  --property=CPUQuota=150% \
  --property=MemoryHigh=2200M \
  --property=MemoryMax=2800M \
  --property=MemorySwapMax=0 \
  --property=Nice=10 \
  --property=WorkingDirectory="$ROOT" \
  /bin/bash -c "exec $GRADLE ${TASKS[*]} --no-daemon --max-workers=1 --console=plain" \
  > /dev/null || { echo "failed to start $UNIT" >&2; exit 1; }

CG="/sys/fs/cgroup/system.slice/${UNIT}.service"
prev=0
over=0
while systemctl is-active --quiet "$UNIT"; do
  [ -r "$CG/cpu.stat" ] || break
  cur=$(awk '/^usage_usec/{print $2}' "$CG/cpu.stat")
  memavail=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)
  if [ "$prev" -ne 0 ]; then
    # micro-seconds (usec) over a 5 s window on a 2-core box -> % of machine
    pct=$(( (cur - prev) / (5 * 1000 * 2 * 10) ))
    echo "$(date +%H:%M:%S) cpu=${pct}% (max 80) mem_avail=${memavail}MB (min 300)" >> "$GUARD_LOG"
    if [ "$pct" -gt 80 ] || [ "$memavail" -lt 300 ]; then
      over=$((over + 1))
      if [ "$over" -ge 2 ]; then
        echo "$(date +%H:%M:%S) LIMIT BREACHED -> stopping $UNIT" >> "$GUARD_LOG"
        systemctl stop "$UNIT"
        echo "Build stopped: CPU or memory limit exceeded (see $GUARD_LOG)" >&2
        exit 2
      fi
    else
      over=0
    fi
  fi
  prev=$cur
  sleep 5
done

tail -3 "$GUARD_LOG"
echo "Build unit finished. For full output:" >&2
echo "  journalctl -u $UNIT --no-pager -n 50" >&2
