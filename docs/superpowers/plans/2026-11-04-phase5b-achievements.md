# Phase 5B – Achievements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Achievements („Erfolge“) computed in the app from the listening data of 5A – each with a date it was reached and progress towards the next tier – shown on a grid screen, previewed on the Stats screen, and announced once when newly reached.

**Architecture:** A pure `Achievements` evaluator replays the merged sessions (5A `ListeningStats.merge`) and the finished books/series (5A functions) chronologically and returns every achievement with `unlockedAt` (or null) and progress. Nothing about unlocking is stored – it is derived and deterministic – except which unlocks the user has already been told about (a small serverId table, purged on sign-out).

**Spec:** §2.6 („Achievements, die darauf aufbauen (z. B. ‚100 Stunden‘, ‚Serie komplett‘, ‚7 Tage am Stück‘). Sie werden in der App berechnet, ein eigener Server ist nicht nötig“). Builds on 5A (merged). Decisions marked **Claudes Wahl**.

## Global Constraints
As 5A (offline first, DE/EN parity, serverId checklist, stamps end with `:<sid>`, Gradle in `timeout`, emulator-5554 only). Room v9 → v10. Append whats-new lines to 0.3.0 (no version bump; 0.3.0 is unreleased).

## Catalogue (Claudes Wahl – ids stable, never renumbered)
| Family | Tiers | Rule |
|---|---|---|
| hours | 1, 10, 50, 100, 250, 500, 1000 h | total listened time (merged sessions) |
| streak | 3, 7, 30, 100, 365 days | longest streak (5A definition, ≥ 60 s/day) |
| books | 1, 5, 10, 25, 50, 100 | finished books (5A: dated ones; undated count too, dated at "first seen") |
| series | 1, 5, 10 | complete series (5A definition) |
| marathon | 1 | ≥ 4 h on one day |
| night_owl | 1 | a session starting 0:00–4:00 local with ≥ 10 min |
| early_bird | 1 | a session starting 4:00–6:00 local with ≥ 10 min |
| weekend | 1 | ≥ 5 h on one Saturday+Sunday |
| one_go | 1 | a book finished within 72 h of its first session |
| commuter | 1 | ≥ 20 weekday sessions starting 6:00–9:00 or 16:00–19:00 (Fabian pendelt) |
| genres | 1 | titles from ≥ 5 different genres listened ≥ 30 min each |
Each has a DE/EN title and one-line description (plain words, sentence case), and a Material icon. Tiers show as one card with the current tier and progress to the next ("37 / 50 Std."). `unlockedAt` = the moment the rule first held during the replay (day precision is enough). Undated finished books (progress only) count from the first evaluation that sees them.

## Notice (Claudes Wahl)
- Table `achievement_seen(serverId, id, tier, seenAt)` (PK serverId,id,tier); sign-out checklist applies; Room v10.
- On the first evaluation for an account, everything already unlocked is marked seen silently (no flood for existing history); afterwards each new unlock shows once as a Snackbar on the Start screen „Neuer Erfolg: 7 Tage am Stück“ with action „Ansehen“ (opens the Achievements screen); several at once → „3 neue Erfolge“.

## UI
- `Achievements` route: grid (adaptive 160 dp) of cards: icon in a tonal circle (unlocked: primary container; locked: surface variant, icon at 40 % alpha), title, description, for tiers a progress bar and "Stufe 3 von 7", for unlocked the date („seit 12. März 2026“). Sorted: recently unlocked first, then by closeness to the next tier.
- Stats screen: a section „Erfolge“ with the 3 most recent unlocks and „Alle (12/31)“.
- Use the polish package's tokens/cover look; no confetti.

