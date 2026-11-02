#!/usr/bin/env bash
# Build the OmarchyTV launcher APK and install it on the TV as the default home screen.
# Usage: TV_HOST=192.168.1.50 launcher/build-and-install.sh
#        STOCK_LAUNCHER=com.google.android.tvlauncher (default; see README for Sony notes)
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
PKG=com.omarchytv.home
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.tvlauncher}"

# JDK 17: $JAVA_HOME, else macOS java_home / Homebrew, else common Linux paths (Arch/Omarchy: jdk17-openjdk).
first_dir() { for d in "$@"; do [ -n "$d" ] && [ -x "$d/bin/java" ] && { echo "$d"; return; }; done; }
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(first_dir "$(/usr/libexec/java_home -v 17 2>/dev/null || true)" /opt/homebrew/opt/openjdk@17 \
    /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/temurin-17-jdk)"
fi
export JAVA_HOME="${JAVA_HOME:?no JDK 17 found: set JAVA_HOME}"
# Android SDK: $ANDROID_HOME, else Android Studio defaults (macOS, Linux), else common package paths.
if [ -z "${ANDROID_HOME:-}" ]; then
  for d in ~/Library/Android/sdk ~/Android/Sdk /opt/android-sdk /opt/homebrew/share/android-commandlinetools; do
    [ -d "$d" ] && { ANDROID_HOME="$d"; break; }
  done
fi
export ANDROID_HOME="${ANDROID_HOME:?no Android SDK found: set ANDROID_HOME}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug -q --console=plain

PY=../tools/venv/bin/python
[ -x "$PY" ] || PY=../tools/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
APK=app/build/outputs/apk/debug/app-debug.apk
"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/omarchytv.apk
# One command per shell call (--batch): each pm runs in its own `shell:` so one failure can't hide another.
out=$(printf '%s\n' "pm install -r /data/local/tmp/omarchytv.apk" "rm -f /data/local/tmp/omarchytv.apk" | "$PY" -I ../tools/tv.py --batch)
echo "$out"
case "$out" in *Success*) ;; *) echo "pm install failed; the stock launcher was left untouched" >&2; exit 1 ;; esac

# Sony BRAVIA (Android 9): the stock launcher's HOME filter has priority 2, so set-home-activity prints
# "Success" while Home still opens com.google.android.tvlauncher. Fixed order (from cleanyourtv):
#   1. start our launcher, 2. disable the stock one (asked first), 3. set the home preference LAST,
#   4. verify with resolve-activity, 5. re-enable the stock launcher if Home is not ours.
printf '%s\n' \
  "pm grant $PKG android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted" \
  "am start -W -n $PKG/.MainActivity | grep -E '^(Status|Error)'" \
  | "$PY" -I ../tools/tv.py --batch
DISABLE_STOCK="echo stock launcher left alone"
if [ "$STOCK_LAUNCHER" != none ]; then
  ans="${CONFIRM_DISABLE_STOCK:-}"
  if [ -z "$ans" ]; then
    printf 'Disable %s (pm disable-user, undo: tools/restore.sh)? On Sony this is needed for Home to open OmarchyTV. [y/N] ' "$STOCK_LAUNCHER"
    read -r ans || ans=n
  fi
  case "$ans" in y|Y|yes|s|S|si) DISABLE_STOCK="pm disable-user --user 0 $STOCK_LAUNCHER" ;; esac
fi
printf '%s\n' "$DISABLE_STOCK" "cmd package set-home-activity --user 0 $PKG/.MainActivity" \
  | "$PY" -I ../tools/tv.py --batch
HOME_NOW=$("$PY" -I ../tools/tv.py 'cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN' | tail -n 1 | tr -d '\r')
case "$HOME_NOW" in
  $PKG/*) echo "Home now resolves to $HOME_NOW" ;;
  *)
    [ "$STOCK_LAUNCHER" = none ] || "$PY" -I ../tools/tv.py "pm enable --user 0 $STOCK_LAUNCHER" >/dev/null || true
    echo "ERROR: Home resolves to ${HOME_NOW:-nothing}, not OmarchyTV; $STOCK_LAUNCHER was re-enabled (TV back to stock)." >&2
    exit 1 ;;
esac
echo "Installed. Press Home on the remote to see it. Next: tools/reboot-check.sh"
