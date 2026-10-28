#!/system/bin/sh
# One transaction: validate -> stop/verify -> atomic replace -> start/verify -> rollback.
set -eu
[ -x /system/bin/app_process ] || { echo 'Android environment required'; exit 1; }
[ "$(id -u)" = 0 ] || { echo ETA_NO_ROOT; exit 97; }
umask 077
D=/data/local/tmp/eta
SOURCE=${1:-}
[ -d "$SOURCE" ] || { echo 'usage: install_vdrive.sh <staged payload directory>'; exit 2; }
mkdir -p "$D"
chmod 700 "$D"
exec 9>"$D/engine.lock"
flock -n 9 || { echo 'engine deployment busy'; exit 75; }
export ETA_ENGINE_LOCKED=1
WORK=$(mktemp -d "$D/.install.XXXXXX")
BACKUP=$WORK/previous
mkdir -p "$BACKUP"
trap 'rm -rf "$WORK"' EXIT
FILES='vdrive.dex vdrive.version vd.sh install_vdrive.sh check_pair.sh engine.token'
changed=0
for file in $FILES; do
  [ -s "$SOURCE/$file" ] || { echo "missing staged $file"; exit 3; }
  cp "$SOURCE/$file" "$WORK/$file"
  chmod 600 "$WORK/$file"
  [ "$(sha256sum "$SOURCE/$file" | cut -d' ' -f1)" = "$(sha256sum "$WORK/$file" | cut -d' ' -f1)" ] || exit 4
  if [ -f "$D/$file" ]; then
    cp "$D/$file" "$BACKUP/$file"
    cmp -s "$WORK/$file" "$D/$file" || changed=1
  else
    changed=1
  fi
done
if [ "$changed" = 0 ] && sh "$WORK/vd.sh" status >/dev/null 2>&1; then echo 'engine already verified'; exit 0; fi
# Use the new controller to stop legacy engines too. Never copy over a mapped live dex.
sh "$WORK/vd.sh" stop || exit 5
for file in $FILES; do mv -f "$WORK/$file" "$D/$file"; done
if sh "$D/vd.sh"; then
  echo 'engine install verified'
  exit 0
fi
echo 'new engine failed; stopping before rollback'
sh "$D/vd.sh" stop || { echo 'rollback refused: new process still alive'; exit 6; }
for file in $FILES; do
  if [ -f "$BACKUP/$file" ]; then mv -f "$BACKUP/$file" "$D/$file"; else rm -f "$D/$file"; fi
done
# Rollback restores bytes only. A legacy controller is not trusted to verify startup;
# leave it stopped and let the next bounded recovery try the bundled version.
echo 'previous payload restored; engine stopped'
exit 7
