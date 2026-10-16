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
Guide programmes come from the per-group store (`vm.groupGuide`, see 0.5.3), warmed for the group on
screen while watching, so the grid is full on its first frame after Back. `vm.programmesFor` stays for
the live screen, Tonight and recording (database only, never the network).
Since 0.6.0 the grid is one focusable node with a drawn cursor (see 0.6.0 below); the guide still
takes Back itself in `onPreviewKeyEvent` on key-up. Overlays that cancel on Back must consume both
key-down and key-up (`QuitPrompt`). A long press's own key-up must not count as the OK that drops
a grabbed group.

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
- 0.5.0 (2026-10-15), the big batch. Self-healing: `PlayerHolder.onFailed` → `vm.onStreamFailed`
  switches to the next untried copy in the same Dupes cluster only (toast + "BACKUP COPY" line,
  undo via overlay "Original copy"); when none, `data/Diagnosis.run` tests internet, the Xtream
  login (status, active vs max connections → "streaming on another device"), and two other
  channels, and the live banner shows the verdict. Recording the watched channel tees the
  player's own stream (`player/RecordTee`, a DataSource.Factory wrapper); the service falls back
  to a direct download if the viewer tunes away. Season passes: `scanSeasonPasses` books guide
  matches only when ESPN's scoreboard (`core/sports/Scores`, tested) agrees on kickoff, else
  "Needs a look" in Settings › Recordings. Phone remote: `pair/RemoteServer` (ports 8781+, token
  in the QR fragment, started by `vm.ensureRemote()` at root) types into Search and taps to
  tune. Profiles: allow-list of live groups in Prefs JSON, PIN to leave, Settings collapses to
  the Profile page while one is active (movie/series category allow-lists not wired yet).
  Tonight: `buildTonight` from watch counts + favourites + reminders (+ optional sport anywhere,
  new episodes), cap 12, hold → Not interested; posters via the code server `/poster` (TMDB
  token is a Worker secret `TMDB_TOKEN`). Friends: opt-in presence (`/friends/me`, 3-minute
  TTL), `/friends/list`, send-to-TV (`/friends/send` + `/friends/inbox`, polled every 45 s).
  Scores: strip entry, ESPN NFL/CFB/NBA/NHL/MLB, channel by guide title match first, then the
  viewer's network map / name guess (`channelsForNetwork`), hold OK to confirm.
- 0.6.1 (2026-10-15): the user's About line read "100 rows in 28585 ms": the IN-list window query
  let SQLite choose a plan that walked far more than it returned. DB v5 adds the index
  (providerId, gen, epgId, stopEpoch); the store reads one channel at a time through
  `windowBlocking` (`INDEXED BY` that index: equality on three columns, a short range on
  stopEpoch). The sweep runs `PRAGMA wal_checkpoint(TRUNCATE)` when it goes idle. The About
  line now also shows the programme count and the WAL size. If a read is still slow, suspect
  the log file size or a guide import in progress.
- 0.6.0 (2026-10-15): the grid is drawn, not composed. `GuideRow` keeps the channel column as
  composables and paints the cells on one `Canvas` per row (`TextMeasurer`, layouts cached per
  title+width); the whole grid is one focusable node (`gridFocus`) and the cursor is state
  (`cursorRow`, `cursorEpoch`) moved by the screen's key handler: Up/Down wrap and keep the
  time, Left/Right step programmes (`moveTime`) and scroll the timeline only as far as needed,
  Left with nothing before opens the groups, OK selects on key-up, a held OK (repeatCount ≥ 2)
  opens the cell menu, Channel± jump ten rows. No per-cell FocusRequesters, no placeholder
  focus, no watchdog, no parking node; `DetailPanel` reads the cursor. `vm.guideStats` (last
  read ms/rows) shows under Settings › About. The timeline header is unchanged.
- 0.5.8 (2026-10-15): the 0.5.4–0.5.7 store had two callers (the screen and the watch-time
  warm-up) sharing one job, last writer wins, so the screen's rows could be cancelled by the
  warm-up's and sit on placeholders; and its reads queued behind the sweep's writes on Room's
  4-thread executor. Now `ensureGuide` merges every request for the same group into
  `guideQueue` and one `drainGuideQueue` loader drains it nearest-first (only a different group
  cancels); reads go through `programmesManyDirect` → `windowManyBlocking` on Dispatchers.IO;
  the database is WAL with an 8-thread query executor; the sweep waits while `guideLoading`
  and runs two-wide when the guide file is fine (six-wide only when it was refused).
