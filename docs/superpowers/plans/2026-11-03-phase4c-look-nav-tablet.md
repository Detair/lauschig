# Phase 4C – Optik, Navigation, „Was ist neu“, Tablet/Foldable, UX-Funde (Plan)

Scope: spec 2.7a (Optik, Navigation anpassbar, „Was ist neu“, Tags im Filter), 6a (Layouts für Handy, Tablet und
Foldable) and the findings of the device tests. Global constraints of the phase-1 plan apply (strings in `values/` and
`values-de/`, offline first, nothing on a start path awaits network I/O). 4A/4B behaviour stays (car, voice, widget,
links, Verbindungsstatus, admin menu); `CarBrowseTest` must still pass.

## 1. UX findings

| Topic | Decision |
|---|---|
| Player buttons below the fold | The player becomes a fit layout instead of one long scroll column: top bar, cover, title block, slider, controls and a compact action area. The cover takes the height that is left (96 dp … 280 dp, larger on tablets); only when even the smallest cover does not fit (very large fonts) the screen scrolls. The three button rows (speed/chapters, sleep/audio, bookmarks/queue/history) become one compact action area of tonal icon buttons with short labels: Tempo · Kapitel · Timer · Audio, then Lesezeichen · Warteschlange · Verlauf. Checked at 1080×2400 @ 420 dpi and 720×1280 @ 320 dpi. |
| "Downloaded" for device titles | `SourceIndicator` gets a third state: titles with a `local:` key show a phone icon and „Auf dem Gerät“ (player label, mini player content description) instead of „Heruntergeladen“. |
| Tags in the library | Missing: the library only filters by kind; search and the entity pages know tags. The library gets a tag filter chip next to the kind chips (dropdown with the tags of the tab's titles and their counts, „Alle Tags“ resets; ViewModel state, not persisted). Discover gets a „Tags“ row next to „Genres“ (opens the tag page). |

## 2. Optik (Einstellungen → Darstellung)

| Topic | Decision |
|---|---|
| Store | `settings/UiSettings` – own DataStore `ui_settings` (testable with an injected `DataStore`): theme mode (SYSTEM / LIGHT / DARK), pure black (default off), dynamic colors (default on, the current behaviour), font scale percent (85/100/115/130, default 100), tab layout (§3), last seen version (§4). |
| Color scheme | `LauschigTheme(appearance)`: pure function `colorSchemeFor(dark, pureBlack, dynamic)` (dynamic only on API 31+, below it the setting is hidden). Pure black only in dark mode: background, surface, surfaceDim, surfaceContainerLowest = #000000; the other surface containers are lifted (blended towards black from the scheme's own values, so cards and the mini player stay visible). |
| Where it applies | `AppRoot` already wraps everything, including dialogs, sheets and menus (M3 colors travel through composition locals into their windows). Status/navigation bar icons: `MainActivity` re-runs `enableEdgeToEdge` with `SystemBarStyle.auto(…) { dark }` whenever the effective theme changes. The splash and the window background keep following the system (documented). |
| Font scale | Not via `LocalDensity` (dialogs, sheets and popups get their own density from their window, and the system's non-linear font scaling on Android 14+ would be lost): `MainActivity.attachBaseContext` applies `applyOverrideConfiguration(fontScale = system × setting)`. The value is mirrored from DataStore into a tiny synchronous cache (`FontScaleBoot`, SharedPreferences, read before the first frame); when the DataStore value differs from the applied one, the activity writes the cache and `recreate()`s (state and back stack are restored). The settings screen is the live preview, plus a sample line. |
| Splash | Held until the local account read and the first appearance read are done (both DataStore, a few ms), so the first frame has the right theme. |
| UI | Settings section „Darstellung“ after „Wiedergabe“: Design (dropdown), Reines Schwarz (switch, disabled when the effective theme is light), Dynamische Farben (switch, API 31+ only), Schriftgröße (four choices + sample), „Navigation anpassen“ (§3). |

Tests: `UiSettingsTest` (defaults, persistence, unknown values), `ColorSchemeTest` (pure black only when dark, containers
lifted and ordered, dynamic ignored below 31), `FontScaleTest` (cache read/write, applied scale = system × setting).

## 3. Navigation anpassbar

| Topic | Decision |
|---|---|
| Model | `ui/nav/TabLayout` (pure): order of the four tabs, hidden tabs, start tab. Normalised on read (unknown names dropped, missing tabs appended in default order, duplicates removed). Start can never be hidden; at least 2 tabs stay visible; the start tab must be visible, else it falls back to Start (also when the current start tab gets hidden). |
| Reorder | Up/down icon buttons per row (labelled „‹Tab› nach oben/unten“, disabled at the edges) – accessible without drag gestures. Switch per row for visibility (disabled for Start and when it would leave one tab). Dropdown „Startbildschirm“ over the visible tabs. „Zurücksetzen“. Own route `NavigationSettings` (Settings → Darstellung → Navigation anpassen). Changes show at once in the bottom bar / rail. |
| Start screen | `MainViewModel` decides the start destination from the local account read and the tab layout (Setup without an account, else the start tab's route). The NavHost's graph start is that route, so "back" from another tab returns to it and then leaves the app. Intents keep their target: widget (`open=player`), links (item detail), cleanup notification and the media notification navigate on top of the start screen; voice (`MEDIA_PLAY_FROM_SEARCH`) only starts playback as before. The media notification's session activity now carries `open=player` so it opens the player (as the widget does). |

Tests: `TabLayoutTest` (normalise, hide rules, start fallback, moves at the edges), `MainViewModelTest` (start destination
from the layout; Setup without account), `AppScaffoldTest` (tabs in the stored order, hidden ones missing).

## 4. „Was ist neu“

| Topic | Decision |
|---|---|
| Content | `whatsnew/WhatsNew.kt`: list of releases (`versionCode`, `versionName`, string-array resource). 0.2.0 (versionCode 2) summarises phases 3 and 4 in short user-facing lines, German in `values-de`, English fallback in `values`. |
| When | `releasesToShow(lastSeen, current, updated, releases)` (pure): nothing on a first install (no stored value and the package was never updated → store the current code); after an update releases with `lastSeen < code ≤ current` (no stored value but the package was updated = an update from 0.1.0 → `lastSeen = 1`); nothing when up to date or downgraded. Shown once as a bottom sheet (not on the Setup screen); dismissing stores the current code. "Updated" = `PackageInfo.firstInstallTime != lastUpdateTime`. |
| Also | Settings → Über: version (name + code) and „Was ist neu“ (all releases, newest first). |
| Version | `versionName = "0.2.0"`, `versionCode = 2`. |

Tests: `WhatsNewTest` (first install, update from 0.1.0, up to date, several versions, downgrade), `WhatsNewViewModelTest`
(shows once, dismiss stores), resource check that every release has notes in both languages.

## 5. Tablet und Foldable

| Topic | Decision |
|---|---|
| Library | `androidx.compose.material3.adaptive:adaptive` + `adaptive-layout` 1.3.0 (latest stable, the version Compose BOM 2026.09.00 lists) in `libs.versions.toml`. |
| Navigation | Width ≥ 600 dp (medium/expanded, incl. phones in landscape): `NavigationRail` on the start side with the same tabs; the mini player sits at the bottom of the content. Compact: today's bottom bar. |
| Grids | Library, series, discover and entity grids keep `GridCells.Adaptive(120.dp)`, which already adapts the column count (see the notes). |
| List–detail | `ListDetailPaneScaffold` used as the layout only, with a fixed value (list + detail expanded) when `calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())` offers two horizontal partitions (expanded width; hinges of book-posture foldables are avoided by the directive). The detail pane hosts its own nested `NavHost` (empty hint → `ItemDetail` / `Browse` / `Collection`), so the existing routes and their `SavedStateHandle` ViewModels stay unchanged and the outer NavHost is not touched. Used for Library and Search. Opening a title from the list replaces the pane's stack, links inside the pane push. When the window shrinks to one pane (rotation, folding) the pane's current page moves onto the outer back stack. If this fights the NavHost, fallback: a plain `Row` two-pane layout (recorded below). |
| Player | Wide windows (width ≥ 600 dp and wider than high): cover left (as large as the height allows), controls right (scrollable if needed). Tall windows: the fit layout of §1, centred with a maximum width; larger maximum cover on tablets. |
| Readable width | Text pages (settings, details opened full screen, Verbindungsstatus, Server-Verwaltung, downloads, cleanup, setup) are centred with a maximum width on wide windows. |
| Foldables | Free from the library: pane directive avoids a vertical hinge. No tabletop-specific player work. |

Verification: emulator `wm size 2000x1200` + `wm density 240` (Galaxy Tab A7 class) in portrait and landscape, phone
1080×2400 @ 420 and 720×1280 @ 320 dpi; screenshots read for clipped or overlapping UI; `wm size/density reset` after.

## Order and commits

Plan → UX findings → Optik → Navigation → Was ist neu (+ version 0.2.0 / 2) → Tablet/Foldable; one or more commits per
task on `phase-4`. End: `timeout 900 ./gradlew testDebugUnitTest lintDebug assembleDebug -q`, `CarBrowseTest` on
emulator-5554 with `leaveApksInstalledAfterRun`, debug APK reinstalled.

## Implementation notes (2026-11-03)

Decisions made or changed while building it:

- **Player**: the cover/fit layout is a small custom `Layout` (`CoverFitColumn`): header and body are measured first,
  the square cover gets what is left of the viewport (96 … 280 dp, 520 dp on tablet portrait); content taller than
  the viewport scrolls. The source indicator moved into the top bar's free middle, the book time sits between the
  chapter times, and the sleep button says „Timer“ (full name in the description). The full player now **hides the
  bottom bar / rail** – it has its own close button, and on a 720×1280 phone the bar cost the second action row.
  Wide windows: cover with the action area under it on the start side, title/progress/controls beside it (so a
  phone in landscape needs no scrolling either). Robolectric's legacy text metrics are ~40 % taller than a device's,
  so the unit tests check the logic at 360×640 dp and the exact 720×1280 @ 320 dpi fit was checked on the emulator.
- **Font scale via the activity configuration, not `LocalDensity`**: Compose dialogs, sheets and popups get their
  density from their own window, so a `LocalDensity` override leaves them at 100 %. `MainActivity.attachBaseContext`
  applies `fontScale = system × setting`; `FontScaleBoot` (SharedPreferences, read synchronously) mirrors the
  DataStore value, and a different value recreates the activity. **Platform quirk found on the API 35 emulator**:
  after `recreate()` Android keeps ("preserves") the window and the freshly inflated content root pads itself by the
  system bars (legacy `fitsSystemWindows`), i.e. white bars on top and bottom in a dark theme; a rotation does not do
  this. `MainActivity` now turns `fitsSystemWindows` off on the content root (Compose handles all insets).
- **Theme**: applied in Compose (`LauschigTheme(appearance)`) – instant, also in dialogs and sheets; status/navigation
  bar icons follow via `enableEdgeToEdge(SystemBarStyle.auto { dark })`. The system splash and the window background
  still follow the system's dark mode (no `UiModeManager.setApplicationNightMode`, which would recreate the activity
  on every change). The Glance widget follows the system as before.
- **Pure black**: background, surface, surfaceDim, surfaceContainerLowest = black; the other containers are blended
  towards black (60/50/40/30 %), so they keep the scheme's tint and their order.
- **Navigation**: up/down buttons swap with the neighbouring row of the settings list (hidden tabs included, what you
  see is what moves); the start screen is a radio group over the visible tabs. Changes go through
  `UiSettings.updateTabLayout` (read-modify-write in one DataStore transaction). The bar follows at once, the start
  screen from the next cold start (the NavHost's start destination must not change while it lives).
- **Media notification → player**: the session activity now carries `open=player`. It had request code 0 like the
  cleanup notification's `MainActivity` intent; both differ only in extras, so `FLAG_UPDATE_CURRENT` made one
  overwrite the other's target (pre-existing, became visible with the new extra). The session activity uses code 1.
- **„Was ist neu“**: string arrays (`whatsnew_0_2_0`) in `values/` and `values-de/`; a test checks that the current
  `versionCode` has notes in both languages. "Updated" = `lastUpdateTime > firstInstallTime`; without a stored
  version an update counts as coming from 0.1.0. The sheet is stored as seen only when dismissed.
- **Tag filter**: a chip after the kind chips (also shown when there is only one kind), session-only state in
  `LibraryViewModel`; a chosen tag the current tab does not have is ignored (shown unselected) and comes back on a tab
  that has it. Discover got a „Tags“ row (chips → tag page), like „Genres“.
- **List–detail**: `ListDetailPaneScaffold` did not fight the NavHost once it is used as layout only: a fixed value
  (both panes expanded) whenever the directive offers two partitions, and the detail pane hosts a nested `NavHost`
  with the existing `ItemDetail` / `Browse` / `Collection` routes – their ViewModels read the route from their
  `SavedStateHandle` unchanged and back presses are handled by the nested NavHost. No navigator, no drag handle
  (pane widths: library list 45 %, search 40 %). The page shown in the pane is remembered (`PaneSelection`,
  saveable) and moves onto the outer back stack when the window shrinks to one pane (tablet rotated to portrait,
  foldable folded); verified on the emulator. Phones in landscape (914 dp) are "expanded" too and get rail + two
  panes.
- **Rail insets**: in rail mode the content and the rail use system bars + display cutout (not the IME) so search
  fields keep handling the keyboard themselves; the mini player pads itself by the navigation bar.
- **Readable width**: settings, navigation settings, Verbindungsstatus, Server-Verwaltung, downloads, cleanup, setup
  and the full-screen item detail are centred at 840 dp at most.

Not done / left for later: no tabletop-specific player layout (hinge-aware split) and no foldable posture testing
(the emulator has no foldable AVD; the pane directive's hinge avoidance comes from the library); no pane drag handle;
grids keep `GridCells.Adaptive(120.dp)` (they already adapt their column count – 4 columns in the tablet's list pane,
5 on tablet portrait; the minimum tile width was not tuned further); the selected title is not highlighted in the
list pane.

## Fix round 1 (review, 2026-11-03)

- **„Beim Start weiterspielen“ with another start screen**: `continueOnLauncherStart()` guarded with `!= Start`, which
  since the start screen is the user's meant "never" for Library/Discover/Search starts; it now only skips `Setup`.
- **No replay of `open=` from a restored task**: after a process death Recents restores the task with its original
  intent and extras; `IncomingIntents.consume` ignores every one-shot request on a replay now, `open` included (still
  stripped).
- **Window background**: `WindowBackground()` (in `AppRoot`) paints the activity window in the color scheme's
  background, and the night window theme starts black, so no grey frames after the splash, on rotation or
  `recreate()` in pure black (checked frame by frame during a rotation on the emulator).
- **First sign-in → chosen start screen**: `finishSetup(home)` replaces Setup with the start tab and makes it the
  graph's start destination (tab switches pop up to it instead of piling up); re-auth still just goes back.
- **Player labels at large text**: action labels and the top bar's source label stay on one line and shrink
  (`TextAutoSize.StepBased`, down to 8 sp) instead of „Warteschla…“ / a two-line „Heruntergelad|en“; checked at
  720×1280 @ 320 dpi with 130 % in German.
- **Tests**: continue on start with a Library start screen, replayed open extra, window background color,
  `finishSetup`, label overflow, the real breakpoints from Robolectric window sizes (`WindowLayoutTest`: rail from
  600 dp, two panes from 840 dp) and the font-scale recreate guard (`FontScaleBoot.needsRecreate`).

Unchanged (for Fabian's briefing): hiding the current start tab keeps the live NavHost start until the next cold start;
the `lastUpdateTime > firstInstallTime` heuristic can show „Was ist neu“ once on a fresh install that receives late
splits.
