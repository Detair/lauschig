# Phase 3C – Download-Verwaltung und lokale Ordner („Auf dem Gerät“) (Plan)

Scope: spec 2.7a "Download-Verwaltung" (pause/resume, sort, search, used storage) and the phase-3 item "lokale Ordner"
(files that only live on the device, played without any server). Global constraints of the phase-1 plan apply (strings in
`values/` and `values-de/`, offline first, nothing on the start path awaits I/O, downloads stay in app storage).

## Part 1 – Download management page

| Topic | Decision |
|---|---|
| Data | One Room query `DownloadDao.observeEntries(serverId)`: every `downloads` row of the account's server with the title (book row, or episode + podcast title), the bytes on disk (`SUM(completedBytes)`, includes `.part` progress), the total size, `requestedAt` (date added) and the progress row (`lastUpdate` = recently played, `isFinished`). |
| Pure logic | `downloads/DownloadList.kt`: `arrange(rows, sort, query)` (NAME natural + case-insensitive, SIZE desc, ADDED desc, PLAYED desc with never-played last; search = substring of title or subtitle, case-insensitive, trimmed) and `totals(rows)` (used bytes, number of finished-and-downloaded titles and their bytes). |
| Entry state | QUEUED (waiting for the network if the worker left `offline`), RUNNING with %, PAUSED (by the user, or `stream_priority` = "paused for the stream"), FAILED with the mapped reason (`storage_full`, `http_404`, `invalid_path`, other), COMPLETE with size. |
| Actions | Pause (QUEUED/RUNNING → `cancel(deleteFiles = false)`), resume / retry (`enqueue`), cancel (`cancel(deleteFiles = true)`, partial data goes), delete (COMPLETE, with confirmation). Exactly the `DownloadManager` semantics the detail page uses; the worker, `StreamPriority` and the `stream_priority` pause are untouched. A resume from the page uses the normal network rule (Wi-Fi setting). |
| Date added | `enqueue` keeps the first `requestedAt` when a paused/failed row is re-queued (it was overwritten before; nothing else reads it). |
| Bulk | "Gehörte löschen" (delete finished): deletes the downloads of titles marked as heard (like the cleanup page, without the per-title ignore list), after a confirmation with count and size. Deleting every completed download would be too destructive for one tap. |
| Top | Used storage (sum of the listed rows on disk) and free space of the app storage volume (`DownloadFiles.freeSpace()`, read on IO). |
| Where | Route `Downloads`; from Library (button on top of the "Heruntergeladen" tab) and Settings (downloads section). Sort chips + search field on the page; sort choice is a `rememberSaveable` UI state. |

## Part 2 – Local folders

### Pseudo server

`local/DeviceLibrary.kt`:

- `SERVER_ID = "device"`: every Room row of a local title (`items`, `audio_files`, `chapters`, `progress`,
  `local_sessions`, `bookmarks`, `title_audio`) uses it; `history_sessions` and `downloads` never get one. A real server id is always 16 hex
  chars (`Account.serverId`), so `"device"` can never be an account's id.
- `LIBRARY_ID = "device"` for `items.libraryId` (no `libraries` row: kind filters and podcast checks simply never match).
- Item ids are `local:` + 24 hex (SHA-256 of tree URI + group key). ABS ids are `[A-Za-z0-9_-]`, so a `:` id is
  never a server id; `DownloadFiles` rejects it as a path as well.
- `serverFor(accountServerId, itemId)`: `"device"` for a `local:` id, else the account's server. Every UI place that
  turns an item id into a (serverId, itemId) pair uses it (item actions, detail, player chapters, comfort, extras,
  start, search, queue rows).
