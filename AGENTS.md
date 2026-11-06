# Agent Instructions — popcornclaw

An LLM agent (PicoClaw) that lives on an Android TV box itself and runs as root: it has the box's
shell, installs its own tools from Termux, fixes the box's small problems, finds and starts movies
through the tv_mcp server, wakes the TV over HDMI-CEC, talks through a Telegram bot (voice too) and
lives in our fork of the OmarchyTV launcher, which is the box's home screen. Made for Android 9 TV
boxes with root and a 32-bit userspace (tested on Beelink GT-King and GT-King Pro).

What it does and where things live on the box — `README.md`. Verified facts, the owner's
decisions, rejected variants and pitfalls — `docs/SPEC.md`. The maintainer's own boxes live in
`private/` (gitignored): his configs, `box.mk` with his make settings and keys, and `README.md` with
where his notes are; read it first when it is there.

## Structure

- `picoclaw/` — Dockerfile that builds PicoClaw v0.3.1 for linux/armv7 with our patches:
  `tool-status.patch` (each step's status in Telegram), `asr-proxy.patch` (voice through the
  model's proxy), `image-shrink.patch` (images go to the model as JPEG of at most 1280 px),
  `tool-feedback.patch` (steps as «status (`tool`)», sent silently, no animation), `keep-logs.patch`
  (every session message also goes to `sessions/archive/`, provider error bodies kept whole),
  `shared-session.patch` (the TV and the Telegram DM or group linked by `session.identity_links` are one dialog:
  a TV (pico) message becomes a message of that chat, every text the chat gets is mirrored to the TV (message
  tool included, files not), each side sees the owner's messages from the other, the TV gets a `turn_end`
  frame after each turn), `start-to-agent.patch` (`/start` goes to the agent instead of a fixed English line), `pico-audio.patch` (the
  pico channel takes a voice note from the TV remote, marks it `[voice]` and transcribes it like a Telegram one)
- `launcher/app/src/main/assets/defaults/` — the `config.json` (PicoClaw config) and `AGENT.md` (facts
  about the box for the agent itself) a fresh box gets; keys go to `.security.yml` on the box, mode 600
- `launcher/app/src/main/assets/termux/bootstrap.sh` — unpacks Termux's bootstrap without opening the app;
  run by the app's provisioning and by `make termux`
- `launcher/` — the app `xyz.vvzvlad.popcornclaw`: fork of the OmarchyTV launcher
  (https://gitlab.com/piermark/omarchytv, imported at upstream 244528f in commit 222116c), built by
  `launcher/Dockerfile` with Gradle on the docker daemon, PicoClaw inside as
  `lib/armeabi-v7a/libpicoclaw.so`. Our code lives in `launcher/app/src/main/java/xyz/vvzvlad/popcornclaw/`;
  upstream files get only hook lines, so upstream updates can be diffed in (`AgentService` keeps the
  gateway running as root through SuperSU and starts the two LAN servers below; `PicoLink` is the app's
  WebSocket to the gateway's pico channel, open from the service's start; `PicoTurn` turns its frames
  into the assistant screen's events; `ScreenStreamServer` serves the box screen as MJPEG with mouse/
  keyboard back (port 8080, LAN); `ControlServer` is the owner's control surface on port 8081 — an MCP
  server over the LAN with the box's own tools (`exec`/`status`/`player`/`config_get`/`config_set`/
  `ask_agent`) plus a settings web form; both servers take the PIN shown on the TV (`Pin`: cookie,
  `?pin=`, `Authorization: Bearer <PIN>` for `/mcp`))

## Build and install

```bash
make install TV=<box address>:5555  # build the app and install it; the app sets the box up itself
make app                            # after a launcher change
make app restart                    # after a PicoClaw patch change (restart ends a turn in progress)
make logs
make ask Q="..."                    # one-shot question to the agent, no Telegram
```

The release APK is signed with the key `KEY_CMD` gives (see the Makefile). With `private/box.mk`,
`make install` also pushes the maintainer's config, AGENT.md and keys. The docker CLI may point at a
remote daemon: no bind mounts, artefacts come back via `docker cp`.

## The live box

- The box is shared: other apps and services run there, and the agent itself acts on the screen
  when the owner writes to it.
- HOME pauses mpv in the background. The paused task comes back without reloading the file the
  way a launcher tap brings it: `am start -a android.intent.action.MAIN -c
  android.intent.category.LAUNCHER -n is.xyz.mpv/.MainActivity -f 0x10200000`. What is on screen:
  `dumpsys activity activities | grep mResumedActivity`.
- `make restart` kills a turn in progress.

## Conventions

- Code comments, commit messages (subject and body) — English. README and agent texts — Russian.
- Routine through `make` targets.
- Default branch `main`.
- The repository is public: no maintainer infrastructure in tracked files or commit messages — his
  addresses, host names, Telegram IDs and bots, password-manager item names, his boxes. Those live in
  `private/` and in his wiki.
