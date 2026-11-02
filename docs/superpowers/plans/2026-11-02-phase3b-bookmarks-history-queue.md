# Phase 3B – Lesezeichen, Hörverlauf, Warteschlange, Sammlungen/Playlists (Plan)

Scope: spec 2.7a (bookmarks with note, synced and offline; listening history per title; ABS collections and
playlists; queue "danach spielen"). Global constraints of the phase-1 plan apply (strings in `values/` and
`values-de/`, offline first, a 401 never blocks local data, network only in background coroutines / WorkManager).

## API facts (ABS v2.37.1, `server/` in the ABS source)

| What | Endpoint | Source | Notes |
|---|---|---|---|
| Bookmarks (all) | `GET /api/me/bookmarks` → `{ bookmarks: [...] }` | `routers/ApiRouter.js:177`, `controllers/MeController.js:123` | Also in `GET /api/me` as `bookmarks` (`models/User.js:623`, `toOldJSONForBrowser`). We use `/api/me/bookmarks`, falling back to `/api/me` on 404 (older servers). |
| Bookmark shape | `{ libraryItemId, time, title, createdAt }` | `models/User.js:865` | **No episode id** (`User.js:835` TODO: "should use mediaItemId … to support podcast episodes"). Identity = `libraryItemId` + `time` (`findBookmark`, `User.js:841`, `==` compare). |
| Create | `POST /api/me/item/:id/bookmark` body `{ time, title }` → bookmark | `ApiRouter.js:187`, `MeController.js:327` | `title` must be a non-empty string, `time` a number (400 otherwise). Existing bookmark at the same time: title is updated, no duplicate (`User.js:853`) → create is idempotent. 404 unknown item. |
| Update | `PATCH /api/me/item/:id/bookmark` body `{ time, title }` → bookmark | `ApiRouter.js:188`, `MeController.js:360` | 404 when no bookmark at that time. |
| Delete | `DELETE /api/me/item/:id/bookmark/:time` → 200 | `ApiRouter.js:189`, `MeController.js:398` | `time` parsed with `Number()`; 404 when not found. Removal compares `bm.time !== time` strictly (`User.js:909`) → the path must carry the exact number (we send plain decimal, e.g. `123` / `123.5`). |
| Item listening sessions | `GET /api/me/item/listening-sessions/:libraryItemId/:episodeId?itemsPerPage=&page=` → `{ total, numPages, page, itemsPerPage, sessions }` | `ApiRouter.js:180`, `MeController.js:185`, `ApiRouter.js:530` | Sorted `updatedAt` desc. 404 for an unknown item or a podcast without (valid) episode. Session JSON = `objects/PlaybackSession.js:52` `toJSON` (`id, libraryItemId, episodeId, displayTitle, duration, timeListening, startTime, currentTime, startedAt, updatedAt, deviceInfo, date, dayOfWeek, …`); `timeListening` is an INTEGER column (`models/PlaybackSession.js:193`), stats code also tolerates strings (`ApiRouter.js:549`). |
| Uploaded local sessions keep their id | `POST /api/session/local-all` | `managers/PlaybackSessionManager.js:127` | Only ids starting with `play_local_` are remapped; ours are UUIDs → server and local rows dedupe by id. |
| NOT used | `GET /api/me/sessions` | `MeController.js:37` | These are **auth** sessions (refresh tokens), not listening sessions. |
| Library collections | `GET /api/libraries/:id/collections` → `{ results, total, limit, page, … }` | `ApiRouter.js:80`, `controllers/LibraryController.js:814` | `results[]` = `Collection.toOldJSONExpanded` (`models/Collection.js:275`): `{ id, libraryId, name, description, lastUpdate, createdAt, books: [expanded library item] }`. Already filtered by user permissions (`Collection.js:34`). Collections are books only. |
| User playlists | `GET /api/playlists` → `{ playlists: [...] }` | `ApiRouter.js:160`, `controllers/PlaylistController.js:138` | Own playlists of all accessible libraries. `Playlist.toOldJSONExpanded` (`models/Playlist.js:347`): `{ id, name, libraryId, userId, description, lastUpdate, createdAt, items: [{ libraryItemId, libraryItem } | { libraryItemId, episodeId, episode (expanded, with audioFile), libraryItem (minified) }] }`. |
| Add to playlist | `POST /api/playlists/:id/item` | `PlaylistController.js:283` | **Skipped** (optional in the brief; would need a picker + error handling for "already in playlist" / other library). |

