#!/system/bin/sh
# Unpacks the Termux bootstrap the way the app's own installer does (TermuxInstaller.java),
# without opening the app on the TV. Run as the Termux uid: run-as com.termux sh bootstrap.sh <dir>,
# where <dir> is bootstrap-arm.zip already extracted and readable by that uid (the boxes have no
# unzip of their own: the app extracts the zip in Java, make on the computer).
# Does nothing once $T/usr exists.

SRC=$1
T=/data/data/com.termux/files
[ -d "$T/usr" ] && exit 0

rm -rf "$T/usr-staging"
mkdir -p "$T/home" && cp -R "$SRC" "$T/usr-staging" && cd "$T/usr-staging" || exit 1
# Each line is "target←link"; the zip itself carries no symlinks.
while IFS= read -r l; do
    p="${l#*←}"
    mkdir -p "$(dirname "$p")" && ln -s "${l%%←*}" "$p" || exit 1
done < SYMLINKS.txt
rm SYMLINKS.txt
find bin libexec lib/apt/methods -type f -exec chmod 700 {} + && chmod 700 lib/apt/apt-helper || exit 1
cd "$T" && mv usr-staging usr || exit 1

# The second stage runs once (it leaves a lock); like the app's installer, wipe the prefix on
# failure so the next run starts over instead of skipping a half-configured usr.
/system/bin/env -i HOME="$T/home" PREFIX="$T/usr" TMPDIR="$T/usr/tmp" PATH="$T/usr/bin:/system/bin" \
    LANG=en_US.UTF-8 ANDROID_ROOT=/system ANDROID_DATA=/data \
    "$T/usr/bin/bash" "$T/usr/etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh" \
    || { r=$?; rm -rf "$T/usr"; exit $r; }
