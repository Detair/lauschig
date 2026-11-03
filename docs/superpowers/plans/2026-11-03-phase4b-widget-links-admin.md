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
