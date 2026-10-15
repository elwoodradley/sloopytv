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
- The emulator is signed in to the user's real IPTV account, and the app tunes a channel the moment
  it launches. Providers cap concurrent connections, so an emulator left playing can lock the user
  out of his own TV. Force-stop the app and kill the emulator the moment a check is done, and never
  leave it running between steps while the user may be watching.
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

## Navigation chain (TiviMate-style, user-approved 2026-10-13)
Everything happens inside `GuideScreen`: Picture → Back → grid (focus on the current channel's now
cell) → Back → groups rail slides out on the current group → Back → the rail shows the sections
(Live TV, Guide, Movies, Series, History, Search, Settings) → Back → "Switch off?". Right walks the
rail back in; Left off the grid's edge opens the groups. Hold Back anywhere returns to the picture
(`MainActivity.dispatchKeyEvent` → `vm.goHome()`). Other screens' Back returns to the guide with the
sections out (`vm.guideOpensAtSections`). OK on the picture opens `InfoOverlay` (badge, ten recent
channels, action bar). NavHost transitions are all `None`: hard cuts only.
Movies, Series and Settings use the same shape (`ui/common/Rail.kt`): categories or sections down
the left, content on the right; highlighting a rail row shows it, Right or OK goes in, Left off the
first column or Back comes out, Back on the rail leaves. No top navigation row anywhere (the user
found it unintuitive). Hold OK on a group in the guide rail picks it up; Up/Down move it; OK drops.
Guide programmes come from `vm.programmesFor` (cache in the view model, warmed around the current
channel while watching) so the grid is full on its first frame after Back.
Gotcha: something under the guide cells swallows Back's key-down and drops focus, so the guide takes
Back itself in `onPreviewKeyEvent` (on key-up) and parks focus on a hidden node while the rail is
rebuilt. Overlays that cancel on Back must consume both key-down and key-up (`QuitPrompt`). A
long press's own key-up must not count as the OK that drops a grabbed group.

## Added in 0.2.0 / 0.3.0 (2026-10-14)
- Themes are palettes in `ui/theme/Theme.kt` (`Palettes`); `Sloopy.*` colours read the current one.
  Every time shown goes through `ui/common/TimeFmt` (12/24 h). Both live in the sync bundle.
- Transfer: `sync/SyncManager` builds the bundle (providers, favourites, order, History, settings,
  license), `sync/SyncClient` encrypts it with a key from the 8-char code and talks to the code
  server's `/sync/put` and `/sync/get`; uploads re-run 8 s after any change, a minute apart at most.
- Paywall: the code server's `/config` names a Gumroad product; `pay/License` verifies keys against
  Gumroad; `vm.locked` swaps the whole UI for `UnlockScreen`. Empty product = free app.
- Recording: `record/Recorder.kt` holds the foreground `RecordService` (copies the TS to a file),
  the alarm receiver, folder handling (SAF tree or the app's own storage). Xtream channels are
  forced to TS; HLS-only URLs refuse with a message. Recordings play through the VOD player as
  `Playing.Vod(kind = "recording")`; the player's data source is DefaultDataSource over OkHttp.
- Icon strip (0.3.4, `NavStrip` in `ui/common/Rail.kt`): seven drawn line icons at the far left of
  every screen (Guide, Movies, Series, History, Recordings, Search, Settings). Landing on it opens a
  label panel over the content to its right. Guide Back chain is grid → groups → strip → switch off.
  Groups can be hidden: hold OK on a group → Hide; Settings › Listings › Hidden groups → Show.
  Hidden groups drop out of the guide, channel list and search; the set rides in the sync bundle.
- Guide layout (0.3.2): the groups panel is always visible. Resting on a group for 220 ms previews
  it on the right; OK moves the cursor into the channels with the group still marked; Back returns
  the cursor to the groups. Every row draws a focusable placeholder cell while its listings load
  (`onLoaded` hands the cursor to the real cell), so the cursor can never be stranded.
- Provider names are cleaned at import (`core/model/Names.clean`, with tests): "##### X #####" → "X".
  Existing rows pick it up on the next channel refresh; the guide also cleans at display time.
- Hold OK on a guide cell or a search result = menu (watch, record with 2/15/60 min padding,
  favourite). Recordings start a minute early. Live opened from search keeps search on the back
  stack so Back returns to the results; the query lives in `vm.lastSearch`.
- First run with no providers: `ProviderScreen` shows the "Add your service" popup with the code
  field focused; Back dismisses it to the plain form.
- Remote Menu key in the guide = quick sort (groups A–Z / provider order; channels by number / name).
- Local code server for tests: `cd codes && npx wrangler@4 dev --local --port 8787 >| /tmp/wrangler.log`
  (zsh noclobber: use `>|`), `adb reverse tcp:8787 tcp:8787`, Settings › Providers › code server
  `http://127.0.0.1:8787`. Deploy with `npx wrangler@4 deploy` from `codes/`.

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
