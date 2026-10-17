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
- 0.6.5 (2026-10-16): buddy's note, "no way to see which channel you're on in a folder with no
  listings": a row whose only programmes ended hours ago had its cursor cell off the left edge, so
  nothing lit. `GuideRow` now paints a focus band across the visible row, "No listings here ·
  <channel>", whenever the cursor is on the row and its cell is not in view; the no-data cell
  names the channel while the cursor is on it.
- 0.6.8 (2026-10-16): an update found at launch (or on the 6-hour check) is a prompt over any
  screen (`vm.updatePrompt`, shown in `SloopyRoot`), not a toast: Install now runs
  `vm.installUpdate()` from there and the prompt follows `updateState` (Downloading with
  percent, NeedsPermission, Ready, Failed with Try again); Later dismisses until the next launch.
  `ChoicePrompt` gained an optional `note`. Sports rail: OK/Right on a day whose games were still
  loading asked for focus on tiles that did not exist and the cursor fell to the strip;
  `enterContent()` moves over only when the page is ready (`wantContent` waits for `busy` to
  clear), and OK fetches the day at once instead of after the 250 ms rest.
- 0.6.7 (2026-10-16): Transfer picks what comes over. `SyncManager.apply(b, RestoreOptions)`:
  login, favourites, groups, history, settings as switches; with login off every per-provider map
  is re-keyed from the bundle's active provider to this stick's own, favourites not on this login
  are skipped (same panel = same ids), and the stick keeps its own transfer code (joining the
  other's would let this stick's backup overwrite theirs). Settings › Transfer › Restore fetches
  first (`vm.fetchBundle`) and shows `CheckPrompt` (ui/common, the multi-select twin of
  ChoicePrompt), default login off when a provider exists; the fresh-stick path in ProviderScreen
  still copies everything. VOD bar: the hide timer counts four seconds of actual playback, so a
  bar raised while buffering no longer stays up for the whole film.
- 0.6.6 (2026-10-16): Tonight on a big lineup. `buildTonight` read the guide with a LIKE per
  trending title and per sport word (minutes on a million rows); now one indexed range read of
  everything on tonight (`ProgrammeDao.betweenBlocking` → `catalog.programmesBetween`) and title
  matches in memory. `vm.tonightBusy` drives a "Hang on · fetching tonight's programmes" notice;
  the screen moves focus off the strip onto that notice at once (so the label panel folds), then
  to the first tile when items land. Sports day pages fetch 250 ms after the cursor rests.
  `CrashLog.noteSystemExit` (Android 11+, `getHistoricalProcessExitReasons`) writes
  files/last_exit.txt when Android closed the app (ANR, low memory, crash) and About shows it
  under "Last time the app was closed on you": the buddy's "kicked out" left no crash line
  because an ANR kill never reaches the exception handler.
- 0.6.5 (2026-10-16): buddy was "kicked out" opening Sports › Tomorrow: `resolveGameChannels`
  ran on Main and called `channelsForNetwork` (a `Dupes.key` over every channel) per network
  per game, so a Saturday slate on a 10k-channel lineup froze the app long enough to be killed.
  Now it runs on Dispatchers.Default, keys each channel once and each network once per refresh,
  and looks at a game's first three networks only. No crash on the demo, which is why the
  emulator never showed it.
