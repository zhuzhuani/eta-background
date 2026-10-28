#!/system/bin/sh
# Authenticated, identity-checked lifecycle for ETA's resident engine only.
set -eu
D=/data/local/tmp/eta
PORT=8733
DEX=$D/vdrive.dex
LOG=$D/vdrive.log
umask 077
mkdir -p "$D"
chmod 700 "$D"
if [ "${ETA_ENGINE_LOCKED:-0}" != 1 ]; then
  exec 9>"$D/engine.lock"
  flock -n 9 || { echo 'engine deployment busy'; exit 75; }
fi

is_engine_pid() {
  directory=/proc/$1
  [ -r "$directory/cmdline" ] || return 1
  [ "$(awk '/^Uid:/{print $2}' "$directory/status" 2>/dev/null)" = 0 ] || return 1
  command=$(tr '\000' '\n' < "$directory/cmdline" 2>/dev/null) || return 1
  if printf '%s\n' "$command" | grep -qx 'eta-vdrive-8733'; then return 0; fi
  printf '%s\n' "$command" | grep -qx 'VDrive' || return 1
  if printf '%s\n' "$command" | grep -qx -- "--tcp=$PORT"; then return 0; fi
  # Some app_process versions overwrite argv. Attribute the legacy process by the
  # actual listening socket, never by the class name alone (test engines may coexist).
  inode=$(awk '$2 == "0100007F:221D" && $4 == "0A" {print $10}' /proc/net/tcp)
  [ -n "$inode" ] || return 1
  for descriptor in "$directory"/fd/*; do
    [ "$(readlink "$descriptor" 2>/dev/null)" = "socket:[$inode]" ] && return 0
  done
  return 1
}

engine_pids() {
  for directory in /proc/[0-9]*; do
    [ -r "$directory/comm" ] || continue
    IFS= read -r comm < "$directory/comm" || continue
    case "$comm" in eta-vdrive-*|VDrive|app_process*) ;; *) continue ;; esac
    process=${directory##*/}
    if is_engine_pid "$process"; then printf '%s\n' "$process"; fi
  done
}

identity() {
  # Field 22, after the possibly-space-containing comm field.
  sed 's/.*) //' "/proc/$1/stat" 2>/dev/null | awk '{print $20}'
}

rpc() {
  token=$(cat "$D/engine.token" 2>/dev/null || true)
  printf '{"cmd":"%s","auth":"%s"}\n' "$1" "$token" | nc -w 2 127.0.0.1 "$PORT" 2>/dev/null | head -1
}

stop_engine() {
  records=''
  for process in $(engine_pids); do
    started=$(identity "$process")
    [ -n "$started" ] && records="$records $process:$started"
  done
  [ -n "$records" ] || return 0
  rpc quit >/dev/null || true
  for record in $records; do
    process=${record%:*}; started=${record#*:}
    count=0
    while [ "$(identity "$process")" = "$started" ] && [ "$count" -lt 15 ]; do
      sleep 0.2
      count=$((count + 1))
    done
    if [ "$(identity "$process")" = "$started" ]; then kill -9 "$process" 2>/dev/null || true; fi
    count=0
    while [ "$(identity "$process")" = "$started" ] && [ "$count" -lt 15 ]; do
      sleep 0.2
      count=$((count + 1))
    done
    [ "$(identity "$process")" != "$started" ] || { echo 'old engine has not exited'; return 1; }
  done
  [ -z "$(engine_pids)" ] || { echo 'another engine appeared during stop'; return 1; }
}

healthy_status() {
  response=$(rpc status) || return 1
  printf '%s' "$response" | grep -q '"healthy":true' || return 1
  printf '%s' "$response" | grep -q '"ok":true' || return 1
  wanted=$(cat "$D/vdrive.version")
  got=$(printf '%s' "$response" | sed -n 's/.*"build":"\([^"]*\)".*/\1/p')
  [ "$got" = "$wanted" ] || return 1
  got_hash=$(printf '%s' "$response" | sed -n 's/.*"dexSha256":"\([^" ]*\)".*/\1/p')
  [ "$got_hash" = "$(sha256sum "$DEX" | cut -d' ' -f1)" ] || return 1
  process=$(printf '%s' "$response" | sed -n 's/.*"pid":\([0-9]*\).*/\1/p')
  [ -n "$process" ] || return 1
  is_engine_pid "$process" || return 1
  printf '%s\n' "$response"
}

case "${1:-}" in
  stop) stop_engine; echo 'vdrive stopped'; exit 0 ;;
  status) healthy_status; exit $? ;;
esac

[ -s "$DEX" ] && [ -s "$D/engine.token" ] && [ -s "$D/vdrive.version" ] || { echo 'engine payload incomplete'; exit 1; }
stop_engine
for number in 2 1; do
  [ ! -f "$LOG.$number" ] || mv -f "$LOG.$number" "$LOG.$((number + 1))"
done
[ ! -f "$LOG" ] || mv -f "$LOG" "$LOG.1"
# The engine must not inherit the installer's lock descriptor.
setsid nohup env CLASSPATH="$DEX" /system/bin/app_process /system/bin --nice-name=eta-vdrive-8733 VDrive --tcp="$PORT" --no-mcp >"$LOG" 2>&1 9>&- &
started_pid=$!
started_at=$(identity "$started_pid")
count=0
while [ "$count" -lt 35 ]; do
  if response=$(healthy_status); then
    got=$(printf '%s' "$response" | sed -n 's/.*"pid":\([0-9]*\).*/\1/p')
    if [ "$got" = "$started_pid" ] && [ "$(identity "$started_pid")" = "$started_at" ]; then
      printf '%s %s\n' "$started_pid" "$started_at" > "$D/vdrive.pid"
      echo "vdrive ready pid=$got build=$(cat "$D/vdrive.version")"
      exit 0
    fi
  fi
  [ "$(identity "$started_pid")" = "$started_at" ] || break
  sleep 0.2
  count=$((count + 1))
done
stop_engine || true
echo 'engine startup verification failed'
exit 1
