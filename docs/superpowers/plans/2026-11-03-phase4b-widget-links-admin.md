# Phase 4B – Widget, Verbindungsstatus, Links, Admin-Menü (Plan)

Scope: spec 2.5 (Homescreen-Widget: Cover, Play/Pause, ±30 s), 2.7 (Widgets) and 2.7a (Links öffnen,
Verbindungsstatus, kleines Admin-Menü). Global constraints of the phase-1 plan apply (strings in `values/` and
`values-de/`, offline first, nothing on a start path awaits network I/O). 4A behaviour (car, voice, resumption,
`CarSafePlayer`) stays as it is; `CarBrowseTest` must still pass.

## 1. Homescreen widget

| Topic | Decision |
|---|---|
| Toolkit | Jetpack Glance `glance-appwidget` 1.2.0 (latest stable), `glance-appwidget-testing` for unit tests. Glance only sets its own `data` URI on a service intent when none is set, so every button gets its own `lauschig://widget/<key>` data (distinct PendingIntents; Media3 then finds the session through `onGetSession`). No fallback to RemoteViews needed. |
| Sizes | `SizeMode.Responsive`: small (≈ 110×110 dp: cover + play/pause on top of it) and wide (≥ 250×80 dp: cover, title, chapter or author, −30 s, play/pause, +30 s, thin progress). `GlanceTheme` (dynamic colors on 12+, baseline M3 scheme below; dark mode from the system). |
| Buttons | Media-button intents (`ACTION_MEDIA_BUTTON` + `EXTRA_KEY_EVENT`) straight to `PlaybackService`, like Media3's own notification: PLAY when the widget shows "paused" → `getForegroundService` (works with the process dead: `MediaSessionService.onStartCommand` → `onGetSession` → PLAY on an empty player → 4A's `onPlaybackResumption`); PAUSE, REWIND (−30 s), FAST_FORWARD (+30 s) → `getService` (the service is running whenever they make sense; `CarSafePlayer` maps rewind/fast-forward to ±30 s). Without any title to resume the play button opens the app instead (no foreground start that could not start playback). |
| Open the app | Tap on cover/title → `MainActivity` with `open=player`: the app adopts the service's title (`adoptFromService`) and opens the player if a title shows up within 2 s, else stays on Start. Empty widget → app start screen. |
| State | `widget/WidgetState` (title, subtitle, serverId, key, cover version, playing, position, duration) in its own DataStore, written by the service only. `PlaybackService` pushes on title change, metadata change (chapter subtitle), play/pause, seek while paused, service start (fresh "paused" state after a process death) and service end; while playing at most every 30 s (`WidgetThrottle`, pure). Book-wide position from the files' durations (`EXTRA_FILE_DURATION_MS`). Empty store → the last title of this device (`LastTitleStarter.last()` + Room), else "Nichts geladen – tippen zum Öffnen". |
| Cover | `CoverStore.file(CoverRef)` on `Dispatchers.IO` inside the widget's state flow (downloaded / device / cached cover; a miss starts 4A's background fetch, and `PlaybackService.coverArrived` refreshes the widget too), decoded with `inSampleSize` to ≤ 300 px. Never the provider, never network on the main thread. |

Files: `widget/WidgetState.kt` (state, mapping, throttle), `widget/WidgetStore.kt` (DataStore + Hilt),
`widget/LauschigWidget.kt` (Glance UI + receiver), `widget/WidgetPublisher.kt` (player listener in the service),
`res/xml/lauschig_widget_info.xml`, layout preview, manifest receiver; changed `PlaybackService`, `MainActivity`,
`AppRoot`.

Tests: `WidgetStateTest` (mapping: global position across files, chapter subtitle, playing = playWhenReady and
ready/buffering, empty player keeps the last title paused), `WidgetThrottleTest` (structural changes at once,
position only every 30 s while playing, at once when paused), `LauschigWidgetTest` (`runGlanceAppWidgetUnitTest`:
small/wide content, empty state, button descriptions). Emulator: place the widget on the Pixel launcher, kill the
process (`run-as … kill`, not force-stop, which cancels PendingIntents), press play/±30 s/pause, check logcat for
`ForegroundServiceStartNotAllowedException`.