- 0.5.7 (2026-10-15): sweep order is current channel → rows on screen → favourites → the
  rest; six-wide for the urgent set with no pause; a channel whose table came back empty is
  remembered in `meta` as `nolist:<id>` for a day so launches stop re-asking. `Catalog.epgNote`
  says which identity the guide file was accepted as, or every answer ("TiviMate: HTTP 451 ·
  ..."), and the guide header shows it next to the refusal.
- 0.5.6 (2026-10-15): the guide file is retried with other User-Agents when the panel refuses
  it (`Catalog.EPG_AGENTS`: ours, TiviMate, VLC, Chrome, okhttp; 401/403/404/406/429/451 move to
  the next; the one that worked is remembered in `prefs.epgUserAgent`). The OkHttp agent
  interceptor now keeps a per-request User-Agent instead of overwriting it. The sweep fills the
  rows nearest the view first (`ensureGuide(..., near)`), four-wide for what is on screen, and
  an empty row reads "Loading listings…" while `vm.listingsPending` (wide sweep, channel not yet
  reached) rather than "No guide data".
- 0.5.5 (2026-10-15): leaving the app switches it off. `MainActivity.onStop` stops playback and
  `finishAndRemoveTask()` unless one of the app's own child windows was just started (the
  activity notes the time in `startActivity`/`startActivityForResult`: speech recogniser, folder
  picker, VLC, installer), in which case it only pauses. `ACTION_SCREEN_OFF` does the same, so
  the box going to sleep ends the sound at once. `saveProgress` writes on a detached scope so
  the resume point lands after the view model is cleared. `PlayerHolder.stop` clears
  `RecordTee.playingUrl`, so a recording that rode the stream falls to its own download.
  Reminders only fire while the app is open (they never showed from the background anyway);
  recordings are a foreground service and keep going.
