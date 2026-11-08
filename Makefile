# Builds PopcornClaw (the OmarchyTV launcher fork with the PicoClaw agent inside) and installs it onto an Android
# TV box over network adb: make install TV=<box address>:5555. `make` or `make help` lists the targets. On its
# first start the app sets the box up by itself: Termux, its config, the home screen, permissions.
# private/box.mk, gitignored, is read at the end when present: a maintainer's own boxes, configs and keys.

DIR := /data/local/popcornclaw
APP := xyz.vvzvlad.popcornclaw
SERVICE := $(APP)/xyz.vvzvlad.popcornclaw.AgentService
# Recursive: TV may come from private/box.mk, which is read last.
ADB = adb -s $(TV)
# Termux is the agent's package manager. The GitHub build is debuggable, which is what lets
# `run-as com.termux` run apt as the app's uid from the root shell; F-Droid's build is not.
TERMUX_APK_URL := https://github.com/termux/termux-app/releases/download/v0.118.3/termux-app_v0.118.3+github-debug_armeabi-v7a.apk
TERMUX_APK_SHA256 := 89416397b70f9ff67a0044e8abe6ef82487cd48fcf543e2d23e02688cc541cf0
TERMUX_BOOTSTRAP_URL := https://github.com/termux/termux-packages/releases/download/bootstrap-2026.11.01-r1%2Bapt.android-7/bootstrap-arm.zip
TERMUX_BOOTSTRAP_SHA256 := 99660c5a95733765c9967309a0c91c67335e9804b9e2027cfba71926fa4a3140
# TorrServer and a static ffprobe ride inside the app as native libs (like libpicoclaw.so), so installing the
# app is enough: AgentService runs TorrServer on :8083 and puts ffprobe on its PATH, no separate TorrServe app.
# Both are fetched at build time (immutable URLs + sha256), never committed. TorrServer's android build uses
# Android's own linker; a statically linked GNU/Linux ffprobe needs no shared libraries, so it runs too.
# One set per ABI, named by its build/ directory: arm (armeabi-v7a) and arm64 (arm64-v8a).
TORRSERVER_URL_arm := https://github.com/YouROK/TorrServer/releases/download/MatriX.149/TorrServer-android-arm7
TORRSERVER_SHA256_arm := 08bb424083de6a56a2f9a6b798d191a8da30407d78758a7533a4f6a24ea47cee
TORRSERVER_URL_arm64 := https://github.com/YouROK/TorrServer/releases/download/MatriX.149/TorrServer-android-arm64
TORRSERVER_SHA256_arm64 := f3486100192cd49776465348c73b34e2c4944abb72d89898c531a517c4903a2f
FFPROBE_URL_arm := https://johnvansickle.com/ffmpeg/old-releases/ffmpeg-6.0.1-armhf-static.tar.xz
FFPROBE_SHA256_arm := 241cbf509222420033486ea82a7cf3cf4fb534437fa00d79df8abebd0184d7e6
FFPROBE_MEMBER_arm := ffmpeg-6.0.1-armhf-static/ffprobe
FFPROBE_URL_arm64 := https://johnvansickle.com/ffmpeg/old-releases/ffmpeg-6.0.1-arm64-static.tar.xz
FFPROBE_SHA256_arm64 := 7dbd8e2f47bd83de591b9d6ea70e67d32d9aa97e7d47ae402b60c2fe3fd4d0ab
FFPROBE_MEMBER_arm64 := ffmpeg-6.0.1-arm64-static/ffprobe
# Same environment as the app's AgentService gives the gateway.
ENV := HOME=$(DIR) PICOCLAW_HOME=$(DIR) PICOCLAW_CONFIG=$(DIR)/config.json SSL_CERT_DIR=/system/etc/security/cacerts \
	PATH=$$PATH:/data/data/com.termux/files/usr/bin PYTHONDONTWRITEBYTECODE=1
SHELL := /bin/bash

.PHONY: help install connect app termux restart logs ask

help:
	@grep -hE '^[a-z]+:.*## ' $(MAKEFILE_LIST) | sed 's/:.*## /\t/'

install: app ## Build the app (launcher with the agent) and install it onto the box TV=<address>:5555; it sets the box up itself

connect:
	$(if $(TV),,$(error TV is not set: make <target> TV=<box address>:5555))
	adb connect $(TV) >/dev/null

