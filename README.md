# Tube

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

Debug builds install as `tv.tube.player.debug`, release as `tv.tube.player`, so both can live on a box.

## Run on the Mac (Android TV emulator)

An AVD named `tube_tv` (Android 14 TV, 1080p, arm64) is already created.

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
$ANDROID_HOME/emulator/emulator -avd tube_tv -gpu auto &
$ANDROID_HOME/platform-tools/adb wait-for-device
$ANDROID_HOME/platform-tools/adb install -r dist/tube-0.1.0.apk
```

The emulator window takes keyboard input: arrow keys are the D-pad, Enter is OK, Escape is Back,
digits type channel numbers.

## Sideload on a Fire TV Stick or Android TV box

1. On the box: Settings → My Fire TV → Developer options → turn on **ADB debugging** and
   **Apps from unknown sources**. (On Android TV: Settings → Device → About → tap Build seven times,
   then Developer options → USB/network debugging.)
2. Find the box's IP under Settings → My Fire TV → About → Network.
3. From the Mac, on the same Wi-Fi:

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
$ANDROID_HOME/platform-tools/adb connect 192.168.1.50:5555
$ANDROID_HOME/platform-tools/adb install -r dist/tube-0.1.0.apk
```

Accept the debugging prompt on the TV the first time. Friends without a Mac can use the
"Downloader" app on the Fire TV to fetch the APK from any link you give them.

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
