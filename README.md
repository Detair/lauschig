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

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