## 2. Verbindungsstatus

| Topic | Decision |
|---|---|
| Probe log | `connection/ConnectionLog` (singleton): per address the last probe (ok, latency, time, error text), the last resolution (state, time). `AddressResolver` reports every probe and resolution to it (optional parameter, tests without it stay valid). `ReachabilityProbe.check(url)` (default: `isReachable`) lets `OkHttpReachabilityProbe` report the failure (exception class + message, HTTP code) – never headers or tokens (the ping is unauthenticated). |
| Neu verbinden | `AddressResolver.reconnect(addresses)`: probes every configured address in parallel (the local one also off the home Wi-Fi, so the window shows which one answers), then `recheck()` restarts the running `states()` loop at once (no waiting for the 5…60 s offline back-off). The window shows "Verbinde …" until the shared state answers (≤ 10 s). |
| Network type | `NetworkInfo.describe()` (default null): WLAN / Mobilfunk / Ethernet / VPN (+ underlying transport), metered or not, validated. |
| "Socket-Status" | Lauschig has no socket.io connection. Shown instead: progress sync – last successful run (stamp written by `ProgressSync`), dirty progress rows and unsent listening sessions of the account. |
| Kopieren | `connectionDiagnostics(...)` (pure): plain text with app version, user name, addresses, state, probes, network, sync state. No tokens, no headers. |
| Where | Route `ConnectionStatus`; Settings → "Verbindung" → "Verbindungsstatus"; tapping the offline/connecting banner. |
| Debounce | `ApiProvider.checking`: true from the start of the shared probe loop (and again after it stopped because nobody listened) until its first answer. The UI's offline indicator (banner + tile dimming) settles only when Offline was *answered* by a probe and persisted `OFFLINE_BANNER_DELAY_MS` (now 3 s). A first check running longer than that shows "Verbinde …" instead of "Offline". The shared `connection` / `connectionState` values (car, service, sync) are unchanged. |

Files: `connection/ConnectionLog.kt`, `connection/ConnectionDiagnostics.kt`, `ui/connection/ConnectionStatusScreen.kt`
+ ViewModel; changed `AddressResolver`, `OkHttpReachabilityProbe`, `AndroidNetworkInfo`, `NetModule`, `ApiProvider`,
`ProgressSync`, `AppScaffold`, `AppRoot`, `AppViewModel`, `SettingsScreen`, nav.

Tests: `AddressResolverTest` (probe log entries, `recheck` re-probes at once while offline, `reconnect` probes every
address), `ApiProviderTest` (checking until the first answer, again after the listeners left), `ConnectionDiagnosticsTest`
(text has no token, all sections), `ConnectionStatusViewModelTest` (state mapping, reconnect progress/result with
fakes), `SettledOfflineTest` (pending check never settles to offline; "Verbinde …" after the delay).

## 3. Links öffnen

| Topic | Decision |
|---|---|
| Hosts | Gradle property `lauschig.linkHosts` (comma-separated, default `audiobookshelf.wolftown.io`), validated (host names only) and written into a generated manifest overlay (`addGeneratedManifestFile`): one VIEW filter (http + https, `BROWSABLE`) on `MainActivity` with path patterns `/item/.*`, `/.*/item/.*`, `/library/.*/series/.*`, `/.*/library/.*/series/.*`. No `autoVerify` (no assetlinks on the server); `BuildConfig.LINK_HOSTS` for the settings hint. |
| Settings | Section "Links": the hosts, whether Android lets Lauschig open them (`DomainVerificationManager` user state, 12+), button → `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` (12+, else app details). |
| Share target | `ACTION_SEND` `text/plain`: the first http(s) URL in the text. |
| Matching | `links/AbsLink.parse(url, addresses)`: only URLs whose origin (scheme, host case-insensitive, effective port) equals one of the signed-in account's addresses (local or public); path prefixes allowed (`/audiobookshelf/item/<id>`), trailing slash, query and fragment ignored. Item → `ItemDetail`; series (`/library/<lib>/series/<seriesId>`) → the series page via the `seriesId` stored in Room, else "not found". |
| Unknown item | In Room → open (also offline). Else online → `LibrarySync.loadDetails` then open; offline → "Titel nicht offline verfügbar"; 404 → "Titel nicht gefunden". Foreign origin / no account → "Link gehört zu keinem eingerichteten Server". Messages as snackbar. |