## Tasks
1. Catalogue + pure evaluator with exhaustive tests per family (thresholds exact, local time windows incl. DST, replay dates, undated books, episodes never count as books, ties).
2. Room v10 `achievement_seen` + DAO + migration test + sign-out guard.
3. Notice logic (first evaluation silent, then once per unlock) – VM test; Snackbar on Start (Compose test).
4. Achievements screen + Stats preview section (Compose tests: locked/unlocked/tier progress, TalkBack descriptions).
5. Strings DE/EN (+ plurals), whats-new lines.

## Review Focus
- Determinism: the same data must always give the same unlock dates (no "now" leaking into the replay except for undated books).
- A first sync of years of history must not show a flood of notices.
- Time windows use the device zone at the session's start, not UTC.

## Implementation notes (2026-11-04)

All five tasks are done on `phase-5b` (TDD, one commit per task plus three fixes found while verifying). Version stays
0.3.0; "Was ist neu" got two lines (achievements; the notice on Start).

**Built**
- **Evaluator** `stats/Achievements.kt` (pure): the catalogue enum (ids as planned), `evaluate(sessions, finished,
  seriesGroups, items, zone)` → `AchievementState(achievement, reached: List<LocalDate?>, value)` per achievement in
  catalogue order, `sorted`, `unlockedTiers`, `TOTAL_TIERS` (28), `AchievementsPreview`. 5A's `ListeningStats` gained
  what the families need, with tests there: `FinishedBook.finishedAt` (moment of the finish) and `.progressDay`,
  `runLengths(days)` (streaks uses it), `finishedSeriesGroups` (finishedSeries uses it).
- **Room v10** `achievement_seen(serverId, id, tier, seenAt)` (PK serverId+id+tier, insert-or-ignore keeps the first
  time), AutoMigration 9→10, `10.json`, `MigrationTest.v9ToV10…`, sign-out checklist (AccountDataDao delete +
  deleteServer + hasRows + SERVER_TABLES).
- **Shared pipeline** `stats/ListeningData.kt`: `ListeningDataSource` (was the Stats view model's private pipeline:
  merged sessions, days, finished books and series, catalog; conflated) – used by Stats, Achievements and the notice.