## Decisions

| Topic | Decision |
|---|---|
| Bookmark key | Room `bookmarks` (serverId, itemKey, timeSec) PK; `itemKey` is the playable key. New bookmarks use whole seconds (`floor`, like the ABS web client). |
| Note vs. ABS title | ABS needs a non-empty title. Our `note` is optional; pushed title = note, or the clock text of the time (`1:02:03` / `02:03`) when empty. A pulled title equal to that clock text is read back as "no note". |
| Episode bookmarks | **Device-local only** (ABS has no episode id on bookmarks; syncing under the podcast id would mix episodes). Documented in the UI-less sense: they simply never leave the device. |
| Outbox | The bookmark row carries `pending` (NONE / UPSERT / DELETE) and `onServer`. Edits coalesce on the row (create+edit = one create, create+delete before a push = row gone, edit+delete = one delete). A deleted row stays as a tombstone until the server confirmed it. |
| Push | Before pull, in `SyncWorker`. UPSERT: `onServer` → PATCH, 404 → POST; else POST (idempotent). DELETE: DELETE, 404 = done. Completion is only recorded if the row did not change meanwhile (`rev` compare). A 404 on the item (title gone) keeps the op pending and does not block others; other errors fail the run (retry). |
| Pull merge | Server list replaces SYNCED book rows: new server rows inserted; SYNCED + `onServer` rows missing on the server are deleted (removed elsewhere); a SYNCED row takes the server title. Pending rows always win (local edit/delete is newer than what we pulled). Pending UPSERT whose server row vanished is re-created (an edit beats a remote delete – nothing is lost). Episode rows are never touched by the merge. |
| History source | Server sessions per title (cached in Room `history_sessions`, replaced per title after each successful fetch, newest 100) + all local sessions of that key (`local_sessions`, synced or not). Deduped by session id (local wins only while not yet synced, so its timing is the freshest), sorted by `startedAt` desc. Sessions under 5 s of listening are hidden. |
| History fetch | When the detail page or the player's history sheet opens and the server is online, in the view model's coroutine; offline shows the cache + local rows. Failure keeps the cache. |
| History tap | Plays the title from the session's end position (`currentTime`): seek if it is loaded, otherwise `play(key, sec)`. |
| Queue | Room `queue` (serverId, itemKey) PK, `position`. Device-local, survives restarts; no duplicates (adding a queued title moves nothing). Reorder with up/down buttons (no drag library), remove, clear. |
| Auto-advance | `PlayerController.titleEnded` (once per natural end of the loaded title, from `STATE_ENDED`). `QueueAdvancer` (app singleton, like the prefetcher): picks the first queued entry that is not the ended title and is playable now (downloaded, or the server is online); unplayable entries stay. Removes the ended title and the started one from the queue. **Queue wins**: with a playable queued entry the next title starts; with none, the existing end flow stays (finished card with the "next volume" suggestion; no auto start). No advance while the sleep timer runs in "end of chapter" mode. |
| Play all | Collection/playlist page: plays the first title and **replaces** the queue with the rest (in list order). |
| Collections/playlists cache | Room `collections` (serverId, id, kind, libraryId, name, description, lastUpdate) + `collection_entries` (serverId, collectionId, position, itemKey). Synced in `SyncWorker` after the library sync (best effort, collections only for book libraries, one `/api/playlists` call) and once when the tab opens online. Collections are replaced per fetched library, playlists all at once. Playlist episodes unknown locally are inserted (row + audio file) so they can be played without opening the podcast first. Titles not (yet) in the local library are not shown. |
| Where | Library tab gets two more tabs: "Sammlungen" and "Playlists" (list with name + count). Opening one = new page `Collection(id)`: header, "Alle abspielen", books as `CoverTile` grid, episodes as rows. |
| Queue entry points | Long-press sheet (books), episode long-press sheet, detail page menu. Queue sheet from the player. |
| Bookmark/history UI | Player: buttons "Lesezeichen", "Warteschlange", "Verlauf" → sheets. Detail page (books): sections "Lesezeichen" and "Hörverlauf" above the chapters. |

