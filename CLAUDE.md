# Lauschig

Native Android client for Audiobookshelf. Kotlin, Jetpack Compose, Hilt, Room, Media3, WorkManager; minSdk 28. The README covers features, modules and the release build. This file holds what is not obvious from the code.

## Commands

```sh
# What CI runs on every pull request, as three parallel jobs (test, lint, build)
./gradlew testDebugUnitTest :abs-api:test :core:model:test
./gradlew lint
./gradlew assembleDebug assembleRelease   # the release is built with R8; without the LAUSCHIG_* variables unsigned
```

Wrap Gradle runs in `timeout`, a hung daemon otherwise blocks the session.

## Modules

- `:app` – UI, playback, sync, downloads.
- `:core:database` – Room entities, DAOs and the exported schemas. A schema change needs a migration and the new exported schema file.
- `:core:model` – plain Kotlin keys every layer shares.
- `:abs-api` – the Audiobookshelf API.
- `:baselineprofile` – needs a physical device; `./gradlew :baselineprofile:assemble` is the compile check without one.

## Principles

- **Offline first.** The local database is the source of truth. The UI reads from Room and never waits for the server; the network runs in the background. A feature that blocks on the server at startup or loses progress offline is a bug.
- **Open source dependencies only.** No Google Play Services and no ML Kit without Fabian's explicit OK. The QR scanner uses ZXing and CameraX for that reason.
- **Nothing sensitive leaves the device by accident.** The diagnostic log masks passwords, tokens and server addresses. The server QR code holds addresses only, never an account or a token (`docs/server-qr-code.md`), and only the in-app scanner accepts it.

## Rules

- **ReadMeABook is switched off for the launch.** The code in package `rmab` stays, the build leaves it out; `-Plauschig.rmab=true` builds it in. `RmabTextsTest` fails if any string outside `rmab_*` names "RMAB" or "ReadMeABook", so in-app texts such as "What's new" must not mention it. Release notes may.
- **User-facing texts exist in English (`values/`) and German (`values-de/`).** Add both when adding a string.
- **Never commit a keystore or signing values.** Signing comes from the environment only.
- **Work in a worktree, not in the main checkout.** `main` only takes pull requests, and only with green `test`, `lint` and `build`.
- **A tag `v*` on `main` is a release.** The release workflow repeats tests, lint and builds on the tagged commit, then signs the bundle and the APK and publishes the GitHub release. Tags cannot be moved or deleted, so check version, "What's new" and `docs/release-notes/v<version>.md` before tagging. Tagging needs Fabian's OK. The steps are in `docs/release-anleitung.md`.
- **Workflows pin every action by commit hash.** The repository rejects anything else.

## Where things are

- Design spec and phase plans (mostly German): `docs/superpowers/specs/`, `docs/superpowers/plans/`.
- Play Store texts and privacy policy: `docs/play-store/`, `docs/privacy-policy.md`.
- Release notes: `docs/release-notes/`.