- 0.6.4 (2026-10-16): Sports rebuilt in the rail shape (`ui/scores/ScoresScreen`): rail = Live
  now, Today, Tomorrow, Yesterday, the next five weekdays, My teams, Standings per league; the
  content shows the game under the cursor as a card ("OK to watch · On <channel>") and the day's
  games as tiles by league. Hold OK: watch, remind (pre + channel), follow/unfollow either team,
  pick the channel for the network, hide scores. The remote's Menu key toggles `prefs.hideScores`
  (scores and clocks blank; "LIVE"/"FINAL" only). Followed teams are ESPN (league, id) pairs in
  `prefs.followedTeams` ("league|id|name|abbr|logo"), in the sync bundle; `Game.has(league, id)`
  matches by id, never by name. Team lists (`Scores.teams`, cached a week in
  `prefs.teamList`) and standings (`Scores.standings`, `/apis/v2/.../standings`, conferences
  walked recursively; college tables repeat stats per split so the first occurrence or the
  `total`-typed stat is the record) are ESPN public JSON, read from the stick. Channel for a game
  is resolved in one pass from `catalog.nowPlaying` (title names both teams) → network map →
  name guess, never `searchProgrammes` (a LIKE over a million rows per game was why Scores
  "didn't work" on the user's box). Rail rows cancel Right, so the screen's rail handles Right →
  content. Emulator note: adb `--longpress` does not fire tv-material `onLongClick`; real remotes do.
- 0.6.3 (2026-10-16): duplicates across countries. `Dupes.key` keeps the country prefix as part
  of the identity ("US| ESPN HD" = "US: ESPN 4K" ≠ "UK| ESPN"; tests updated), and
  `vm.collapse` only ever shows a member of the list it was given (the preferred copy when it is
  there, else the best copy that is), so a cluster's primary from another group never appears
  inside this one. The off switch already existed: Settings › Listings › Collapse duplicate
  channels.
- 0.6.2 (2026-10-16): buddy's bug, "channel names missing until you scroll": the row Canvas
  painted outside its bounds, so a programme that began before the view's left edge (negative x)
  drew its rectangle over the channel column. `clipToBounds()` on the Canvas. `SloopyDb.migrating`
  (raised by the `migration()` helper) makes the root show "PREPARING THE GUIDE" while a schema
  migration runs, so an index build on a million rows is never a black screen. About's guide
  line adds the database file size.
- 0.6.14 (2026-10-17): Sports channel guess. "FOX" games tuned FOX SOUL and "ABC" the bare
  national feed (The View, not the game). `core/sports/Affiliates.rank` (tested) ranks a channel
  key as a carrier of a network: city + state 4, call sign or city 3, bare network 2, state
  only 1 (the viewer's rule: state-only names are the national news feed), and look-alikes
  (FOX SOUL/NEWS/SPORTS, ABC NEWS, CBS SPORTS, ESPN DEPORTES …) are out, and so is any
  broadcast-network name with PRIME in it (0.6.15, the viewer: those feeds never show sports). The key's leading
  country prefix is dropped first. `vm.channelsForNetwork` returns that order (US groups first
  within a rank) and both the guess and the hold-OK picker use it; a mapped channel still wins.
- 0.6.11 (2026-10-16): login and transfer codes on Fire OS 6. Both derived their AES key through
  `SecretKeyFactory("PBKDF2WithHmacSHA256")`, which Android only ships from API 26, so on the
  friend's Fire OS 6.7.1.1 stick (Android 7.1) `decrypt` returned null, "That code didn't unlock
  the login", and the server had already burned the code. `core/crypto/Pbkdf2` is a hand-written
  PBKDF2-HMAC-SHA256 (RFC 7914 vectors + JDK-equality test) and `CodeClient`/`SyncClient` use
  `Pbkdf2.aesKey`. Rule: nothing in the crypto path may depend on a provider algorithm newer
  than API 24; HmacSHA256, SHA-256/512 and AES/GCM are safe. Open: `/redeem` marks a code used
  before the TV has unlocked it, so a failed unlock still costs a code.
- 0.6.10 (2026-10-16): the same friend's stick opened the app but showed Fire TV's placeholder
  tile. The banner was only on `<application>`; older Fire OS launchers read `android:banner`
  from the launcher `<activity>`, so it is set on both now, with banner.png at every density
  (mdpi to xxxhdpi, sips-scaled from the xxhdpi master). If a tile is still generic after an
  update, a stick restart refreshes the launcher's icon cache.
- 0.6.9 (2026-10-16): older Fire Sticks. A friend's stick installed the app but it went black and
  back to home at launch. `minSdk` is 24 and Fire OS 6 (Fire TV Stick 4K 2018, Cube 1st gen) is
  Android 7.1, where `java.time` and `java.util.Base64` do not exist. Core library desugaring is on
  (`isCoreLibraryDesugaringEnabled` + `desugar_jdk_libs` 2.1.5) so the time classes ship inside the
  APK, and Base64 goes through `core/util/B64` (Kotlin's own encoder, tolerant of missing padding).
  Never import `java.util.Base64` or anything else that first appeared in API 26 without a guard;
  `NotificationChannel` is guarded in `Recorder`. There is no API 25 emulator image installed, so
  this is verified only by the dex contents (`j$/time` present, no `java/util/Base64`).
- 0.6.1 (2026-10-15): the user's About line read "100 rows in 28585 ms": the IN-list window query
  let SQLite choose a plan that walked far more than it returned. DB v5 adds the index
  (providerId, gen, epgId, stopEpoch); the store reads one channel at a time through
  `windowBlocking` (`INDEXED BY` that index: equality on three columns, a short range on
  stopEpoch). The sweep runs `PRAGMA wal_checkpoint(TRUNCATE)` when it goes idle. The About
  line now also shows the programme count and the WAL size. If a read is still slow, suspect
  the log file size or a guide import in progress. Measured on the user's Shield, 2026-10-15:
  before, 100 rows in 28,585 ms; after, 100 rows in 207 ms with 953,893 programmes in the table
  and a 0 MB log. The index build itself froze the first launch for about a minute (a migration
  on a ~1M-row table); any future schema change needs a "preparing the guide" screen in front.
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
KV free plan: 1,000 writes/day; the day resets at 00:00 UTC (8 pm Eastern), so a morning
warning covers the previous evening's viewing. Second 50% warning on 2026-10-17 at 11:20 am:
every channel change touches History, which is in the backup bundle, so each stick uploaded a
backup up to once a minute all evening, plus presence every two minutes. Since 0.6.12 automatic
backup uploads are 15 minutes apart (`SYNC_GAP_MS`; a pending one is flushed from `onCleared`
on `detachedScope` so switch-off never loses it) and presence is rewritten only when four minutes
old, or ninety seconds old and the channel changed (TTL 600). Budget: a stick costs at most ~4
backups + ~15–40 presence writes an hour while on. The KV Metrics chart (dash → Workers KV →
CODES → Metrics, "Request count") showed the real loop on 2026-10-17: a flat 60 writes/hour with
no reads from 6 pm to 1 am. The change watcher merged raw `Prefs` flows, which emit on every
DataStore edit, and each upload's `setSyncedAt` was such an edit, so every upload scheduled the
next. 0.6.13 wraps every source in `distinctUntilChanged()` and drops the initial emissions, so
only a real change uploads. Rule: never put a raw `ds.data.map` flow or a Room flow into a
trigger without `distinctUntilChanged()`. Cloudflare warned at 50% on 2026-10-16 with ~550 requests/day:
the rate limiters wrote a KV mark per request. Since then (`memLimited`) limits live in isolate
memory, presence (`/friends/me`) is rewritten only on a channel change or after two minutes
(TTL 300 s), and every counter/cache put is `.catch(() => null)` so a quota miss degrades instead
of failing the request. Remaining writers: login code mint/redeem, sync put, config, poster and
trending caches, Toad counters, presence. If the group turns on friends presence at scale, the
Workers Paid plan ($5/mo, 1M writes/day) is the answer, not more trimming.
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
