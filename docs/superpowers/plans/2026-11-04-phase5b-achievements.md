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