- **Notice** `AchievementNotices.step` (pure) + `AchievementNoticeViewModel` + `AchievementNoticeSnackbar` on Start
  (the app's snackbar host is handed to `StartRoute` through `AppNavHost`).
- **UI** `ui/achievements/AchievementsScreen` (+ view model, texts, badge), route `Achievements` inside `ReadableWidth`,
  the Stats section „Erfolge“, `SeeAllButton(label, description)`.

**Decisions beyond the plan (Claudes Wahl, for review)**
1. *Undated books are dated by their progress's `lastUpdate`* (the day in the device zone), not by "the first
   evaluation that sees them": the same data gives the same day without storing a first-seen per book, and when a book
   is first seen its `lastUpdate` is never later than that moment. A progress without a time (`lastUpdate` ≤ 0) still
   counts, its tier is reached without a day („Erreicht“). No "now" enters the evaluation at all.
2. *Baseline*: the first evaluation waits until the account's history is complete (`HistoryLoad.NONE`) **and** the
   progress was synced once (`progress_sync:<sid>` stamp), then writes every reached tier plus a `_baseline` row
   (tier 0) silently – so a new listener with nothing reached still gets their first notice. After the baseline every
   new tier is told whatever the history does (an interrupted reconcile only brings genuine listening). Every data
   change is a new look (fix `39fe67a`: a progress sync that changes no tier must still settle the baseline).
3. *Shown once* = the snackbar ended (dismissed, timed out after `Long`, or „Ansehen“). Leaving Start while it shows
   brings it back next time; a newer notice replaces it; a late end for an account that is no longer signed in writes
   nothing. Notices appear only on Start (as planned); with another start tab they wait for a visit to Start.
4. *Rules the plan left open*: commuter sessions need 10 minutes like night owl / early bird, Monday–Friday, start in
   [6:00, 9:00) or [16:00, 19:00); genres count a title (a book, or a podcast with its episodes) once heard 30 minutes,
   its catalog genres trimmed and case-insensitive; weekend = a Saturday plus the Sunday after it (held on Saturday when
   Saturday alone is enough); one go = a book's first finish within 72 h of its first session with listening; a
   series is complete on the day its last volume was finished (no day when a volume has none); the streak's progress is
   the longest run (the rule's measure), not the current one.
5. *Cards*: the title is the reached tier's name („7 Tage am Stück“; a locked card names its first goal) – the notice
   says the same words; „Stufe 2 von 5“ only for a reached tier; progress = value of the next threshold („37 / 50
   Std.“, under an hour „20 Min. / 1 Std.“, marathon/weekend as durations); then „Seit 12. März 2026“ / „Erreicht“ /
   „Noch nicht erreicht“. TalkBack: one merged item reading the lines in order, the progress said „37 von 50 Std.“
   (German dative: „12 von 30 Tagen“, „3 von 5 Büchern“), the bar itself silent.
6. *Grid*: columns of at least 160 dp, but never narrower than the widest single word on any card (fix `2758556`: at
   200 % in German two columns broke „Frühaufsteher“); each row as tall as its tallest card, the
   status lines at the bottom so they line up. Up to four columns inside `ReadableWidth` on tablets.
7. *Order*: unlocked by the current tier's day (newest first; reached without a day after those), ties by closeness
   (a complete one counts as 1), then catalogue order; locked by closeness, then catalogue order.
8. *Stats section* under the tiles (not tied to the period, in every period): the three latest as rows with a 40 dp
   badge, „Alle (7/28)“ (said „Alle Erfolge, 7 von 28 erreicht“), „Noch keine Erfolge“ without any. „Alle (x/y)“
   counts tiers: 28 in all (the plan's 31 was an example).
9. *Names*: Pendelzeit/Commute (not „Pendler“), Genre-Vielfalt/Genre explorer, Hörwochenende/Listening weekend, In
   einem Rutsch/In one go, Frühaufsteher/Early bird, Nachteule/Night owl, Marathon; EN "Level 2 of 5". Icons from
   Material Icons Extended, Rounded (the project's set): Schedule, LocalFireDepartment, AutoStories, LibraryBooks,
   DirectionsRun, NightsStay, WbTwilight, Weekend, Bolt, Commute, Category.

**Verification**
- `timeout 1500 ./gradlew testDebugUnitTest :abs-api:test lintDebug assembleDebug -q` green: 1427 tests (54 new), lint
  0 errors and no new warnings (one run crashed inside lint's Kotlin analysis on the untouched `ApiStubs.kt` – the known
  flake from earlier phases; the rerun was clean).
- Emulator (lauschig35, local test ABS): update over 0.3.0 migrated Room 9→10 in place; the first evaluation marked
  books 1, one go and series 1 silently (no snackbar); Stats section and grid (light/dark, EN/DE); test sessions posted
  to the local server one at a time brought „New achievement: 1 hour listened“, „2 new achievements“ (night owl + 3 days
  in a row, „View“ opened the grid) and „Neuer Erfolg: Frühaufsteher“, each once (a cold start told nothing again);
  200 % font in German (one column, no word broken, after the fix); tablet mimic 800x1340 @213 portrait/landscape,
  light/dark. Afterwards the three sessions were deleted from the server (48 again), the three titles' progress values
  restored, the emulator's cache pruned by a full run, the four test rows removed from `achievement_seen`, and every
  emulator setting reset.

**Open / for Fabian**
- Start now reads the account's whole history while it is shown (merge + evaluation per Room change, conflated, off the
  main thread) – 5A kept Start to the last 30 days. Fine for the family's sizes; a very long history re-evaluates on
  every 10 s tick while a title plays. Moving the evaluation into the sync and storing a pending notice would undo that.
- Achievements are derived, so they can go away: sessions deleted on the server (once a full run prunes them), a
  finished mark removed, a series that gets a new volume. Reached again, they are not told again.

## Fix round 1 (review 2026-11-04, controller's rulings)

The branch was rebased onto `main` (17a3f35, phase 5A merged) first; the tree under the rebased commits was unchanged.

- **F1** The silent baseline is taken from a fresh read and evaluation once the account is settled, not from the
  flow's latest evaluation: the full sync flips "history complete" right after writing its last page, and the flow
  could still hold the evaluation before that page (reproduced: 5000 sessions written and the flag flipped 0 ms later
  gave a baseline of `_baseline` alone and 14 tiers told). Test `aFirstSync_whoseLastPageAndTheCompleteFlagComeBackToBack…`.
- **F2 (accepted)** There is no zone per session: time windows and days are read in the device's current zone. After
  the baseline, a zone change (a trip) can reach a zone-dependent tier – night owl, early bird, commuter, and the days
  of a streak, marathon or weekend – with old listening; it is told once like any other new tier.
- **F3 (accepted)** A book only the progress marks finished counts on the day of its progress's `lastUpdate`; a later
  progress write (played again, a position moved on another device) moves that day – and the „Seit …“ of the books or
  series tier it carries – forward, and an undated book can change which day the N-th book has. Whether a tier is
  reached, and so the notice, never depends on it. (Also in the catalogue KDoc.)
- **F4** The notice never takes Start down: a failing evaluation, read or write logs and tells nothing; a failed step
  is tried again with the next change; a failing flow ends with no notice (tests with a throwing DAO).
- **F5** The history is evaluated again only when something a tier depends on moved (`Achievements.tierInputs`: tier
  counts of the total, longest run, ten-minute sessions, sessions and their ids, finished books, series, titles heard
  30 minutes, catalog, zone) – a playback tick on a day that counts already is not; every change still settles a
  waiting baseline. A random test checks that equal inputs always mean equal reached tiers. The statistics read only
  the catalog columns they use (`CatalogDao.observeForStats`, no description).
- **F6** Tiers are append-only (KDoc next to the ids); a test pins every family's id and thresholds.
- **F7 (comment fixed, accepted)** After the baseline a tier can also be reached with older data (reconcile, a late
  upload, a series edited on the server, F2) – told once all the same. A snackbar that times out in the background
  counts as told. An account whose history never completes (metered only, every full read failed) gets no baseline and
  so no notice.
- **F8** The notice tests wait in real time for counted evaluations (no virtual `delay`); "shown is shown" looks again
  at a new session and could fail; the misnamed test is `anExistingBaseline_isKept_andATierReachedSinceIsTold`; DST
  tests for commuter, weekend and marathon; a Compose test runs `StartRoute` with the notice view model and the app's
  snackbar host (fails if „View“ is not wired).
- **F9** Descriptions take minutes, hours, counts and the time windows from the rule constants (times in the locale's
  format: „zwischen 00:00 und 04:00 Uhr“, "between 12:00 AM and 4:00 AM"); `tiered` = counts towards a threshold (all
  but night owl, early bird, one go), `leveled` = more than one tier („Stufe x von y“); rows and cards keyed by
  achievement id; the route is `AchievementsPage`; tier 0 is documented as the baseline row's; `sorted`'s KDoc fixed.
- **F10** The achievements title is a semantics heading; the Stats rows are buttons with „Erfolg ansehen“ / "View
  achievement".

Verification: full `testDebugUnitTest :abs-api:test lintDebug assembleDebug` green (1440 tests, lint 0 errors, no new
warnings). Emulator: the update showed no notice; a 5-minute test session on the local server brought „New
achievement: 1 hour listened“ once, „View“ opened the grid with the new descriptions (`shots/achievements/fx1-…`);
afterwards the session was deleted, the title's progress restored, the cache pruned and the test row removed from
`achievement_seen`.
