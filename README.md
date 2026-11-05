# Lauschig

Lauschig is a native, offline-first Android client for [Audiobookshelf](https://www.audiobookshelf.org/).
It is built for listening: your library and downloaded books open instantly, playback never waits for the
network, and listening progress is synced with the server whenever a connection is available.

## Screenshots

<table>
  <tr>
    <td align="center" valign="top"><img src="docs/screenshots/start.png" width="260" alt="Start screen with the continue card for Winnetou III, a row of titles in progress and this week's listening time"><br>Continue where you left off</td>
    <td align="center" valign="top"><img src="docs/screenshots/detail.png" width="260" alt="Detail page of Die Schatzinsel with a header tinted in the cover's colours"><br>Every title in its cover's colours</td>
    <td align="center" valign="top"><img src="docs/screenshots/player.png" width="260" alt="Player for Winnetou III with chapter progress, speed, chapters, sleep timer and audio settings"><br>Chapters, speed and sleep timer at hand</td>
  </tr>
  <tr>
    <td align="center" valign="top"><img src="docs/screenshots/statistics.png" width="260" alt="Statistics for the year: listening time per month, streaks and finished books"><br>Listening statistics by week, month and year</td>
    <td align="center" valign="top"><img src="docs/screenshots/achievements.png" width="260" alt="Achievements grid with levels for streaks, hours listened and finished books"><br>Achievements for streaks, hours and finished books</td>
    <td align="center" valign="top"><img src="docs/screenshots/year-in-review.png" width="260" alt="Year in review slide with the five most listened titles of the year"><br>Your year in review</td>
  </tr>
</table>

![Tablet in landscape: the library's finished titles on the left, the details of Nussknacker und Mausekönig on the right](docs/screenshots/tablet-library.png)

On tablets, the library and a title's details sit side by side.

The screenshots use a demo library of public-domain books with covers made for it.

## Why

Existing clients tend to block on the server at startup or lose progress when offline. Lauschig treats the
local database as the source of truth and uses the network only in the background.

## Status

Version 0.3.0, in daily use on our own devices; not on Google Play yet (a closed test is planned). What works:

- Offline first: library, downloads, progress and listening sessions live on the device and sync in the background.
- Browsing by series, authors, narrators, genres, tags and collections; podcasts; search; single sign-on (OIDC).
- Player with chapters, per-title speed and volume boost, sleep timer, bookmarks, listening history and a queue;
  books and folders that exist only on the device.
- Android Auto, voice search, a home-screen widget, links from the Audiobookshelf web app, phone and tablet layouts.
- Listening statistics, achievements and a year in review, computed on the device.

The design spec and the phase plans (mostly German) live in [`docs/superpowers/specs/`](docs/superpowers/specs/) and [`docs/superpowers/plans/`](docs/superpowers/plans/).

## Tech

Kotlin, Jetpack Compose, Hilt, Room, Media3, WorkManager. Android 9+ (minSdk 28).

Modules: `:app` (UI, playback, sync, downloads), `:core:database` (Room entities, DAOs and the exported schemas),
`:core:model` (plain Kotlin keys every layer shares) and `:abs-api` (the Audiobookshelf API).

## Build

```sh
./gradlew :app:assembleDebug testDebugUnitTest :abs-api:test :core:model:test lint
```

## Release build

```sh
./gradlew :app:bundleRelease
```

Signing is read from the environment (or Gradle properties): `LAUSCHIG_KEYSTORE`, `LAUSCHIG_KEYSTORE_PASSWORD`,
`LAUSCHIG_KEY_ALIAS`, `LAUSCHIG_KEY_PASSWORD`. Without them the bundle is built **unsigned** and a warning is printed.
Never commit a keystore. Pushing a tag `v*` runs `.github/workflows/release.yml`, which builds the signed AAB from
repository secrets (`LAUSCHIG_KEYSTORE_BASE64` plus the three values above); uploading to Play is manual. CI uses environment variables only (no Gradle properties files).

## Baseline profile and startup benchmark

The `:baselineprofile` module needs a connected physical device (USB debugging on, ideally the phone you use):

```sh
# Generate and apply the baseline profile (writes app/src/release/generated/baselineProfiles/)
./gradlew :app:generateBaselineProfile

# Macrobenchmark: cold start offline (target < 800 ms, recorded, never fails the run).
# Sign in and sync once, then switch the phone to airplane mode and run:
./gradlew :baselineprofile:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.wolftown.lauschig.baselineprofile.StartupBenchmark
```

Journeys (startup, library scroll) live in `baselineprofile/src/main/kotlin/`. Compile check without a device:
`./gradlew :baselineprofile:assemble`.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