app termux restart logs ask: connect

# Patched PicoClaw (picoclaw/Dockerfile) built on the docker daemon, once per ABI: the build/ directory's name
# is the GOARCH (arm or arm64). Only the binary comes back.
build/arm/picoclaw build/arm64/picoclaw: build/%/picoclaw: picoclaw/Dockerfile picoclaw/tool-status.patch picoclaw/asr-proxy.patch \
	picoclaw/image-shrink.patch picoclaw/tool-feedback.patch picoclaw/keep-logs.patch \
	picoclaw/shared-session.patch picoclaw/pico-audio.patch picoclaw/start-to-agent.patch
	docker build --build-arg GOARCH=$* -t popcornclaw-picoclaw-$* picoclaw
	mkdir -p $(@D)
	id=$$(docker create popcornclaw-picoclaw-$* /picoclaw) && { docker cp $$id:/picoclaw $@; r=$$?; docker rm $$id >/dev/null; exit $$r; }
	touch $@

# The app (launcher/Dockerfile: the OmarchyTV launcher fork) built on the docker daemon with the PicoClaw,
# TorrServer and ffprobe binaries inside as lib/{armeabi-v7a,arm64-v8a}/lib{picoclaw,torrserver,ffprobe}.so: an
# unsigned release APK. It is signed with the key KEY_CMD gives: run with a directory as its last argument, KEY_CMD writes
# release.p12 (PKCS12, key alias popcornclaw) and pass (its store and key password) there. apksigner signs in a
# throwaway container of the build image, so the key never lands in an image layer; only the signed APK comes back.
UNSIGNED_APK := app/build/outputs/apk/release/app-release-unsigned.apk
build/popcornclaw.apk: build/arm/picoclaw build/arm/libtorrserver.so build/arm/libffprobe.so \
	build/arm64/picoclaw build/arm64/libtorrserver.so build/arm64/libffprobe.so \
	$(shell find launcher -type f -not -path 'launcher/app/src/main/jniLibs/*')
	mkdir -p launcher/app/src/main/jniLibs/armeabi-v7a launcher/app/src/main/jniLibs/arm64-v8a
	cp build/arm/picoclaw launcher/app/src/main/jniLibs/armeabi-v7a/libpicoclaw.so
	cp build/arm/libtorrserver.so launcher/app/src/main/jniLibs/armeabi-v7a/libtorrserver.so
	cp build/arm/libffprobe.so launcher/app/src/main/jniLibs/armeabi-v7a/libffprobe.so
	cp build/arm64/picoclaw launcher/app/src/main/jniLibs/arm64-v8a/libpicoclaw.so
	cp build/arm64/libtorrserver.so launcher/app/src/main/jniLibs/arm64-v8a/libtorrserver.so
	cp build/arm64/libffprobe.so launcher/app/src/main/jniLibs/arm64-v8a/libffprobe.so
	$(if $(KEY_CMD),,$(error KEY_CMD is not set: a command that writes release.p12 and pass into the directory given last))
	docker build -t popcornclaw-app launcher
	k=$$(mktemp -d) && trap 'rm -rf "$$k"; [ -z "$$id" ] || docker rm -f "$$id" >/dev/null 2>&1' EXIT && \
	$(KEY_CMD) "$$k" && \
	chmod 755 "$$k" && chmod 644 "$$k/release.p12" "$$k/pass" && \
	id=$$(docker create popcornclaw-app sh -c '$$ANDROID_HOME/build-tools/34.0.0/apksigner sign --ks /tmp/k/release.p12 --ks-pass file:/tmp/k/pass --ks-key-alias popcornclaw --out /tmp/popcornclaw.apk $(UNSIGNED_APK)') && \
	{ docker cp "$$k" $$id:/tmp/k && docker start -a $$id && docker cp $$id:/tmp/popcornclaw.apk $@; r=$$?; docker rm $$id >/dev/null; exit $$r; }
	touch $@

build/termux.apk:
	mkdir -p build
	curl -fsSL -o $@.tmp '$(TERMUX_APK_URL)'
	echo "$(TERMUX_APK_SHA256)  $@.tmp" | shasum -a 256 -c -
	mv $@.tmp $@

build/bootstrap-arm.zip:
	mkdir -p build
	curl -fsSL -o $@.tmp '$(TERMUX_BOOTSTRAP_URL)'
	echo "$(TERMUX_BOOTSTRAP_SHA256)  $@.tmp" | shasum -a 256 -c -
	mv $@.tmp $@

