# OmarchyTV 2.10-beta

APK: `omarchytv-release-2.10.apk` · versionCode 16 · Android TV 7+ · ~1.5 MB
SHA-256 `4cd21301efd28d06686be296762f1e884371c06950886817745cf1f07f45abef` · signing cert SHA-256 `90aa68d9f45e623eadfd349ab1eb66042356f95ff6347a4568c3f3a78f8ea33d`

## Novità / New
- 5 workspace reali (home, tv, app, pc, sistema), tasti 1–5 e CH± · 5 real workspaces, keys 1–5 and CH±.
- Tile TV fissa in prima posizione; ingressi HDMI con nome CEC nel workspace 2 · fixed TV tile; HDMI/CEC inputs on ws 2.
- Tutti i 23 temi Omarchy con lo sfondo wordmark Omarchy, sfondo personale e "Sfondo successivo"; sfondi di terzi senza licenza verificata esclusi · all themes; third-party wallpapers excluded until licensed.
- Ogni riquadro della home si spegne davvero; "Disponi riquadri" (griglia 12×8, senza sovrapposizioni) · panel toggles + arrange.
- OK lungo → Sposta / Nascondi; ordine sincronizzato home/griglia · long OK → move/hide, order in sync.
- Titolo della riga = app in focus · row title follows focus.
- Logo OMARCHY TV animato all'apertura (disattivabile) · animated logo (can be turned off).
- Tile testuali stile terminale (opzione, spenta) · terminal-style text tiles (option, off).
- Salvaschermo: 37 effetti tte.js in processo separato, attesa 150 s (1/2,5/5/10 min/mai) · screensaver in its own process.
- Cast spento di default, solo tipi ammessi con controllo dei primi byte · cast off by default, magic-byte allowlist.

## Correzioni / Fixes
- Riquadri sovrapposti (vecchio strato orologio/crediti) eliminati · overlapping old panels removed.
- Salvaschermo nero: logo nativo subito, verifica dello schermo, layer software, poi ripiego nativo · never black.
- Salvaschermo di sistema riconosciuto da Android 9 · dream now found by DreamManager.
- Copia di riserva degli effetti funzionante (37 effetti) · fallback effects bundle fixed.
- Menu OK lungo non più fuori schermo; crash alla riapertura di un menu · tile menu off-screen; menu reopen crash.
- Meteo: la città scelta a mano ha sempre priorità · manual city always wins.
- Ingranaggio rimosso, icona Bluetooth apre le impostazioni, riga tema/rete/up non più tagliata, X web app.

## Note
Beta: provata su emulatore Android TV 9 (software). Test su Sony KD-55XE9005 in corso. Segnala la tua TV con il
modello "TV report". · Tested on the emulator; real-TV reports welcome.

## 2.10.1 (versionCode 17)
- Temi personali dal PC: `PUT/GET/DELETE /v1/themes/<name>`, `POST /v1/themes/<name>/select`; ZIP verificato (nomi sicuri,
  magic bytes JPEG/PNG/WebP, max 32 immagini, 50 MB totali); compaiono come ★ nel menu Temi.
- "Schermo PC" live: lettore HTTP LAN dedicato (targetSdk 34 bloccava l'http in chiaro: prima lo stream non poteva partire).
- APK: `omarchytv-release-2.10.1.apk` SHA-256 `68e861248e65a74aeceee88683fdb24a15bde900ccf91970969dac2d207608b2`, cert `90aa68d9…a33d`.

## 2.10.2 (versionCode 18)
- Nuovo sfondo predefinito (Tokyo Night): **"Liftoff Sunset"** di Tom Ballard ([@tcballard](https://x.com/tcballard)),
  da https://github.com/tcballard/omarchy-theme-tokyo-liftoff; foto originale SpaceX (pubblico dominio secondo l'autore).
  Incluso con il permesso dell'autore (X, 22 ottobre 2026). WebP 3840x2160. Grazie Tom!
- APK: `omarchytv-release-2.10.2.apk` SHA-256 `8b4458ccb1d39fbd254b72521e62e5ea3b82561ed45e36850037afc15395f381`, cert `90aa68d9…a33d`.

## 2.11.0 (versionCode 19)
- See [CHANGELOG.md](../../CHANGELOG.md). APK: `omarchytv-release-2.11.0.apk` SHA-256 `33248d55f359905dd6068bfeca16e76a02da54150a119aae3ecfa0938ddc5111`, cert `90aa68d9…a33d`.

## 2.12.0-beta (versionCode 20)
- 26 MIT-only themes (`jade-horse`, `year-of-the-horse`, `hermarchy` + Omarchy wordmark wallpapers for `last-horizon`, `solitude`, `starship`), scrollable `AsciiMenu`, `Sync PC wallpaper` toggle (off by default), Sharp AQUOS & multi-brand Live TV detection, fixed app hiding/unhiding + Workspace 5 `Hidden apps` shortcut, Screensaver effect speed (`slow`/`normal`/`fast`), built-in **Grok Bot & AI** WebApps (`Grok Bot`, `Grok (X)`, `ChatGPT`, `Claude`, `Hermes`), and native MCP server (`/mcp` on TV and `omarchy-tv mcp` on PC).
- See [CHANGELOG.md](../../CHANGELOG.md). APK: `OmarchyTV-2.12.0-beta.apk` SHA-256 `f1426db057fdc958fe562d1afd57a710b896e2f63d4fa6c2ec6c8ceefdfee11e`, cert `90aa68d9…a33d`.