- Two independent layers keep local titles away from ABS: (1) the rows live under `"device"`, which no sync ever reads
  (all sync code runs with the account's server id); (2) every sync/upload/download/API entry point also refuses
  `"device"` and any `local:` key explicitly, so a local key stored under the account's server by a bug is still
  never sent.

### Every path that must bypass local titles (guard + test)

| Path | Guard |
|---|---|
| Progress push (`ProgressSync`) | `sync("device")` = no-op; dirty rows with a `local:` key are skipped (stay dirty, never sent). |
| Session upload (`ProgressSync.uploadSessions`) | sessions with a `local:` key are not uploaded (and not marked synced). |
| Progress pull / merge | server rows are keyed by ABS ids; nothing to do, covered by the namespace. |
| Bookmark sync (`BookmarkSync`, `BookmarkRules`) | `isSyncable(local:)` = false → saved with `pending = NONE`, deleted at once (like episode bookmarks); merge ignores them; `sync("device")` = no-op. No bookmark-only work is enqueued for a local title. |
| History refresh (`HistoryRepository.refresh`) | no-op for `"device"` / `local:`; the history shows this device's sessions. |
| Library sync (`LibrarySync`) | `syncAll("device")`, `loadDetails`, `loadDetailsForPlayable`, `loadEpisodes` = no-op for local; deletions only ever touch the synced server's rows (test: device rows survive a full sync). |
| Downloads (`DownloadManager.enqueue/cancel`, `DownloadWorker`) | enqueue = no-op (no row, no work); cancel = no-op (never touches the user's files or the `content://` paths); the worker fails fast without I/O. |
| Download on play (`PlayDownloadStarter`) | no-op for local. |
| Auto-download (`EpisodeAutoDownloader`) | runs for the account's server on episodes only; unaffected (namespace). |
| Next-volume prefetch (`NextVolumePrefetcher`) | skips `local:` titles. Local titles have no series anyway. |
| Missing volumes, RMAB, series pages | driven by the account catalog and series names; local titles carry no series. Unaffected (namespace). |
| Playback sources (`MediaItemFactory`, `PlayerControllerImpl`, `ServiceTitleStarter`) | a local title never gets a base URL, never loads details, never reports a stream start; `build` throws `PlaybackUnavailableException` instead of building an `http` URI even if a base URL is passed. |
| Covers (`coverModel`) | a `local:` id never builds a server cover URL (only its own cover file, else initials). |
| "Stand vom Server holen" (`ProgressActions.pullFromServer`) | returns false without any API call; the menu entry is hidden for local titles. |
| Progress actions | written locally as usual; no full sync is triggered for local titles. |
| Cleanup reminder | joins `downloads`; local titles have none. |
| Last title / auto-resume (`LastTitleStarter`) | accepts a last-played local title (it is this device's), looked up under `"device"`. |
| Queue | rows stay in the account's queue namespace (`queue.serverId` = account) with mixed keys; resolution of a queued key uses `serverFor`. The queue is device-local and never synced. When a local title ends, the service advances the account's queue. |

### Schema v8 (AutoMigration 7 → 8, new tables only)

```kotlin
@Entity("local_folders", primaryKeys = ["uri"])
data class LocalFolderEntity(uri: String /* tree URI */, name: String, addedAt: Long, lastScanAt: Long = 0,
    error: String? = null /* "no_access" | "missing" */)
@Entity("local_titles", primaryKeys = ["id"], indices = [Index("folderUri")])
data class LocalTitleEntity(id: String /* local:… */, folderUri: String, groupKey: String, relativePath: String,
    signature: String /* files fingerprint */, scannedAt: Long)
```

Item, audio file and chapter rows of a local title are ordinary `items` / `audio_files` / `chapters` rows under
`"device"`: `audio_files.localPath` = the file's `content://` document URI, `ino` = document id, `completedBytes` = size.

### Scan

| Topic | Decision |
|---|---|
| Access | SAF only: `ACTION_OPEN_DOCUMENT_TREE`, `takePersistableUriPermission(READ)`; no storage permission. Behind `DocumentTree` (children of a directory; null = not accessible) and `AudioProbe` (tags, duration, embedded picture) so the logic runs in unit tests with fakes. |
| Grouping (pure, `LocalGrouping`) | Per directory: every `.m4b` is a title of its own; all other audio files of that directory together are one title. Disc folders (`CD 1`, `Disc2`, `Teil 3`, `Part 1`, `Seite A`…) are merged into their parent's title in natural order. Hidden entries (`.x`) are skipped; depth ≤ 8. |
| Order | Natural sort by file name (`2` < `10`, case-insensitive, leading zeros ignored, stable tie-break). |
| Names | Fallback name of a directory title = directory name; of an `.m4b` that is the only title of its directory = directory name, else the file name without extension. `Autor - Titel` in a fallback name gives author + title. |
| Metadata (`LocalMeta`) | From the first file: single file → title tag, else album; several files → album, else title tag; then the fallback name. Author = album artist → artist → author tag. Narrator = composer (like ABS). Duration = sum of file durations; files without a readable duration are left out (they would break the position mapping); a group without any playable file is no title. |
| Chapters | Several files: one chapter per file (title tag if every file has a distinct one, else the file name). One file: no chapters (embedded m4b chapters are not parsed: `MediaMetadataRetriever` does not expose them; left for later). |
| Cover | Embedded picture of the first file, else `cover.jpg/jpeg/png`, `folder.jpg/jpeg/png` in the title's directory. Copied to `filesDir/device_covers/<hex>.jpg` (app storage, so lists never open SAF). |
| Incremental | Signature = hash of (document id, size, mtime) of every file + the cover file. Unchanged signature → nothing is re-read. Changed → files, chapters, metadata, cover are rebuilt; `addedAt`, progress, bookmarks stay. |
| Removal | A title no longer found in an accessible folder: its item/file/chapter/local-title rows and its cover go; progress, sessions and bookmarks stay (they come back if the folder returns). A folder whose permission was revoked or that vanished is marked (`no_access` / `missing`) and its titles are **kept** (playing them shows "not reachable"); the user can re-add or remove it. Removing a folder releases the permission and deletes its titles' rows. |
| When | `LocalScanWorker` (unique `local-scan`, `APPEND_OR_REPLACE`, no network): after adding/removing a folder, from "Jetzt neu einlesen", and on app start at most every 6 h (stamp `local_scan` in `SyncStamps`). Nothing on the start path waits for it. |

### UI

- Library tab "Auf dem Gerät" (last tab): grid of local titles (cover tiles with progress, offline badge); empty state with
  "Ordner hinzufügen" (opens the picker), scan-in-progress hint.
- Settings section "Auf dem Gerät": folders with title count or error, remove (with confirmation), "Ordner hinzufügen",
  "Jetzt neu einlesen"; plus "Downloads verwalten" in the downloads section.
- Detail page of a local title: no download controls, no "Stand vom Server holen", play always enabled; bookmarks are
  local-only (hint), history = this device.
- Start "Weiterhören" and Search include local titles (search only when the kind filter is "Alle").

## Files

- `local/DeviceLibrary.kt`, `local/LocalGrouping.kt` (natural order, grouping, names), `local/LocalMeta.kt` (metadata
  fallbacks, chapters), `local/DocumentTree.kt` (+ `SafDocumentTree`), `local/AudioProbe.kt` (+ `RetrieverAudioProbe`),
  `local/LocalLibraryScanner.kt`, `local/LocalFolders.kt` (repository, permission, scheduling), `local/LocalScanWorker.kt`,
  `local/LocalUi.kt` (settings section, device tab content).
- `db/entities/LocalEntities.kt`, `db/dao/LocalLibraryDao.kt`; DB v8; `DownloadDao.observeEntries`.
- `download/DownloadList.kt` (pure), `ui/downloads/DownloadsScreen.kt` + `DownloadsViewModel.kt`, nav route `Downloads`.
- Guards in `ProgressSync`, `BookmarkRules`, `BookmarkSync`, `HistoryRepository`, `LibrarySync`, `DownloadManager`,
  `DownloadWorker`, `PlayDownloadStarter`, `NextVolumePrefetcher`, `ProgressActions`, `MediaItemFactory`,
  `PlayerControllerImpl`, `ServiceTitleStarter`, `PlaybackService` (queue owner), `queueEntryState`, `coverModel`,
  `LastTitleStarter`; `DownloadFiles` routes device covers.

## Tests (TDD, red first)

- `LocalGroupingTest`: natural sort (numbers, leading zeros, case, umlauts stable), one title per directory, every m4b its
  own title, disc folders merged in order, hidden entries skipped, fallback names (directory vs. file), `Autor - Titel`.
- `LocalMetaTest`: title/album choice for one vs. several files, author/narrator fallbacks, blank tags ignored, chapters
  from files (distinct tags vs. file names), unreadable files left out, empty group = no title.
- `LocalLibraryScannerTest` (fakes + in-memory Room): new title rows under `"device"` with `content://` paths; unchanged
  signature reads nothing again; added/removed file rebuilds files and chapters and keeps progress; vanished title removed
  but progress kept; revoked folder keeps titles and records `no_access`; removed folder deletes its titles.
- `LocalExclusionTest` + additions to existing tests: progress push/session upload skip local, bookmark rules local-only
  + `BookmarkSync` never sends local, history refresh no API call, `LibrarySync` no-ops and keeps device rows,
  `DownloadManager` enqueue/cancel no-op, `PlayDownloadStarter` no-op, `ProgressActions.pullFromServer` no API,
  `MediaItemFactory` never builds http for local, `PlayerController` local title with a base URL never streams or loads
  details, `ServiceTitleStarter` same, `coverModel` no URL, `NextVolumePrefetcher` skips local, queue state of a local key.
- `DownloadListTest`: each sort, search on title/subtitle, never-played last, totals (used bytes, finished count/bytes).
- `DownloadDaoEntriesTest`: titles for books and episodes, bytes on disk incl. partial, played/finished columns.
- `MigrationTest`: v7 → v8 keeps data and the new tables work.
- UI: downloads page lists states and actions, bulk delete asks first; library device tab; settings folder section.
