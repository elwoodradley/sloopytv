# SloopyTV: working notes for Claude

## What this is
Android TV / Fire TV IPTV player (Xtream Codes first, M3U second). Kotlin, Compose for TV,
Media3. `core/` is pure Kotlin and must stay free of Android imports so it can move to desktop.

## Build and test
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :core:test :app:assembleDebug
```
Emulator AVD `tube_tv`; `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
Debug build id `tv.sloopy.debug`, release `tv.sloopy`. Release signing config reads
`keystore/keystore.properties` (git-ignored; never commit, never print its contents).

Screenshots from the emulator: `adb exec-out screencap -p > shot.png`. The release build blocks
capture (`FLAG_SECURE`); use the debug build for visual checks. Drive the UI with
`adb shell input keyevent DPAD_LEFT` etc.

## Rules that matter here
- Never call `android.util.Log`; use `TLog`, which redacts and is silent in release.
- Any string that might contain a URL or credential goes through `Redact.apply` before it is shown or logged.
- Secrets only in `Vault`. Never in DataStore, Room, logs, or Compose state that outlives the screen.
- Network: only through `AndroidHttp` (OkHttp with scheme guard). No logging interceptors, ever.
- Parsers in `core/` must stay streaming and capped (`Limits`). Add a test for every new input shape.
- No bundled channels, playlists or provider URLs. The only built-in streams are the public test
  streams in `Demo.kt`.

## Design language (approved mockups on the canvas, round 1)
- Near-black ground, off-white text, one amber accent (`Tube.amber`) used only for live dots, the
  now-line and warnings. Sharp corners and 1px hairlines; never rounded pills or glow rings.
- Focus is shown as light: lifted surface plus a 1px white edge (`focusLight`, `ClickableSurfaceDefaults` in `Widgets.kt`).
- Type: Big Shoulders Display (titles, channel idents, caps), Instrument Sans (text), Geist Mono (numbers, times, labels).
- Layout numbers are mockup px / 1.333: `TubeRoot` overrides `LocalDensity` so 1dp = 1/1440 of screen width.
- The tube power-on plays only at launch and on switch-off. Channel changes are a hard cut with the bare badge; no other motion.

## TV input conventions
- Text fields (`Field` in `ProviderScreen.kt`) are two-stage: the row takes D-pad focus, OK opens the keyboard.
  The TV keyboard is a floating window that eats the Back that closes it and reports no IME inset.
- Screens that need D-pad keys at the root must own focus (`LiveScreen` retries `requestFocus`).

## Voice
Five touches of copy, no more: HANG ON (buffering), HANG ON SLOOPY (reconnecting), "Nothing on."
(empty search), "Stay tuned" (quit button), TV-menu headings in Settings (PICTURE, LISTINGS,
PARENTAL). Everything else, especially errors and the provider form, stays dead straight.
No O-H-I-O or team references: the user turned that down.

## Releases
Use `scripts/release.sh <version> "<notes>"`; never hand-bump without it (versionCode is derived
from the version and must only rise). The updater (`update/Updater.kt`) keys on the asset name
`sloopytv.apk`. Debug builds read `files/update_api` for a local manifest; the updater test
(`adb reverse tcp:8099`, python http.server with latest.json + a debug APK) is in the session notes.

## Code server
`codes/src/index.js` (Cloudflare Worker, KV `CODES`, secret `ADMIN_PASSWORD`). Local run:
`cd codes && npx wrangler@4 dev --local --port 8787` with `.dev.vars` holding the password;
`adb reverse tcp:8787 tcp:8787` and set Settings › Login code server to `http://127.0.0.1:8787`.
The TV-side decrypt (`pair/CodeClient.decrypt`) has a unit test against a Worker-produced blob.
On the provider screen, Right from the Xtream/M3U buttons is wired to the code field with
`focusProperties`; geometric focus search did not find it.

## Pairing test without a phone
`adb forward tcp:8765 tcp:8765`, `curl http://127.0.0.1:8765/` for the salt, read the code
from `adb shell uiautomator dump`, then run tweetnacl under macOS `jsc` to derive the key and
seal a payload, and POST it to `/pair`. The scratch script from the first build lives only in
the session; rebuild it from `PairPage.kt`'s JavaScript if needed.
