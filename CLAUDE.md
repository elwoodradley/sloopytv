# Tube: working notes for Claude

## What this is
Android TV / Fire TV IPTV player (Xtream Codes first, M3U second). Kotlin, Compose for TV,
Media3. `core/` is pure Kotlin and must stay free of Android imports so it can move to desktop.

## Build and test
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :core:test :app:assembleDebug
```
Emulator AVD `tube_tv`; `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
Debug build id `tv.tube.player.debug`, release `tv.tube.player`. Release signing config reads
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
