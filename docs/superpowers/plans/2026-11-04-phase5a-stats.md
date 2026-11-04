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
