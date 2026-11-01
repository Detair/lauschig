# Lauschig

Lauschig is a native, offline-first Android client for [Audiobookshelf](https://www.audiobookshelf.org/).
It is built for listening: your library and downloaded books open instantly, playback never waits for the
network, and listening progress is synced with the server whenever a connection is available.

## Why

Existing clients tend to block on the server at startup or lose progress when offline. Lauschig treats the
local database as the source of truth and uses the network only in the background.

## Status

Early development (phase 1: offline core). Not usable yet.

The design spec and phase 1 plan (German) live in [`docs/superpowers/specs/2026-10-31-lauschig-design.md`](docs/superpowers/specs/2026-10-31-lauschig-design.md) and [`docs/superpowers/plans/`](docs/superpowers/plans/).

## Tech

Kotlin, Jetpack Compose, Hilt, Room, Media3, WorkManager. Android 9+ (minSdk 28).

## Build

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :abs-api:test lint
```

## Release build

```sh
./gradlew :app:bundleRelease
```

Signing is read from the environment (or Gradle properties): `LAUSCHIG_KEYSTORE`, `LAUSCHIG_KEYSTORE_PASSWORD`,
`LAUSCHIG_KEY_ALIAS`, `LAUSCHIG_KEY_PASSWORD`. Without them the bundle is built **unsigned** and a warning is printed.
Never commit a keystore. Pushing a tag `v*` runs `.github/workflows/release.yml`, which builds the signed AAB from
repository secrets (`LAUSCHIG_KEYSTORE_BASE64` plus the three values above); uploading to Play is manual.

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