# TorrServer's android build (Android linker); packaged as a native lib so install extracts it executable.
build/arm/libtorrserver.so build/arm64/libtorrserver.so: build/%/libtorrserver.so:
	mkdir -p $(@D)
	curl -fsSL -o $@.tmp '$(TORRSERVER_URL_$*)'
	echo "$(TORRSERVER_SHA256_$*)  $@.tmp" | shasum -a 256 -c -
	mv $@.tmp $@

# Only ffprobe is kept from the static ffmpeg tarball; TorrServer runs it to read a file's media info.
build/arm/libffprobe.so build/arm64/libffprobe.so: build/%/libffprobe.so:
	mkdir -p $(@D)
	curl -fsSL -o $(@D)/ffprobe.tar.xz '$(FFPROBE_URL_$*)'
	echo "$(FFPROBE_SHA256_$*)  $(@D)/ffprobe.tar.xz" | shasum -a 256 -c -
	tar -xJf $(@D)/ffprobe.tar.xz -C $(@D) --strip-components=1 '$(FFPROBE_MEMBER_$*)'
	mv $(@D)/ffprobe $@
	rm -f $(@D)/ffprobe.tar.xz

# The boxes have no unzip of their own: the zip is extracted here and the tree pushed (the app extracts it in Java).
build/termux-bootstrap: build/bootstrap-arm.zip
	rm -rf $@ $@.tmp
	unzip -q $< -d $@.tmp
	mv $@.tmp $@

termux: build/termux.apk build/termux-bootstrap ## Install the 32-bit (armeabi-v7a) Termux and unpack its bootstrap (skipped where done); on a 64-bit box the app installs Termux itself
	$(ADB) push build/termux.apk /data/local/tmp/termux.apk
	# A never-opened app sits in app standby, and Android rejects all its network traffic.
	$(ADB) shell 'pm install -r /data/local/tmp/termux.apk && dumpsys deviceidle whitelist +com.termux; r=$$?; rm -f /data/local/tmp/termux.apk; exit $$r'
	# The tree is thousands of files: it goes over only while the prefix is missing.
	$(ADB) shell '[ -d /data/data/com.termux/files/usr ]' || { \
	  $(ADB) shell 'rm -rf /data/local/tmp/termux-bootstrap' && \
	  $(ADB) push build/termux-bootstrap /data/local/tmp/termux-bootstrap && \
	  $(ADB) push launcher/app/src/main/assets/termux/bootstrap.sh /data/local/tmp/termux-bootstrap.sh && \
	  $(ADB) shell 'chmod -R 755 /data/local/tmp/termux-bootstrap && run-as com.termux sh /data/local/tmp/termux-bootstrap.sh /data/local/tmp/termux-bootstrap; \
	    r=$$?; rm -rf /data/local/tmp/termux-bootstrap /data/local/tmp/termux-bootstrap.sh; exit $$r'; }