- 0.5.4 (2026-10-15): the 0.5.3 store loaded whole groups (a quarter-million rows for "All
  channels" on a 10k-channel provider) and the sweep ran three-wide over everything, rebuilding
  the store per channel: slower than before on the user's box. Now `ensureGuide(group, channels,
  rowLo, rowHi, lo, hi)` loads only the rows near the view (first visible row in steps of 30,
  about 130 rows, plus the top 40) across the time band; scrolling down or sideways loads more;
  rows not yet loaded show the placeholder. The sweep does the current channel, favourites and
  the rows the guide asked for (`guideWanted`) three-wide, then the rest one at a time with a
  400 ms pause, and filled channels fold into the store in 700 ms batches (`flushChangedKeys`).
  `vm.sweepProgress` (done, total) shows as "Loading TV guide · d / t" with an amber hairline in
  the guide header; the guide-file download shows there too.
- 0.5.3 (2026-10-15): guide speed. Guide rows never touch the database or the network. The
  view model keeps a per-group store (`GroupGuide`, LRU of six groups, `vm.groupGuide`):
  `ensureGuide` runs one `windowMany` query for the rows asked for across a band
  six hours behind the view and six ahead (`GUIDE_BAND_BACK/FWD`), widened as the view scrolls
  (the hour the view starts in is the trigger), and a new group cancels a load still in flight,
  so flicking through fifty groups costs one query. The store is warmed for the group on screen
  while the picture plays, so Back draws a full grid on its first frame. Rows compose cells
  only from two hours behind the view to eight ahead. The timeline still spans 48 h back / 24 h
  ahead for catch-up. Per-channel Xtream listings (`get_simple_data_table`) come from the
  background sweep (`startSweep`): current channel → favourites → visible group → everything
  (the last only when the guide file was refused or the table is empty), three at a time, a
  row updates live when its channel is filled (`guideRowChanged`). A refresh Done drops the
  stores and re-sweeps instead of going cold. Coil crossfade is off.
- 0.5.2 (2026-10-15): the Toad, the app's Claude helper. The Anthropic key is the Worker secret
  `ANTHROPIC_KEY` (local copy in `keystore/anthropic-key.txt`, never in the APK). Worker routes:
  `GET /toad/status?id=<friend code>` (enabled + allowed + requests left today), `POST /toad/ask`
  (one sentence + the group names and favourites → `{say, actions[]}` from a fixed menu:
  favourite_add/remove, hide/show_group, hide/show_channel, tune, remind, record, search,
  nothing), `POST /toad/tags` (every live group name → sports/news/kids/movies/series/music/
  adult/foreign/general, cached in KV by the sorted name set for 30 days). Admin page "The Toad":
  on/off, allowed friend codes (empty = every stick), per-stick daily cap (50), model (default
  Haiku 5.5; spend estimate per model in `TOAD_MODELS`), this month's calls and dollars
  (`toad:spend:YYYY-MM`). The TV: `vm.refreshToad()` at root sets `ToadFlag.on`, which adds the
  footprint icon above Settings in `NavStrip` (`stripItems()`); `ui/toad/ToadScreen` (Speak,
  typed field whose keyboard Done asks, phone QR, recent asks, the plan with Do it / Not now);
  `vm.toadAsk` resolves names locally (`toadFindChannel`: exact → `Names.sameTitle` → word
  match, collapsed through Dupes; groups by cleaned name) and builds steps that run only on
  `toadRun`. Phone remote page shows a "The Toad" box when `/state` says `toad:true` → `POST
  /toad` → `RemoteEvent.Toad` → the TV opens the Toad page with the plan (`vm.toadEvents`).
  Group tags land in Prefs per provider (`groupTags`, keyed by category id) and drive Settings ›
  Listings › "Groups by kind" (hide a whole kind) and "Tick the kids groups" in the profile
  editor. Dead-channel reports stay off. Testing: the emulator's friend code must be on the
  allow-list for the icon to appear; take it off again afterwards.
- 0.5.1 (2026-10-15), buddy's notes. Tonight adds "Popular": the code server's `/trending`
  (TMDB trending TV + films for the day, cached in KV six hours, no key on the TV) and
  `buildTonight` keeps a title only when the service carries it (guide programme tonight via
  `Names.sameTitle`, tested, or a series/film in the library); at most four tiles, toggle in
  Settings › Picture (`prefs.tonightPopular`, default on). Series details: resting on an episode
  swaps the left panel to that episode (still, air date, length, plot; falls back to the show's
  summary with a note). Recordings screen is a rail: Recorded (done/failed) and Scheduled
  (booked, running, and the season-pass "Needs a look" rows with Record/Skip); Back on the rail
  leaves, on the list returns to the rail (rail wrapper uses `hasFocus`, not `isFocused`).
- 0.4.2 (2026-10-15): duplicate engine. `core/model/Dupes.kt` (tested) gives a cluster key from
  the stripped name; identical keys = copies, never "starts with". `vm.dupes` clusters the
  channel list (viewer merges join clusters, splits break them, a preferred copy wins, else best
  quality tag); `vm.collapse()` reduces any list to one row per cluster and is applied in
  visibleChannels and search. "Possible duplicates" (same EPG id, different key) are offered in
  Settings › Listings, never auto-merged. Hold OK on a channel → Copies picker / split. All
  choices ride in the sync bundle. The demo carries "Big Buck Bunny FHD" as a second copy.
- 0.4.0 (2026-10-15): Stone Toad branding. The wordmark is `drawable-nodpi/stone_toad_mark.png`,
  cut from the user's artwork as white-on-transparent (`ui/common/StoneToad.kt` tints it); never
  redraw it by hand, the user rejected that. It rides the power-on (`SloopyRoot`, fades after
  2.2 s), sits on About, the welcome window and the admin page. Operator config on the code
  server: `operatorName`, `announcementText/Id` (shown once per id via `prefs.seenAnnouncement`),
  `reportsEnabled` + Telegram/Discord secrets (never in `/config`). Dead-channel reports are
  scaffolded and dormant: the app shows Report only when `reportsEnabled` is true. Prompts set
  `vm.modalUp` so LiveScreen does not steal focus; `ChoicePrompt` re-grabs focus every 250 ms.
- 0.3.9: `CrashLog` keeps the last uncaught exception (scrubbed) in files/last_crash.txt and
  Settings › About shows it under "Last crash" with a Clear button. When a friend reports being
  "kicked out", ask them to read that section. Guide focus: `focusRow` falls back to any cell of
  the target row, then `focusGrid()`; a 400 ms watchdog re-focuses the grid if nothing in it has
  focus while it is the active area.
- 0.3.7: reminders table (`reminders`, DB v4); `vm.dueReminder` is polled every 15 s and
  `SloopyRoot` shows a prompt over any screen (Watch tunes + goHome). Hidden channels live in
  Prefs like hidden groups. Guide window is 48 h back (catch-up within `archiveDays`); rows only
  compose cells within a 6-hour bucket window around the scroll to keep the Shield happy.
  Catch-up: Left/Right ±15 s, RW/FF ±1 min (0.5.1, was 30 s / 2 min), badge shows the time position. Live OK opens the info
  overlay on key-up and the overlay ignores OK for 400 ms, for phone remotes that repeat.
- 0.3.6: `PlayerHolder.player` is rebuilt when Settings › Picture › Buffer changes (small/medium/
  large, default large); it is Compose state so PlayerViews re-attach. Guide re-downloads every
  N hours (Settings › Listings, default 3). External player: Settings › Picture › Player = VLC
  hands every stream to `org.videolan.vlc` via `vm.externalLaunches` (started by the Activity);
  `playVod` returns false when VLC took it so callers skip opening the player screen. Hold menu
  on a live cell and the details screen offer Open in VLC when it is installed.
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
