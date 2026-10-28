#!/system/bin/sh
# Verify the installed APK and the deployed engine come from the same build.
#   sh check_pair.sh            -> compare and print a verdict, exit 1 on mismatch
set -u
if [ ! -x /system/bin/app_process ]; then
  echo "check_pair.sh must run in the Android environment"
  exit 1
fi
D=/data/local/tmp/eta
APK=${1:-$(pm path io.github.mangi.etax | sed -n 's/^package://p')}
[ -f "$APK" ] || { echo "pair: no apk at ${APK:-?}"; exit 1; }

apk_engine=$(unzip -p "$APK" assets/vdrive.dex 2>/dev/null | sha256sum | cut -d' ' -f1)
apk_stamp=$(unzip -p "$APK" assets/vdrive.version 2>/dev/null | tr -d '\r\n')
live=$(sha256sum "$D/vdrive.dex" | cut -d' ' -f1)
status=$(sh "$D/vd.sh" status 2>/dev/null) || { echo 'PAIR FAIL: engine unavailable or unhealthy'; exit 1; }
running=$(printf '%s' "$status" | sed -n 's/.*"build":"\([^"]*\)".*/\1/p')

echo "apk engine dex : ${apk_engine:-unknown}"
echo "apk stamp      : ${apk_stamp:-unknown}"
echo "deployed dex   : $live"
echo "running build  : ${running:-not running}"

bad=0
[ "$apk_engine" = "$live" ] || { echo "PAIR MISMATCH: apk ships ${apk_engine}, deployed dex is $live"; bad=1; }
[ -z "$running" ] || [ "$running" = "$apk_stamp" ] \
  || { echo "PAIR MISMATCH: apk expects ${apk_stamp}, running engine is $running"; bad=1; }
if [ "$bad" = "0" ]; then
  echo "PAIR OK: apk and engine both belong to build ${apk_stamp:-?}"
  exit 0
fi
echo "fix: sh $D/install_vdrive.sh $D/build2/... (deploy the engine that belongs to this apk)"
exit 1
