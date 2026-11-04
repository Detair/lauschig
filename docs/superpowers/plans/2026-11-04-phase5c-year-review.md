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
