#!/bin/sh
# Prepare threaded sub-cgroups under this service's own cgroup.
# Must be invoked from systemd with Delegate=yes so this process is already
# running inside /sys/fs/cgroup/system.slice/app.service.
set -eu

BASE=/sys/fs/cgroup/system.slice/app.service

mkdir -p "$BASE/user-pool" "$BASE/admin-pool"

# Flip children to threaded (parent becomes "domain threaded" implicitly).
echo threaded > "$BASE/user-pool/cgroup.type"
echo threaded > "$BASE/admin-pool/cgroup.type"

# Delegate cpu to children.
echo "+cpu" > "$BASE/cgroup.subtree_control"

# Proportional weights (default 100, range 1..10000).
echo 300 > "$BASE/user-pool/cpu.weight"
echo 700 > "$BASE/admin-pool/cpu.weight"
