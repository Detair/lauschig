# Phase 5A – Listening statistics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Offline-first listening statistics: every ABS listening session of the account cached in Room, merged with the device's own sessions, shown as a Stats screen (periods, bar chart, streaks, finished books, top titles/authors) and a small card on the Start screen.

**Architecture:** A new paged sync (`GET /api/me/listening-sessions`) fills a `listening_sessions` table (serverId-scoped, purged on sign-out); a pure `ListeningStats` object computes everything from that table plus `local_sessions` with the existing dedupe rule; a `StatsViewModel` exposes it to Compose. No server-side stats endpoint is trusted for numbers (it is not offline and uses the server's time zone).

**Tech Stack:** Kotlin, Room (v8 → v9, AutoMigration), Retrofit + kotlinx.serialization (abs-api), WorkManager (existing SyncWorker hook), Compose M3 (Canvas bar chart, no chart library), Robolectric tests.

**Spec:** docs/superpowers/specs/2026-10-31-lauschig-design.md §2.6 („Statistiken aus den ABS-Hörsitzungen: Hörzeit pro Tag/Woche/Jahr, Streaks, beendete Bücher und Serien“). Code map: scratchpad/phase5-codemap.md. Fabian handed the decisions to Claude (2026-11-04, „mach weiter solange ich weg bin“); every choice below marked **Claudes Wahl** is for his later review.

## Global Constraints
- minSdk 28, target/compile 37; German strings in values-de, English in values/ (parity enforced by tests where they exist).
- Offline first: every number comes from Room; the network only refreshes the cache. Opening the Stats screen while offline works and shows what is cached.
- Every new serverId table follows the sign-out checklist in the code map (AccountDataDao, SERVER_TABLES, schema 9.json, MigrationTest v8→v9). Sync stamp keys end with `:<serverId>`.
- Version 0.3.0 / versionCode 3 starts with this package: bump in `app/build.gradle.kts`, add `Release(3, "0.3.0", R.array.whatsnew_0_3_0)` on top of WHATS_NEW with lines for the UI polish (already merged) and the statistics (DE + EN).
- Gradle always in `timeout`; only emulator-5554 for device checks.

## Decisions (Claudes Wahl)
1. **Source of truth:** all server sessions of the account (`/api/me/listening-sessions`, paged, newest `updatedAt` first – verify the order on the local test server) + `local_sessions` of the same serverId, merged by session id (unsynced local wins, else the larger `timeListening`, like `HistoryAggregation`). Device-library titles (`serverId = "device"`) do not count – they belong to no account.
2. **Day of a session:** the device's local date of `startedAt` (a session across midnight counts for its start day). Weeks start on the locale's first day (`WeekFields.of(Locale.getDefault())`, Monday in DE).
3. **Active day / streak:** a day counts when ≥ 60 s were listened. The current streak includes today if today counts, otherwise it ends yesterday (it is not broken before the day is over). Longest streak over all data.
4. **Finished books:** a book (not an episode) counts as finished on the `updatedAt` day of its first session whose `currentTime ≥ 0.995 × duration`; books marked finished in `progress` without such a session count only in "Gesamt". **Finished series:** every numbered volume of a series group in the library (`Catalog.seriesGroups`) finished; shown in "Gesamt" only (no reliable date). Achievements (5B) build on the same functions.
5. **Periods:** Woche, Monat, Jahr, Gesamt, each with previous/next navigation (not into the future); default Woche.
6. **Navigation:** a full-screen `Stats` route (not a fifth tab), opened from a Start card and from Settings („Statistik“).
7. **Sync cadence:** inside `SyncWorker` like `collectionSync.syncIfDue` (throttle 30 min), plus an unthrottled refresh when the Stats screen opens while online. First run fetches everything (100 per page, safety cap 500 pages); later runs stop at the first page whose sessions are all older than the stored stamp minus 24 h (open sessions keep growing). Stamp `listening_sessions:<serverId>` = newest `updatedAt` of a completed run.

## Files
- abs-api: `me/MeApi.kt` (+ `listeningSessions(itemsPerPage, page)`), `me/SessionModels.kt` (+ `UserListeningSessionDto` with `displayAuthor`, `mediaType`, `duration`; reuse lenient serializers), test in `me/` with an inline JSON page + paging path assertion; stub in `T/ApiStubs.kt`.
- Room: `db/entities/StatsEntities.kt` (`ListeningSessionEntity(serverId, id, itemKey, title, author, startedAt, updatedAt, timeListeningSec, durationSec, currentTimeSec)`, PK (serverId,id), index (serverId,startedAt)), `db/dao/ListeningSessionDao.kt` (`upsertAll`, `observeAll(serverId)`, `newestUpdatedAt(serverId)`), registration per checklist, `app/schemas/.../9.json`, `MigrationTest.v8ToV9…`, `AccountDataDao` + `SERVER_TABLES`.
- Sync: `sync/ListeningSessionSync.kt` (`sync(serverId)`, `syncIfDue(serverId)`), hook in `SyncWorker`, `SyncTrigger`/VM entry for the on-open refresh.
- Logic: `stats/ListeningStats.kt` (pure: `merge(server, local)`, `days(...)`, `periodSummary(period, anchor, zone)`, `streaks(...)`, `finishedBooks(...)`, `finishedSeries(...)`, `topTitles/topAuthors`).
- UI: `ui/stats/StatsScreen.kt`, `ui/stats/StatsViewModel.kt`, `ui/stats/BarChart.kt` (Canvas; content description lists the values), route in `Destinations.kt` + `AppNavHost.kt`, Settings entry, Start card (`ui/start/` – a compact card under the hero: "Diese Woche 5 h 12 min", streak, a 7-day sparkline; tap → Stats; hidden when nothing was listened in the last 30 days).
- Strings DE/EN incl. plurals (Tage, Bücher, Serien), whats-new 0.3.0, version bump.

## Tasks (each: failing test → code → green → commit)
1. **API**: `listeningSessions` + DTO + MockWebServer test (path `api/me/listening-sessions?itemsPerPage=100&page=0`, lenient rows, string numbers). Stub in StubMeApi.
2. **Room v9**: entity, DAO, migration, sign-out registration; tests: MigrationTest v8→v9 keeps data and the new DAO works; AccountDataTest stays green (guard), DAO upsert/observe/newest.
3. **Sync**: `ListeningSessionSync` with fake MeApi: first run pages until `numPages`; incremental run stops at an old page (overlap 24 h); stamp only after a complete run; offline/no account → nothing; device server id skipped; SyncWorker calls `syncIfDue` best effort (a failure never fails the worker).
4. **ListeningStats (pure)**: tests for merge/dedupe (local unsynced wins, synced → larger time), day bucketing in a fixed zone (Europe/Berlin) incl. a DST change day and a session across midnight, week start Monday, period totals and previous/next anchors, streaks (today counts / today empty keeps yesterday's streak / gap breaks / longest), 60 s threshold, finished books by session (0.995) vs progress-only (only Gesamt), episodes never count as finished books, finished series from catalog groups, top titles/authors (multiple authors split like `Catalog.splitPeople`).
5. **Stats screen + VM**: VM test (period switch, previous/next bounds, refresh on open only when online), Compose tests (empty state; numbers, chart content description, top titles; large font no clipping smoke). Follow the polish package's look (tokens/spacing/cover tiles; tint not needed here).
6. **Entry points**: Settings „Statistik“ row, Start card (shown/hidden rule test), version 0.3.0 + whats-new lines (WhatsNewResourcesTest green).

## Review Focus
- A heavy listener's first sync (thousands of sessions): paging cap, memory (do not hold all DTOs at once – upsert per page), time.
- Sessions that keep growing (open session) must update, not duplicate.
- Time zone/DST and locale week start produce the same day buckets as a human would expect.
- Sign-out: the new table and stamp are purged/kept exactly like other account data.
- Stats screen offline with an empty cache: empty state, no spinner forever.

## Implementation notes (2026-11-04)

All six tasks are done on `phase-5a` (TDD, one commit per task plus three fixes found while verifying). Version 0.3.0
(code 3) with "Was ist neu" lines for the UI polish and the statistics.

**Built**
- **API**: `MeApi.listeningSessions(itemsPerPage, page)` → `UserListeningSessionsPage` / `UserListeningSessionDto`
  (media type, display title/author, `mediaMetadata.title`, flexible numbers, broken rows dropped). Verified on the
  local ABS 2.37.1: 0-based pages, newest `updatedAt` first (also newest `startedAt`).
- **Room v9**: `listening_sessions` (`ListeningSessionEntity`, PK serverId+id, index serverId+startedAt), AutoMigration
  8→9, schema 9.json, `MigrationTest.v8ToV9…`, registered as account data (AccountDataDao delete/hasRows/SERVER_TABLES).
  `SessionDao.observeAll(serverId)` for this device's sessions.
- **Sync**: `ListeningSessionSync` (singleton, runs serialized) – see "Decisions" for the run types; `SyncWorker` calls
  `syncIfDue` (30 min) after the progress sync, best effort; the Stats page calls `sync` once on open after the
  connection check said online.
- **Logic**: `stats/ListeningStats` (pure) + `StatsSessions` (Room flows, merged).
- **UI**: `ui/stats/StatsScreen` + `StatsViewModel` + `BarChart` (Canvas), route `Stats` inside `ReadableWidth`;
  Start card (`StartViewModel.statsCard`, `StartScreen(stats, onOpenStats)`); Settings row „Statistik“.

**Decisions beyond the plan (Claudes Wahl, for review)**
1. *Later runs ask for one session first* (`itemsPerPage=1`): same newest `updatedAt` and same `total` = nothing
   changed, the run ends after that request. Session JSON is large (≈1.4 KB per session on the test server without
   chapters or descriptions; real books carry both), so re-reading the last 24 h every 30 min would have cost megabytes
   a day. Later runs page with 20, full runs with 100.
2. *Stop rule*: a later run stops after the first page that reaches sessions older than newest − 24 h (one page
   earlier than "all older"; the order is by `updatedAt`, so nothing below can be newer).
3. *Late uploads and deletions*: ABS keeps the `updatedAt` a device uploads (checked: a session from three weeks ago
   uploaded now sorts last), so the 24 h overlap alone would never reach sessions an offline device uploads days later.
   After a later run the cached row count is compared with the server's total; when they differ a **full run** follows,
   at most once per 24 h. A full run drops rows the server no longer lists (deleted there) – after re-reading page 0
   when anything would be dropped, so a session that moved to the top while paging is not taken for deleted – and
   remembers how many listed rows could not be read, so those do not trigger a full run every day.
4. `newestUpdatedAt` in the DAO was not needed (the stamps are the authority: only complete runs move them); the DAO has
   `count`, `ids`, `delete` (chunks of 500) for the reconcile instead. Stamps: `listening_sessions[_total|_unreadable|
   _full|_run]:<serverId>`.
5. An episode's row carries its podcast's title; top titles group episodes under their podcast (by library item id).
   Names come from the catalog where it knows the item, else from the session.
6. A finishing session also needs listening time > 0 (a 0 s session opened at the end is no finish). A finished series
   needs at least two numbered volumes (a single numbered volume is mostly a series the library holds one book of).
7. The Start card sits below the whole "continue" block (hero + its row), shows the current week (locale week start,
   today in primary) and hides when less than 60 s were heard in the last 30 days (a few seconds by accident do not
   bring it back).
8. Settings: the row sits right under the account rows (with a bar-chart icon like them), no section of its own.
9. A period switch starts at that period's current one; previous is bounded by the first day with a session.
10. Streak/record tiles show the streaks as of today in every period; „Beendet“ counts the period's books, in „Gesamt“
    also the finished series.
11. „Server nicht erreichbar“ is only said in the empty state; with data the app's offline banner already says it.
12. Bars: neutral `outline`, the current day/month/year in `primary`; month bars labelled 1/8/15/22/29; the month
    chart's description names only days with listening. Tiles side by side in one height, one below the other when a
    word would have to break (large text).

**Verification**
- `timeout 1500 ./gradlew testDebugUnitTest :abs-api:test lintDebug assembleDebug -q` green (see the report for counts).
- Emulator (AVD lauschig35, signed in to the local test ABS): what's new 0.3.0, Start card, Stats week/month/year/all,
  previous/next bounds, an empty week (a test session from 14 Oct was added to the server for this and removed again
  afterwards), Settings entry, German (Monday week, „2.–8. Nov. 2026“), 200 % font (tiles stack, nothing cut),
  dark/light, tablet mimic portrait/landscape, offline (airplane mode; server stopped) shows the cache without a
  spinner, sign-out without keeping purges rows + stamps, with keeping both stay; the app is signed in again.
  The numbers matched a script over the server's sessions (week 56 min on 2 days, Der Anfang 26 min, Die Mitte 25 min…).

**Open / for Fabian**
- A heavy listener's first sync downloads every session's full JSON (with chapters and metadata) – on mobile data too,
  like the other syncs. ABS also loads all of a user's sessions for every page it serves.
- The empty-cache-offline state was checked in tests only (on the emulator the first sync after signing in was done
  before the server could be stopped).
- Sessions deleted on the server that this device recorded itself keep counting (they are in `local_sessions`).

## Fix round 1 (review 2026-11-04, controller's rulings)

- **F1** Finished series of the same name in two libraries no longer crash "Gesamt": `FinishedSeries.key` = library +
  name; two entries (as `Catalog.seriesGroups` keeps them).
- **F2** Full runs (first sync, an empty cache, an interrupted run, the reconcile) wait for an **unmetered** network;
  later runs work on any network. A full run stores its place after each page (`listening_sessions_resume_page/
  _resume_total:<sid>`) and continues with one page of overlap, or starts over when the total moved by more than a page.
  250 per page. While the history is incomplete on a metered network the page says „Die vollständige Hörhistorie wird
  im WLAN geladen“ (`ListeningSessionSync.historyLoad`).
- **F3** Reconcile only when the server lists more sessions than this device can know: cached rows ∪ this account's
  uploaded `local_sessions` (+ the unreadable count). Deletions start no full run; the next full run drops them.
- **F4** A full run drops rows only after reading the whole list in one go, every listed row readable and the total
  unchanged while reading (plus the head re-read). The unreadable count is the run's own; it brings no daily full run but
  a retry after 7 days.
- **F5** An empty cache is a first sync whatever the stamps say (unless the server had none). The page's refresh runs
  per signed-in account (`collectLatest`): sign-out or another account cancels it.
- **F6** `SyncWorker` runs the statistics sync after the episode auto-downloader.
- **F7** A later-run page that is not newest-`updatedAt` first becomes a full read instead of an early stop (logged once).
- **F8** Capped runs move no stamps; a capped full run continues from its stored place.
- **F9** Tiles: the period that holds today shows „Am Stück“ + „Rekord“; a past period its „Längste Serie“ + „Hörtage“.
- **F10** Finished-book dates show the year outside the current year and always in „Gesamt“, in the app's locale.
- **F11** `StatsCalendar` (today, zone, week start) is read again at midnight, on time/zone/language broadcasts and on
  resume; the week start honours Android 14+'s regional preference via `LocalePreferences`, else `WeekFields`.
- **F12** The Start card reads the last 30 days (`observeSince` on the (serverId, startedAt) index; a longer streak reads
  older days once, 90 at a time); both screens conflate Room bursts and compute off the main thread.
- **F13** The current bar has a dot below it and a bold label; plain bars use `outline` only where it keeps 3:1 on the
  surface; the description says "(heute)/(dieser Monat)/(dieses Jahr)" and „Keine Hörzeit“ for an empty period.
- **F14** „Hörhistorie wird geladen …“ while a full run reads (no blocking spinner once there is data).
- **F15** `isCurrent` removed (the tiles model replaces it); the index stays (used by F12).
- **F16** Meaningful DAO re-emission and previous/next tests; tests for the series collision, all seven stamp keys,
  capped and unreadable pages, a fixed calendar for the Start card.
- **F17** KDoc and comments match the code (sync rules, worker order, `firstDay` may lag one emission).
- **F18** Was ist neu 0.3.0 also names Android Auto covers, the tablet panes/layouts and the previous account's downloads.

Verification of fix round 1: full `testDebugUnitTest :abs-api:test lintDebug assembleDebug` green (1353 tests, lint 0
errors). Emulator: the current bar's dot and bold label (light, dark, Start card), past-period tiles („Längste Serie“ /
„Hörtage“, also for an empty week), the Wi-Fi hint on a metered network with the sync stamps cleared (no request, no
stamps), then back on unmetered Wi-Fi the full run ran and dropped the test session that had been deleted from the server
(49 → 48 rows; „Der Anfang“ is now finished on 3 Nov, its first real finishing session). The "loading" line was only
checked in tests (one page on the test server finishes before a screenshot).

## Fix round 2 (re-review of fix round 1)

- **N1** "Unmetered" no longer depends on the VPN: the default network is NOT_METERED, or it is a VPN (NetBird, how this
  family reaches its server) and a validated, unmetered Wi-Fi or Ethernet exists (`UnmeteredNetwork`). The hint offers
  „Jetzt laden“ / "Load now": one tap reads the whole history once over any network.
- **N2** The gate is asked before every page; leaving the Wi-Fi mid-run stops it at its stored place, without stamps.
- **N3** A resume place counts only while the cache holds the rows the earlier pages imply (less the overlap and the
  unreadable rows); otherwise the run starts over. Sign-out's "rows again" step also removes the account's stamps.
- **N4** The empty-cache rule ignores rows the server lists but the app cannot read (no full download every 30 minutes
  for an all-unreadable account); the unreadable count adds up over a resumed run; unreadable rows are retried after an
  app update, not on a timer.
- **N5** While the history is incomplete on a metered network, a run reads the newest page (recent numbers stay fresh)
  and counts for the 30-minute throttle; the hint follows the stored "incomplete" state (also after a failed or
  cancelled run, in another process), and a routine reconcile on a complete history shows no line.
- **N6** The unsorted-order fallback keeps the daily limit.
- **N7 (accepted, documented)** Sessions deleted on the server leave the cache **only** with a full run that happens
  anyway for another reason (first sync, another device's late upload, an app update with unreadable rows) and that
  reads the whole list in one go with every row readable and the total unchanged. There are no extra runs for
  deletions, so in a steady state a session an admin deleted can keep counting for a long time.
- **N8** The Stats view model collects the calendar once (a shared state); the midnight timer reads the wall clock after
  each step of at most 15 minutes and is re-armed on time/zone/date/locale broadcasts.
- **N9** The Start sparkline's plain bars keep 3:1 against the card's `surfaceContainer` (tested with the accent).

Verification of fix round 2: full `testDebugUnitTest :abs-api:test lintDebug assembleDebug` green (1367 tests, lint 0
errors). Emulator, Wi-Fi marked metered and the sync stamps cleared: the page showed the cache with „Die vollständige
Hörhistorie wird im WLAN geladen“ and „Jetzt laden“; the run had read only the newest page and written only its run
stamp. „Jetzt laden“ then read the whole history over the metered network and the hint went away. A VPN could not be
run on the emulator; the VPN cases are unit tests over the network facts.
