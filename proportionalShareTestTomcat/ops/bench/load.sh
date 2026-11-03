#!/bin/sh
# Blast /work in parallel against both ports and print simple throughput.
# Usage: ./load.sh <seconds> <concurrency-per-port>
set -eu

DUR=${1:-30}
C=${2:-50}
USER_URL=http://127.0.0.1:8080/work
ADMIN_URL=http://127.0.0.1:8081/work

worker() {
  URL=$1 OUT=$2
  end=$(( $(date +%s) + DUR ))
  n=0
  while [ "$(date +%s)" -lt "$end" ]; do
    curl -s "$URL" > /dev/null
    n=$((n+1))
  done
  echo "$n" > "$OUT"
}

tmp=$(mktemp -d)
i=0
while [ "$i" -lt "$C" ]; do
  worker "$USER_URL"  "$tmp/u$i" &
  worker "$ADMIN_URL" "$tmp/a$i" &
  i=$((i+1))
done
wait

user_total=0
admin_total=0
for f in "$tmp"/u*; do user_total=$((user_total + $(cat "$f"))); done
for f in "$tmp"/a*; do admin_total=$((admin_total + $(cat "$f"))); done
rm -rf "$tmp"

printf "duration=%ss concurrency=%s per side\n" "$DUR" "$C"
printf "user  (8080) total=%d  rps=%s\n" "$user_total"  "$(( user_total / DUR ))"
printf "admin (8081) total=%d  rps=%s\n" "$admin_total" "$(( admin_total / DUR ))"
printf "admin:user ratio = %s\n" "$(awk "BEGIN{printf \"%.2f\", $admin_total/$user_total}")"
