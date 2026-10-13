# SloopyTV

An IPTV player for Android TV and Fire TV, built around Xtream Codes with M3U/XMLTV as a second
path. It ships with no channels and no providers. Credentials live in the device keystore, URLs
are scrubbed from every log and error, the guide parser is hardened against hostile XML, and
there is no analytics or telemetry of any kind.

## Layout

- `core/` pure Kotlin, no Android: Xtream API client, XMLTV and M3U parsers, credential redaction,
  byte caps. Unit tested. Reusable for a desktop build later.
- `app/` the Android TV app: Jetpack Compose for TV, Media3 ExoPlayer, Room cache, DataStore prefs.
  - `data/` vault (Android Keystore AES-GCM), provider store, OkHttp, catalogue repository, demo streams
  - `player/` one ExoPlayer for live and on demand
  - `ui/` theme and screens: intro (tube power-on), live, guide, channel list, library, details, VOD player, search, settings, provider setup
- `app/.../pair/` the phone sign-in: a tiny web server on the home network, a QR code, NaCl-encrypted form
- `keystore/` release signing key and its password file. Git-ignored. **Back this folder up**: an
  APK signed with a different key cannot update an installed copy.
- `dist/` built APKs for sharing. Git-ignored.

## Build

Needs JDK 17 and the Android SDK (platform 35, build-tools 35.0.0). On this Mac they are at
`/opt/homebrew/opt/openjdk@17` and `/opt/homebrew/share/android-commandlinetools`.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :core:test                 # parser and redaction tests
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease       # signed, minified; app/build/outputs/apk/release/app-release.apk
```

Debug builds install as `tv.sloopy.debug`, release as `tv.sloopy`, so both can live on a box.

## Run on the Mac (Android TV emulator)

An AVD named `tube_tv` (name predates the rename) (Android 14 TV, 1080p, arm64) is already created.

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
$ANDROID_HOME/emulator/emulator -avd tube_tv -gpu auto &
$ANDROID_HOME/platform-tools/adb wait-for-device
$ANDROID_HOME/platform-tools/adb install -r dist/sloopytv.apk
```

The emulator window takes keyboard input: arrow keys are the D-pad, Enter is OK, Escape is Back,
digits type channel numbers.

## Releases and self-update

`scripts/release.sh 0.1.2 "notes"` bumps the version, builds and signs, tags, pushes and
publishes a GitHub Release whose asset is always named `sloopytv.apk`. Installed copies check
`https://api.github.com/repos/elwoodradley/sloopytv/releases/latest` fifteen seconds after
launch and every six hours, show a one-line toast, and install from Settings › About after the
viewer confirms in the system installer. The downloaded APK must have the same package name
and signing certificate as the running app or it is refused before the installer ever opens.
Fixed download address for Downloader: `github.com/elwoodradley/sloopytv/releases/latest/download/sloopytv.apk`, or the AFTVnews short code **7839728**.

Friend-facing instructions, no computer needed: [INSTALL.md](INSTALL.md).

## Sideload on a Fire TV Stick or Android TV box

1. On the box: Settings → My Fire TV → Developer options → turn on **ADB debugging** and
   **Apps from unknown sources**. (On Android TV: Settings → Device → About → tap Build seven times,
   then Developer options → USB/network debugging.)
2. Find the box's IP under Settings → My Fire TV → About → Network.
3. From the Mac, on the same Wi-Fi:

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
$ANDROID_HOME/platform-tools/adb connect 192.168.1.50:5555
$ANDROID_HOME/platform-tools/adb install -r dist/sloopytv.apk
```

Accept the debugging prompt on the TV the first time. Friends without a Mac can use the
"Downloader" app on the Fire TV to fetch the APK from any link you give them.

## Signing in from a phone

Typing `err74758drue749` with a remote is the worst part of every IPTV app, so the Add Provider
screen shows a QR code. Scan it with the phone's camera, paste the provider link or type the
login on the page, submit, and the TV tests and saves it. No server anywhere: the TV itself
serves the page on the home Wi-Fi (`http://<box ip>:8765/`). If you type the address instead of
scanning, the page asks for the six-digit code shown on the TV.

The page encrypts the login in the browser with tweetnacl before sending, using a key that
travels only inside the QR code (the URL fragment, which browsers never send). Typed-code
sessions derive a key from the code and a per-session salt instead. Six wrong codes or ten
minutes end the session. The TV decrypts with `core/.../crypto/NaCl.kt`, which is tested
against tweetnacl's own output.

## Remote

| Key | Live picture |
|---|---|
| Up / Down | previous / next channel in the current group |
| Left | channel list over the picture |
| Right or Menu | the deck: Guide, Movies, Series, Search, Settings |
| OK | channel badge; OK again opens the channel list |
| Digits | tune by number |
| Back | close an overlay, or switch off |

In the guide: D-pad moves between programmes, OK tunes (or plays catch-up for a past programme
on a channel that has it), Channel Up/Down pages ten rows.

## Security notes

- Provider credentials are encrypted with a hardware-backed AES-256-GCM key in the Android
  Keystore and stored in app-private files excluded from backup.
- `Redact` masks usernames, passwords and credential-bearing URLs in every log line, error message
  and exception chain. Release builds log nothing at all; `android.util.Log` calls are stripped by R8.
- The XMLTV parser disables DOCTYPE processing (no external entities, no entity expansion), caps
  bytes after gzip decompression, caps text and element counts, and never builds a DOM.
- Xtream JSON goes through tolerant accessors with size caps; HTML error pages are detected.
- Only `http` and `https` are accepted anywhere; redirects cannot change scheme to `file:` and the
  like. Plain HTTP is allowed because most providers need it, with a warning on the provider screen.
- The release build sets `FLAG_SECURE`, so the provider screen never appears in screenshots or the
  recents thumbnail.
- Trust is limited to the system CA store; user-installed certificates are not honoured.
