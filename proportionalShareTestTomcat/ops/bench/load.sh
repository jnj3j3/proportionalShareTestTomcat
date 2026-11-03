#!/bin/sh
# Parallel load against user (8080) and admin (8081) using wrk.
# Requires: apt install wrk  (or build from https://github.com/wg/wrk)
#
# Usage: ./load.sh <seconds> <connections-per-side> <threads-per-side>
set -eu

DUR=${1:-30}
C=${2:-50}
T=${3:-2}

command -v wrk >/dev/null 2>&1 || {
  echo "wrk not found. Install: sudo apt install -y wrk" >&2
  exit 1
}

tmp=$(mktemp -d)
wrk -t"$T" -c"$C" -d"${DUR}s" --latency http://127.0.0.1:8080/work > "$tmp/user"  2>&1 &
USER_PID=$!
wrk -t"$T" -c"$C" -d"${DUR}s" --latency http://127.0.0.1:8081/work > "$tmp/admin" 2>&1 &
ADMIN_PID=$!

wait $USER_PID $ADMIN_PID

echo "===== user (8080) =====";  cat "$tmp/user"
echo "===== admin (8081) ====="; cat "$tmp/admin"

user_rps=$(awk  '/Requests\/sec/ {print $2}' "$tmp/user")
admin_rps=$(awk '/Requests\/sec/ {print $2}' "$tmp/admin")
echo
printf "user  rps = %s\n" "$user_rps"
printf "admin rps = %s\n" "$admin_rps"
printf "admin:user = %s\n" "$(awk "BEGIN{printf \"%.2f\", $admin_rps/$user_rps}")"

rm -rf "$tmp"
