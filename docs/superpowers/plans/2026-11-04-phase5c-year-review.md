# Phase 5C – Year in review („Jahresrückblick“) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** A story-style year in review in the spirit of Spotify Wrapped: full-screen slides with the year's listening, built offline from 5A/5B data.

**Architecture:** A pure `YearReview.build(year, …)` turns merged sessions, finished books/series and achievements into a list of slides (only those with data); a `YearReviewScreen` shows them as a story pager. No new storage.

**Spec:** §2.6 („Jahresrückblick im Stil von Spotify Wrapped als Story-Ansicht“). Builds on 5A + 5B. Decisions marked **Claudes Wahl**.

## Slides (Claudes Wahl; skipped when empty; order fixed)
1. Opening: „Dein Hörjahr 2026“ – total hours, big.
2. Days: active days and the longest streak of the year.
3. Rhythm: the top month (bar of 12 months) and the favourite weekday / time of day („meist morgens auf dem Weg“ if commuter hours dominate).
4. Top titles: the 5 most listened titles with covers (cover collage), hours each.
5. Top author (and narrator if known) with hours.
6. Finished: books finished this year (+ series completed), the first and the last finished title.
7. Achievements reached this year (from 5B), up to 6.
8. Personality: one of Nachteule / Frühaufsteher / Pendler / Wochenendhörer / Marathonhörer by the strongest pattern, with one line why.
9. Closing card: the summary in one view.

## Behaviour
- Pager with segmented progress bars at the top; auto-advance after 7 s per slide, tap right/left = next/previous, long-press pauses, swipe down or X closes; TalkBack: no auto-advance, slides are announced, buttons for next/previous. Respect "remove animations" (no auto-advance animation, still navigable).
- Backgrounds: the cover tint (polish package) of the slide's lead title where there is one, otherwise the theme's container colours; large type (displaySmall/Medium for the number, bodyLarge for the line). No confetti, one entrance motion per slide (number counts up once, disabled when animations are off).
- Entry points: the Stats screen in „Jahr“ mode shows „Jahresrückblick 2026 ansehen“ for any year with ≥ 1 h; the Start screen shows a card from 1 Dec to 31 Jan for the closing year (dismissable per year).
- Share: not in this package (Claudes Wahl – later: render the closing card to an image).