# The app becomes the home screen and runs AgentService,
# which starts the gateway from the APK once the gateway's /health has failed three times in a row.
# An upgrade kills the running service, so it is started again explicitly.
app: build/popcornclaw.apk ## Build and install the app (launcher with the agent), make it the home screen, start its agent service
	$(ADB) shell 'mkdir -p $(DIR)/workspace $(DIR)/backups'
	$(ADB) push build/popcornclaw.apk /data/local/tmp/popcornclaw.apk
	$(ADB) shell 'pm install -r /data/local/tmp/popcornclaw.apk; r=$$?; rm -f /data/local/tmp/popcornclaw.apk; exit $$r'
	# Every install gets a new /data/app directory: point $(DIR)/picoclaw (used by `ask`) at the new binary,
	# from lib/arm64 or lib/arm, whichever ABI the package was installed with.
	# The running gateway keeps the old one until `make restart`.
	$(ADB) shell 'p=$$(pm path $(APP)) && l=$$(dirname "$${p#package:}")/lib && { [ -e $$l/arm64/libpicoclaw.so ] && a=arm64 || a=arm; } && ln -sf "$$l/$$a/libpicoclaw.so" $(DIR)/picoclaw'
	# Off the whitelist Android stops the service a minute after the launcher leaves the screen and refuses
	# to start it while the app is in the background ("app is in background uid null" from am startservice).
	$(ADB) shell 'dumpsys deviceidle whitelist +$(APP)'
	$(ADB) shell 'cmd package set-home-activity --user 0 $(APP)/com.omarchytv.home.MainActivity'
	# The stock OmarchyTV, still installed, opens itself over ours at every boot to ask for the home screen
	# back, and its own screensaver starts on that screen 150 s later: its boot check off (no stock app: no-op).
	$(ADB) shell 'pm disable com.omarchytv.home/.HomeCheck >/dev/null 2>&1; true'
	# The launcher's own screensaver (150 s idle on the home screen by default) off; a config already there is kept.
	$(ADB) shell 'f=/sdcard/Android/data/$(APP)/files/omarchytv.json; test -e $$f || { mkdir -p $${f%/*} && echo "{\"version\":1,\"screensaver\":{\"idleSec\":0}}" > $$f; }'
	# Re-point the ACTION_ASSIST preferred at VoiceAssistActivity (the phone remote's mic-press silent capture)
	# and the WEB_SEARCH one at VoiceSearchActivity (the Bluetooth remote's mic key), which `pm install -r` just
	# cleared. adb's shell is already uid 0, which passes SET_PREFERRED_APPLICATIONS; without this the phone
	# remote's mic lands in the three-way system chooser (Katniss / stock launcher / us), and the Bluetooth
	# remote's mic key in the WEB_SEARCH chooser.
	# Best-effort: the trailing `; true` keeps a failure here (another ROM, a transient reflection error) from
	# aborting the install before the service starts below; the system chooser stays a working fallback.
	$(ADB) shell 'p=$$(pm path $(APP) | head -n1); CLASSPATH=$${p#package:} app_process /system/bin xyz.vvzvlad.popcornclaw.AssistRole; true'
	# The recognition service checks the caller's RECORD_AUDIO: VoiceSearchActivity's SpeechRecognizer needs it.
	$(ADB) shell 'pm grant $(APP) android.permission.RECORD_AUDIO'
	# Replacing the app while its home screen is shown hands the screen to the stock OmarchyTV: bring ours back.
	$(ADB) shell 'dumpsys activity activities | grep mResumedActivity | grep -q "com.omarchytv.home/" && am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null; true'
	# The service pops up each command to the agent over every app, video included.
	$(ADB) shell 'appops set $(APP) SYSTEM_ALERT_WINDOW allow'
	$(ADB) shell 'am startservice -n $(SERVICE)'

# AgentService kills the running gateway (SIGTERM, up to 10 s, then SIGKILL) and starts a new one, so the
# restart is asynchronous: success is a live gateway with a pid other than the old one.
# "[p]icoclaw" keeps pgrep from matching the adb shell whose command line holds the pattern.
restart: ## (Re)start the gateway through the app's agent service
	$(ADB) shell 'old=$$(pgrep -f "[p]icoclaw gateway"); am startservice -a $(APP).RESTART -n $(SERVICE) >/dev/null; \
	  for i in $$(seq 1 30); do sleep 1; p=$$(pgrep -f "[p]icoclaw gateway") && [ "$$p" != "$$old" ] && { echo "running (pid $$p)"; exit 0; }; done; \
	  echo "not restarted (pid still $${old:-none})"; tail -20 $(DIR)/gateway.log; exit 1'

logs: ## Tail the gateway log
	$(ADB) shell 'tail -50 $(DIR)/gateway.log'

# The question goes through stdin so quotes and $ in it reach the agent untouched.
# make expands $ in a command-line variable before exporting it; keep the raw value.
override Q := $(value Q)
export Q
ask: ## One-shot question to the agent from the command line: make ask Q="..."
	printf '%s' "$$Q" | $(ADB) shell 'cd $(DIR) && $(ENV) ./picoclaw agent -m "$$(cat)"'

-include private/box.mk

# Fail before the long build when the box or the signing key is missing (read after private/box.mk, which may set them).
ifneq ($(filter install app termux restart logs ask,$(MAKECMDGOALS)),)
$(if $(TV),,$(error TV is not set: make <target> TV=<box address>:5555))
endif
ifneq ($(filter install app build/popcornclaw.apk,$(MAKECMDGOALS)),)
$(if $(KEY_CMD),,$(error KEY_CMD is not set: a command that writes release.p12 and pass into the directory given last))
endif
