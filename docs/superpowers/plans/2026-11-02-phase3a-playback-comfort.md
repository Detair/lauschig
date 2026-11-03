# Phase 3A – Wiedergabe-Komfort (Plan)

Scope: spec 2.5 (sleep timer, rewind on resume), 2.7a (speed per title, skip silence, volume boost, auto-resume on
car/Bluetooth, continue on app start), 2.7b ("mark not finished, keep position"). Global constraints of the phase-1
plan apply (strings in `values/` and `values-de/`, nothing in the start path awaits the network).

## Decisions

| Topic | Decision |
|---|---|
| Sleep timer state | `SleepTimer` app singleton (same process as the service), survives leaving the player. Pure core `SleepTimerMachine`. |
| Timer durations | 5/10/15/30/45/60/90 min and "end of chapter" (no chapters: end of title). |
| Countdown | Counts **listening time** only: a manual pause holds the timer (falling asleep is about heard time). |
| Fade | Last 10 s, volume `(remaining / 10 s)²` (perceived loudness falls roughly linearly). Pause at 0, then volume back to 1. |
| Shake | Accelerometer registered only while the timer is in its last 60 s (fade included). Shake adds the last chosen duration (duration mode) or one more chapter (chapter mode); the fade is undone. |
| End of chapter | Ends after chapter `endIndex` (the current one at start). Jumping past it by hand re-anchors to the new chapter; a natural crossing ends the timer. |
| Rewind on resume | Setting off / 3 / 5 / 10 s. Pause < 3 s: none; < 10 min: base; < 1 h: 2× base; longer: 3× base. Only if the same title is resumed at the same position (±1 s, same file): a seek, a title switch or a sync jump in between disables it. Also after a transient audio-focus loss (call, navigation prompt). Clamped to the start of the current file. Lives in the service, so notification, headset and Auto resumes rewind too. |
| Rewind default | 5 s (spec 2.5 "Claudes Vorschlag"); can be switched off. |
| Speed per title | Room table `title_audio` (serverId, itemId, speed, boostDb). A title without its own speed starts at the setting "Standardtempo" (default 1.0×), never at the previous title's speed. Every speed change in the player is stored for the playing title. Applied/stored in the service, so any controller is covered. |
| Skip silence | **Global** (setting + toggle in the player's audio sheet write the same value). |
| Volume boost | **Per title** (Off/+3/+6/+9 dB, `LoudnessEnhancer` on the player's audio session) – quiet digitised tapes are a property of the title. Titles without their own value use the setting "Standard-Verstärkung" (default off). |
| Auto-resume | Setting, default off. `AudioDeviceCallback` (no permission) while the app process lives; new A2DP / BLE headset/speaker / wired / USB headset / car (bus) output. Resumes the newest unfinished title if it was paused < 2 h ago (progress `lastUpdate`), not while something plays or loads, at most once per 10 s. Devices already connected when the callback is registered are ignored. |
| Auto-resume limits | A dead process gets no callback (a manifest receiver would need `BLUETOOTH_CONNECT`); Android 12+ may refuse a foreground-service start from the background – Media3 reports it, we log it. Reliable subset: app open, or the service still alive after a recent pause. Documented in the report, untested on a device. |
| Continue on start | Setting, default off. `MainViewModel` (once per activity, not on rotation) after the local account read, in a background coroutine: if nothing is playing, plays the newest unfinished title (local DB only). |
| Mark not finished, keep position | New progress entry for finished titles whose saved position is inside the title (0 < pos < duration − 5 s; ABS re-marks titles with < 5 s left). `markFinished` now keeps an existing position (only an unstarted title jumps to the end), and the push sends `progress = 1` for finished rows. |

## Files

- `playback/SleepTimerMachine.kt` (pure), `playback/SleepTimer.kt` (singleton glue, ticker, shake sensor), `playback/ShakeDetector.kt` (pure detection + sensor wrapper)
- `playback/ResumeRewind.kt` (pure), `playback/SpeedMemory.kt` (pure), `playback/PlaybackComfort.kt` (service glue: rewind, speed memory, skip silence, `LoudnessEnhancer`)
- `playback/AutoResume.kt` (pure `AutoResumePolicy`, `AudioDeviceWatcher`, `LastTitleStarter`)
- `settings/PlaybackSettings.kt` (DataStore `playback_settings`)
- `db/entities/TitleAudioEntity.kt`, `TitleAudioDao` in `OtherDaos.kt`, DB v6 (AutoMigration 5→6), `ProgressDao.newestUnfinished`
- `PlayerController`: `setVolume` (default no-op in the interface), `PlayerUiState.chapterEndsSec`
- `ProgressActions.markNotFinishedKeepPosition`, `ProgressEntry.MarkNotFinishedKeep`
- UI: `ui/player/ComfortSheets.kt` (sleep timer sheet, audio sheet), `ui/player/ComfortViewModel.kt`, buttons + countdown in `PlayerScreen`, playback section in `SettingsScreen`
- Strings in `values/strings.xml` and `values-de/strings.xml`

## Interfaces

```kotlin
sealed interface SleepMode { data class Minutes(val minutes: Int); data object EndOfChapter }
data class SleepInput(isPlaying, positionSec, speed, chapterIndex, chapterEndsSec, durationSec)
class SleepTimerMachine { start(mode, input); cancel(); tick(input, deltaMs): SleepEffect(volume, pause); shake(input): Boolean; remainingMs(input) }
class ResumeRewind { onPaused(key, index, posMs, now); onSeekOrTitleChange(); onResumed(key, index, posMs, now, baseSec): Long }
fun rewindMs(pauseMs, baseSec): Long
class SpeedMemory { onTitle(key, stored, default): Float; onSpeedChanged(key, speed): Boolean }
object AutoResumePolicy { fun shouldResume(...): Boolean }
```

## Tests (TDD, red first)

- `SleepTimerMachineTest`: minutes count down only while playing; fade curve (1 → 0, monotonic, quadratic); pause + volume 1 at the end; cancel restores volume; shake only in the last minute, adds the duration; chapter mode ends at chapter end, natural crossing ends, manual jump re-anchors, shake adds a chapter, last chapter cannot extend; no chapters → end of title.
- `ShakeDetectorTest`: a single bump is no shake, several strong samples within the window are; samples spread too far apart are not.
- `ResumeRewindTest`: scaling by pause length, off, short pause, seek / title change / moved position disable, clamp at 0.
- `SpeedMemoryTest`: stored speed vs default, own apply is not stored, user change is stored for the current key, nothing before a title.
- `AutoResumePolicyTest`: off, playing, too old, no title, irrelevant device, debounce, initial devices ignored.
- `ProgressActionsTest`: mark not finished keeps position, entries offer it only inside the title; markFinished keeps the position.
- `PlayerScreenTest` / settings: timer countdown shown, audio sheet opens.
- `MigrationTest`: v5 → v6 keeps data.