Files: `links/AbsLink.kt`, `links/LinkOpener.kt`, `links/IncomingIntents.kt` (pure routing of `MainActivity`'s intents);
build script task; changed `MainActivity`, manifest (share filter), `AppRoot`/`AppViewModel`, `SettingsScreen`.

Tests: `AbsLinkTest` (prefix paths, trailing slashes, query/fragment, case, ports incl. default ports, foreign host,
foreign scheme, malformed ids, series), `IncomingIntentsTest` (Robolectric: VIEW, SEND with text, launcher, cleanup
extra, SSO callback untouched), `LinkOpenerTest` (in Room, fetch online, offline, 404, foreign server).

## 4. Admin menu

| Topic | Decision |
|---|---|
| API | New `absapi/admin/AdminApi.kt` (own Retrofit interface, so existing `LibraryApi` fakes stay): `POST api/libraries/{id}/scan` (plain "OK": `Response<Unit>`, the body is never parsed), `POST api/items/{id}/scan` (`{ "result": "UPDATED" }`), `POST api/items/{id}/match` with `{ "provider": <library.provider> }` only, `GET api/users/online` (`usersOnline` with their session, `openSessions`). `LibraryDto.provider` added (ABS 2.37.1 sends it per library, e.g. `audible.de`; ABS's own "match all" uses exactly that), so quick match ships. |
| Who | `Account.isAdmin` only; offline → entries disabled with a hint. 403 → "Keine Berechtigung". |
| Library | Settings → "Server-Verwaltung" (admins): libraries with "Scannen" (confirmation), "Wer ist online" with refresh. After a library scan a library sync runs one minute later (the server scans in the background). |
| Item | Detail page overflow (admins, server titles): "Titel neu scannen", "Titel neu zuordnen" (confirmation each); afterwards the item's details are reloaded into Room. |
| Online users | Users with a socket connection (web, official app) and their session; open playback sessions (title, device, position). Lauschig itself keeps no server session, so its listeners only show up via progress, not here (noted in the screen). |

Files: `absapi/admin/AdminApi.kt`, `AdminModels.kt`; `admin/AdminRepository.kt`, `admin/ServerAdminScreen.kt` +
ViewModel, `admin/ItemAdminMenu.kt` + ViewModel; changed `ApiProvider`, `SyncTrigger`/`SyncScheduler`, `SettingsScreen`,
`ItemDetailScreen` route, nav.

Tests: `AdminApiTest` (MockWebServer: plain "OK", 403, scan result, match body has only the provider, online users),
`AdminRepositoryTest` (refresh after scan/match, no provider, offline, forbidden), `ServerAdminViewModelTest`.
Scan/match only against the local test server (emulator), never a real server.

## Order and commits

Plan → widget → Verbindungsstatus (with the debounce) → links → admin; one or more commits per task on `phase-4`.
End: `timeout 900 ./gradlew testDebugUnitTest lintDebug assembleDebug -q`, `CarBrowseTest` on emulator-5554 with
`leaveApksInstalledAfterRun`, debug APK reinstalled.

## Implementation notes (2026-11-03)

Decisions made or changed while building it:

- **Glance, not RemoteViews.** Glance 1.2.0 sets its own data URI on a service intent only when none is set, so each
  media-button intent carries `lauschig://widget/key/<code>` and the PendingIntents stay apart; Media3 finds the
  session through `onGetSession` (the data is no session URI). `GlanceTheme` gives Material You colors; `SizeMode.Responsive`
  with SMALL 48×48 dp (1×1 … 2×2: cover + play/pause) and WIDE 200×48 dp (row with title, chapter, −30 s, play/pause,
  +30 s, progress; default 4×1). Tests run the composables with `runGlanceAppWidgetUnitTest` (Robolectric for strings).
- **Buttons like Media3's notification**: PLAY (widget shows paused) = `getForegroundService`, PAUSE and
  REWIND/FAST_FORWARD (`CarSafePlayer`: ±30 s) = `getService`. Without a title `LastTitleStarter` can resume, play opens
  the app. The service writes a fresh paused state when it starts, so a widget that still said "playing" after a
  process death corrects itself.
- **A failing resumption crashed the app (also 4A's Bluetooth path).** With the process dead, offline and a last title
  that is not downloaded, a play key ended in `ForegroundServiceDidNotStartInTimeException` 30 s later (API 35
  emulator): Media3 only goes foreground once something plays. `ForegroundStartGuard`: a play-key intent arms it in
  `onStartCommand`; a failed resumption (reported by `LibraryCallback.onResumption`) or nothing playing after 8 s makes
  the service go foreground for a moment with its own low-importance channel, stop it and `stopSelf()` (Media3's
  `stopSelfSafely` does the same), plus a toast with the reason. Verified on the emulator: no crash after 40 s.
- **Connection log in memory only**: probes and decisions of this process; a fresh process starts empty and the first
  probe fills it. `ProgressSync` writes a `SyncStamps` entry `progress_sync:<serverId>` after each successful run.
- **Offline banner**: `ApiProvider.checking` (true from the start of the shared probe loop, and again after it stopped
  because nobody listened, until its first answer). Banner and dimming need "not online" for 3 s (was 2 s); "Offline"
  only when a probe answered, "Verbinde …" while the first check is still running. The banner is tappable (window).
- **Links**: generated manifest overlay via `variant.sources.manifests.addGeneratedManifestFile`; the property is
  validated (host names only). Series links resolve through the `seriesId` Room knows from loaded details (new
  `ItemDao.seriesNameById`); no extra API call for unknown series. The settings section reads the user's choice with
  `DomainVerificationManager` (12+) and re-reads it on resume.
- **Admin**: quick match ships – ABS 2.37.1 sends `provider` per library (the test library: `audible.de`) and its own
  "match all" passes exactly `{ provider: library.provider }`; Lauschig sends only that (title/author come from the
  item on the server). A library without a provider shows "keinen Metadaten-Anbieter" instead of guessing. The detail
  page gets its own admin icon (dropdown) next to the existing "more" menu instead of growing the shared progress menu.
  "Wer ist online" names the signed-in user's own open sessions too (they have no socket entry).

Not done / left for later: the series page for a series Lauschig has never seen (needs `GET /api/series/<id>`); the
widget preview is a static layout (no generated preview); podcast pages have no admin entries (book detail page only).

Verification: see the phase report (unit tests, lint, assemble, `CarBrowseTest` on emulator-5554, widget presses with
the process killed, failing resumption, links via VIEW/SEND, admin scan/match/online users against the local test ABS).

## Fix round 1 (review, 2026-11-03)

- **Play-key guard** (#1, #3): arms only while the player is empty (`shouldArm`), lets go for good once something plays
  (player listener → `onEngaged`), and never settles a service that is already in the foreground (API 29+,
  `foregroundServiceType`): `startForeground` with its own id made Android cancel Media3's media notification. The first
  press's deadline counts (7 s up to API 33, 20 s from 34). At the deadline with the resumption still running the
  service goes foreground with the placeholder and keeps running; it stops (with the toast) only when the resumption
  fails, or when nothing plays after a successful one. Media3's own `startForeground` later replaces the placeholder.
- **Widget covers** (#2): `WidgetCoverCache` keeps the decoded cover per `CoverRef` (a miss is asked again after 5 min);
  only the widget's own cover arriving redraws it (an episode matches by its podcast).
- **Stale "playing"** (#4): `PlaybackService.running` (onCreate/onDestroy); without the service the widget shows the
  title paused whatever redraws it (boot, update, next write). The store itself is corrected the next time the service
  starts (as before); no extra boot/update receiver.
- **Accounts** (#8): `WidgetAccountWatcher` (started by the app) clears a stored title of another server after a
  sign-out or server change; the widget never shows one meanwhile; device titles stay.
- **Neu verbinden** (#5): "Verbunden" only if a probe of this reconnect answered and the shared state is online.
- **Cold-start links** (#6): `awaitConnectionAnswer` waits while `ApiProvider.checking` says the first check is running,
  at most 10 s, before "Titel nicht offline verfügbar".
- **Path prefixes** (#7): Android's globs do not backtrack (`.*` stops at the next `/`; an advanced-pattern `.*` eats
  the rest and never gives back – checked on the API 35 emulator), so the filter lists the depths: `pathPattern` for up
  to 2 prefix segments on every version, `pathAdvancedPattern` with explicit `[^/]+` segments for 3 and 4 on Android 12+.
  **Limit:** before Android 12 links with a prefix deeper than 2 segments, and everywhere deeper than 4, do not open
  Lauschig (sharing the link still works). `LinkFilterTest` resolves the merged manifest with Robolectric.
- **Tests** (#9): widget actions checked with `assertHasStartServiceClickAction` (intents built once per key);
  `WidgetPublisher` takes a snapshot function (ticker, release, stale-state correction tested); MainActivity's routing
  moved to `IncomingIntents.consume` (replay, stripping, open extra, voice, SSO never a link).

## Fix round 2 (re-review of the play-key guard, 2026-11-03)

Checked against the sources before changing anything:
- AOSP `ActiveServices` (android-13.0.0_r1 vs android-14.0.0_r1): `getForegroundServiceTypeLocked` returns
  `r.foregroundServiceType` whether or not the service is foreground, and only 14 resets it in the stopForeground
  branch – the type says nothing on API 29–33. A start of a service whose record is foreground gets no deadline
  ("Service already foreground; no new timeout"); any later `startForeground` clears the deadline; bringing the service
  down before it crashes the app; `startForeground` with another id cancels the previous foreground notification.
- Media3 1.11.1: the media notification is `DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID` = 1001, posted with
  `NotificationManager.notify(1001, …)` without tag (Lauschig keeps the default provider). For a player that does not
  need the foreground, every notification update calls `stopForeground(DETACH)` itself. `isPlaybackOngoing()` /
  `startedInForeground` is never reset after the first foreground start, so it cannot tell "foreground now" either.

Final design (`ForegroundStartGuard` + `PlaybackService.StartHost`):
- Arm on every play key (PLAY, PLAY_PAUSE, HEADSETHOOK) while nothing plays, loaded or empty; the first press's deadline
  counts (7 s / 20 s). Engagement ends the arming for good (play → pause within the timeout never settles).
- Settle at the deadline, or at once when the resumption fails, never reading the foreground type:
  - loaded title and Media3's notification (id 1001, no tag) is posted → `startForeground(1001, that notification)`:
    same id and object, nothing swapped or cancelled, no stop. Media3's next update detaches it again by itself (doing a
    DETACH here would end Media3's own foreground after a recent pause, so it is left to Media3);
  - otherwise placeholder (4711): with a resumption still running it stays up, at most 60 s (cap), else it is left at
    once: `stopForeground(REMOVE)` plus `cancel(4711)` (Media3's own DETACH may have detached it meanwhile) and
    `stopSelf()` only for an empty player (a loaded title stays with Media3). The toast for a failed resumption stays.
- Deviation from the ruling: none in substance; "stop" is limited to an empty player (a loaded but unplayable title is
  not unloaded), and the placeholder is also cancelled explicitly.

Verified on the emulator (API 35): play on a loaded title, pause after 2 s → notification 1001 still posted after 15 s;
headset double tap on a paused title (platform dispatch) → +30 s, no crash after 40 s, notification intact; failing
resumption with the process killed, twice in a row (first with Android still holding the record as foreground, then
not) and twice more with the app's controller bound → placeholder shown and left each time, process alive after 42 s,
no placeholder left behind. New instrumented `PlayKeyGuardTest`: a held PLAY key (Media3 ignores repeats) and two
headset presses delivered with `startForegroundService` on a paused title survive the deadline; the held key went
through the "adopt the media notification" path.