## Tasks
1. Pure `YearReview` with tests (each slide's numbers, skipping empty slides, year boundaries in the device zone, personality ranking ties, first/last finished).
2. Story pager composable (Compose tests: progress segments, tap navigation, pause, close, TalkBack mode without auto-advance).
3. Slide composables + cover collage (tests for content descriptions; screenshot check on emulator light/dark, phone and tablet).
4. Entry points (Stats year mode button; Start card date window + dismiss per year – VM test with a fixed clock).
5. Strings DE/EN, whats-new lines.

## Review Focus
- A year with very little data must not produce odd slides (e.g. "Top month: 2 min").
- Auto-advance must stop when the app goes to the background and must never fight TalkBack.
- Tablet landscape: slides centred with a max width, not stretched.

## Implementation notes (2026-11-04)

All five tasks are done on `phase-5c` (stacked on `phase-5b`), TDD, one commit per task plus one fix commit from the
emulator check. Version stays 0.3.0; "Was ist neu" got two lines (the review; where it is offered).

**Built**
- **Pure** `stats/YearReview.kt`: `YearReview.build(year, sessions, finished, seriesGroups, achievements, items, zone,
  incomplete)` → `YearReview(year, slides)` or null below an hour; `ReviewSlide` (Opening, Days, Rhythm, TopTitles,
  TopPeople, Finished, Achieved, Personality, Closing), `ListeningTime`, `ListenerType`, `ReachedTier`;
  `offeredYear(today)` (December → this year, January → last year), `yearTotal(days, year)`.
- **Story pager** `ui/review/StoryPager.kt`: `StoryPager`, `StoryState` (+ Saver), `StoryMotion` /
  `rememberStoryMotion()` (TalkBack touch exploration and the animator scale, both followed live), `SlideColors`.
- **Slides** `ui/review/ReviewSlides.kt` (one composable per slide, `reviewSlideDescription`, `reviewSlideColors`, the
  cover collage), page `YearReviewScreen` + `YearReviewViewModel` (route `YearReviewPage(year)`).
- **Entry points**: Stats „Jahr“ → „Jahresrückblick 2026 ansehen“ (`StatsUiState.reviewYear`); Start card
  `YearReviewCard` + `YearReviewCardViewModel`.
- The statistics' catalog read (`CatalogDao.observeForStats`) now includes the narrator.

**Decisions beyond the plan (Claudes Wahl, for review)**
1. *Floors per slide* (so a thin year never says "Top month: 2 min"): review from 1 h in the year; days slide from
   3 active days, the streak line from a run of 2; rhythm needs 2 months with ≥ 60 s and 1 h in the strongest month,
   weekday and time of day need 5 active days; a top title needs 10 min (5 at most); author and narrator 30 min;
   personality 5 h on 5 days. Ties: the earlier month, the earlier weekday from Monday, the types in their listed order.
2. *Personality*: the share of the year's time each pattern holds, divided by its own threshold – night owl 30 %
   (sessions started 22:00–04:00), early bird 25 % (04:00–07:00), commuter 40 % (weekday sessions started in the
   achievement's commute windows 6–9 / 16–19), weekend 50 %, marathon 40 % (days with ≥ 3 h); the highest score ≥ 1
   wins, none → no slide. The line says the share (or the marathon days) and the window from the rule constants.
3. *Time of day*: "on the way" (morning or homeward) when the weekday commute windows hold half of the time, else the
   part of the day with the most time (5–12, 12–17, 17–22, 22–5), by session start.
4. *Books* count by their dated finish (as the Stats year); *series* and *achievements* by the day the achievements
   give them (a series is complete on its last volume's day, undated books by their progress day). Achievements: all
   tiers reached in the year count; the slide shows each achievement once (its highest tier of the year), newest
   first, six at most.
5. *Narrator* from the catalog (sessions name none); left out when it is the top author.
6. *Built once*: the page reads Room once when it opens (no live update under the reader; offline-first, no refresh of
   its own). The place in the story lives in the view model (see the fix below).
7. *Manual mode* = TalkBack on **or** animations removed: no auto-advance, the current segment shown full,
   previous/next buttons with "3 / 9"; the slide is one live-region item ("Folie 3 von 9. …") with custom actions and
   a double-tap that goes on. "Remove animations" also drops the count-up and the cross-fades. The slide time honours
   Android's "time to take action" (`calculateRecommendedTimeoutMillis`).
8. *Taps*: the left third of the window is back, the rest next (mirrored RTL); the first slide's "back" restarts its
   time; the last slide stays (closing is the X, a swipe down or Back). A held press pauses until release (a long
   press never navigates). The timer runs on frame time, not an animation, and only while the screen is resumed.
9. *Colours*: opening/closing primary container, days/achievements secondary, rhythm/personality tertiary; title slides
   the cover tint over `surfaceContainerHigh`. All text in the slide's "on" colour; the month bars use the slide's
   colour too (`quietBarColor` keeps 3:1). The status/navigation bar icons follow the slide's text colour while the
   story is shown. The frame hides tabs and mini player on the review route; the backdrop reaches under the status
   bar and (new `BackdropReach.bottom`) the navigation bar.
10. *Start card*: below the stats card, primary container, "hidden for this year" stored as the sync stamp
    `year_review_hidden_<year>:<serverId>` (purged by a sign-out without keeping; kept with keeping).
11. *Share* not built (as planned).

**Verification**
- `timeout 1500 ./gradlew testDebugUnitTest :abs-api:test lintDebug assembleDebug -q` green: 1492 tests (47 of them in
  `YearReviewTest` and `ui/review`, a few more in Stats, Start and the backdrop), lint 0 errors, 15 warnings (none new;
  one run hit the known lint crash in `ApiStubs.kt`, the rerun was clean).
- Emulator (lauschig35, local test ABS): the real 2026 (56 min) offers no review; with 261 test sessions spread over
  2026 on the local server (and the app's sync stamps reset for a full read) all nine slides in light, dark, German,
  200 % font, phone portrait/landscape and tablet mimic portrait/landscape; auto-advance, tap next/back, hold, the
  background and a system dialog pause it; X, swipe down and Back close; remove animations (manual mode); TalkBack
  (manual mode switched on live, the slide text is what TalkBack speaks); the Start card on 3 December (clock moved),
  open and hide (stays hidden after a restart). Afterwards the test sessions were deleted from the server (48 again,
  progress unchanged), the app's rows, achievement rows and sync stamps restored, every emulator setting reset.

**Fix from the emulator check**
- Rotating switches the frame between tab bar and navigation rail, which composes the nav host along another path:
  a page's `rememberSaveable` state does not come along (all pages, also before this phase). The story's place now
  lives in `YearReviewViewModel`; `rememberStoryState` saves under a fixed key.
- Android 16's dynamic schemes have mid-tone containers with light "on" colours: the month bars and the status bar
  icons now follow the slide's own text colour instead of the theme's neutrals.
- The sides beside the centred story (tablets) are tap areas too.

**Open / for Fabian**
- Rotation between tab-bar and rail layouts loses every page's saved UI state (scroll positions …) – pre-existing,
  app-wide; worth a look in the frame (one stable path for the nav host).
- The "history incomplete" line was checked in tests only (a full cache counts as complete on the emulator).
- TalkBack on the emulator kept asking for the Accessibility Suite's notification permission; the speech was checked
  through TalkBack's log (the TTS engine was not ready), not by ear.

## Fix round 1 (review 2026-11-04, controller's rulings)

Rebased onto `main` (fcc9dba, phase 5B merged) first; the tree under the rebased commits was unchanged.

- **F1 + F7** One set of hours: the rhythm's parts of the day (`YearReview.DAY_PARTS`: morning 4–12, afternoon 12–17,
  evening 17–24, night 0–4) and the listener types take the achievements' windows – night owl 0–4
  (`Achievements.NIGHT_WINDOW`), early bird 4–6 (`EARLY_WINDOW`, the start of the morning), commuter 6–9/16–19 on
  weekdays. 4:30 is "morning" and an early bird, never "night"; 23:00 is "evening" and no night owl.
- **F2** The rhythm slide needs the days slide's three days.
- **F3** The opening leaves out "with N titles" at 0 (`OPENING_TITLE_SEC` = a minute); the top titles need two titles
  (`MIN_TOP_TITLES`) – one would only repeat the opening and the closing.
- **F4** The achievements slide counts achievements (each once, at its highest tier of the year), shows six at most and
  says "and N more" beyond them.
- **F5** A series completed in the year by progress-dated books alone still gets the Finished slide (the series is its
  big number then, its last volume's cover the tint).
- **F6** Of the types above their threshold the **largest share** wins; ties in the listed order (six long Saturdays:
  weekend listener, not marathon; long weekdays besides: marathon).
- **F8** The story's slide and played entrances are written to the route's `SavedStateHandle`: after a process death
  it goes on there (the slide's time starts again). The unused `rememberStoryState`/Saver are gone.
- **F9** `rememberStoryMotion` KDoc says what it does (TalkBack live, the animator scale on composition and resume);
  tests for both.
- **F10** Right/left arrows and the D-pad go next/previous (mirrored right-to-left), space pauses and resumes.
- **F11** The pull-down is read in the draw phase; composition only sees whether there is one.
- **F12** The slide time follows Android's "time to take action" (tested through the accessibility manager); only touch
  exploration turns auto-advance off, other assistive services keep it (KDoc).
- **F13** No "next" custom action on the last slide, no "previous" on the first.
- **F14** Spoken numbers and lines use the shown formatter and helpers (`countText`, `titlesLine`, `finishedUnit`).
- **F15** `topPeople` instead of a shadowing `people`; the opening's title floor is a named constant.
- **F16** The 200 % test measures every big number/name (no overflow, not ellipsized) and that a slide scrolls on a
  short window; the Start card says when it has read its data (`YearReviewOffer.known`) instead of a sleep; the
  "built once" test waits for an explicit state change; tests for Sydney's New Year (DST at New Year), Berlin's DST
  days, right-to-left taps and keys, the pull pausing the time.
- **F17** The opening's „Noch nicht alle Hörsitzungen geladen“ follows the account's history live and goes as soon as
  it is complete (the slides themselves stay built once).

Verification: full `testDebugUnitTest :abs-api:test lintDebug assembleDebug` green (1503 tests, lint 0 errors, 15 warnings,
none new; one run hit the known lint crash, the rerun was clean). Emulator:
a thin 2025 (1 h 11 min on two days: opening, author, achievement, closing – no repeated title, no rhythm), the seeded
2026 by keyboard (arrows, space pausing and resuming), its rhythm ("Meist morgens auf dem Weg"), achievements (5 = five
badges) and type (Pendler) slides; screenshots `fx-*` and `contact-fx-*`. Test sessions removed from the server
(48 again, progress unchanged), the app's rows, achievement rows and sync stamps restored; no emulator setting changed.