## Schema (v7, AutoMigration 6 → 7, new tables only)

```kotlin
@Entity("bookmarks", primaryKeys = ["serverId", "itemKey", "timeSec"])
data class BookmarkEntity(serverId, itemKey, timeSec: Double, note: String?, createdAt: Long,
    pending: BookmarkPending = NONE, onServer: Boolean = false, rev: Long = 0)
@Entity("queue", primaryKeys = ["serverId", "itemKey"]) data class QueueEntity(serverId, itemKey, position: Int, addedAt: Long)
@Entity("history_sessions", primaryKeys = ["serverId", "id"], indices = [Index("serverId", "itemKey")])
data class HistorySessionEntity(serverId, id, itemKey, startedAt, updatedAt, startTimeSec, currentTimeSec, timeListeningSec, deviceName: String?)
@Entity("collections", primaryKeys = ["serverId", "id"]) data class CollectionEntity(serverId, id, kind: CollectionKind, libraryId, name, description?, lastUpdate)
@Entity("collection_entries", primaryKeys = ["serverId", "collectionId", "position"]) data class CollectionEntryEntity(serverId, collectionId, position, itemKey)
```

## Files

- `abs-api`: `me/MeApi.kt` (+ bookmarks, item listening sessions), `me/BookmarkModels.kt`, `me/SessionModels.kt`, `library/CollectionModels.kt`, `LibraryApi` (+ collections, playlists), lenient list serializers.
- `db/entities/Phase3bEntities.kt`, `db/dao/BookmarkDao.kt`, `QueueDao.kt`, `HistoryDao.kt`, `CollectionDao.kt`; DB v7; `EpisodeDao.insertIgnore`.
- `bookmarks/BookmarkMerge.kt` (pure), `bookmarks/BookmarkRepository.kt` (local ops), `sync/BookmarkSync.kt`.
- `history/HistoryAggregation.kt` (pure), `history/HistoryRepository.kt`.
- `queue/QueueOrder.kt` (pure), `queue/QueueRepository.kt`, `playback/QueueAdvancer.kt` (+ `AdvancePolicy`, pure).
- `sync/CollectionSync.kt`; `SyncWorker` runs bookmark + collection sync.
- `PlayerController.titleEnded`, `PlayerSnapshot.isEnded`.
- UI: `ui/player/BookmarkSheet.kt`, `QueueSheet.kt`, `HistorySheet.kt` (shared rows in `ui/common/TitleExtras.kt`), detail sections, `ui/collections/*` (tabs content, page, VM), nav route `Collection`.

## Tests (TDD, red first)

- `MeApiTest`/`LibraryApiTest`: bookmarks list (+ fallback shape in `/api/me`), create/update/delete paths and bodies (delete path `123.5`), item listening sessions (episode path, string `timeListening`), collections (books → ids), playlists (book + episode items, lenient rows).
- `BookmarkMergeTest`: insert new server rows, delete synced rows missing on server, server title wins for synced, pending upsert/delete win, pending upsert re-created when vanished, episode rows untouched, default-title ↔ note round trip.
- `BookmarkDaoTest`/`BookmarkSyncTest`: outbox coalescing (create+delete before push removes the row; edit after push re-pends), push order and 404 handling, completion only if unchanged (`rev`), pull after push.
- `HistoryAggregationTest`: dedupe by id, unsynced local wins, sort, short sessions hidden, total.
- `QueueOrderTest` / `QueueDaoTest`: add appends without duplicates, move up/down, remove compacts, replace.
- `AdvancePolicyTest`: empty queue → none, skips the ended key, skips unplayable, sleep "end of chapter" blocks.
- `PlayerControllerTest`: `titleEnded` emits once per end, again after a replay.
- `CollectionSyncTest`: replace per library, playlists with episodes insert missing episode rows, failure keeps cache.
- `MigrationTest`: v6 → v7 keeps data and the new tables work.
- UI: player sheets open, detail sections render, library collections tab lists and opens.
