# Phase 4A – Android Auto, Sprachsteuerung, Wiedergabe-Fortsetzung (Plan)

Scope: spec 2.5 (Pause/Weiter über Android Auto), 2.7 (Android Auto ist Pflicht; „Play from search“: „Spiel X in
Lauschig“, Pause, Weiter, „Was läuft gerade?“), offline first (2.1, 6a). Global constraints of the phase-1 plan apply
(strings in `values/` and `values-de/`, nothing on a start path awaits network I/O, an expired token never blocks
playback of local titles). Fabian listens 2 h a day in the car: everything here must be car-safe (no dead taps, no
position loss from a stray button, works offline).

Starting point: `PlaybackService` builds its `MediaLibrarySession` with the default `Callback {}`. Legacy browsers
(Android Auto, SystemUI media resumption, Assistant) get no root, SystemUI logs `MediaBrowser onConnectFailed`.

## Browse tree

At most 4 root children, all browsable (AA shows them as tabs and asks for browsable-only roots):

| Node | Media id | Children | Style |
|---|---|---|---|
| Weiterhören | `continue` | in-progress books of the account + started episodes + in-progress device titles, newest progress first (hidden-from-continue excluded) | list |
| Heruntergeladen | `downloads` | completely downloaded books of the account (title order) | list |
| Serien | `series` | series of the account's non-podcast libraries (name, cross-library like the app's series pages); series with recent activity first, then by name | grid (category) |
| ↳ one series | `series/<url-encoded name>` | volumes in sequence order (missing-volume placeholders left out) | list |
| Bibliotheken | `libraries` | each server library (display order) + „Auf dem Gerät“ when device titles exist | list (category) |
| ↳ book library | `library/<libraryId>` | titles in natural title order (paged; 241 Hörspiele) | grid |
| ↳ podcast library | `library/<libraryId>` | podcasts (browsable) | grid |
| ↳ podcast | `podcast/<itemId>` | stored episodes, newest first | list |
| ↳ device library | `library/device` | device titles, natural order | grid |
| Recent root | `recent` | the resumption title (one playable) — for non-SystemUI callers that ask with `isRecent` (SystemUI is answered by Media3 itself once a `MediaButtonReceiver` exists) | – |

Playable title: `title/<serverId>/<key>` (`serverId` = 16 hex or `device`, `key` = ABS id, `item~episode`, or
`local:<hex>`). Every id is validated on the way back (unknown prefix, wrong shape, another account's server, a
`local:` key under a server id → not found). Free text (series name) is URL-encoded, so `/` never splits it.

Paging: every list is built from Room and sliced `[page * pageSize, +pageSize)` with long arithmetic
(`pageSize = Int.MAX_VALUE` from unpaged legacy loads must not overflow).

Offline: lists show only titles that play without the server (complete download or device title); series and
library folders without any such title stay visible but list only playable titles. A connection change and
library/download changes (debounced) call `notifyChildrenChanged` for the root nodes, so AA refreshes.

Per item: title, subtitle (in progress: "noch 2 h 5 min", else author; volume: "Band 3 · author"), `artworkUri`
(content URI, see covers), media type (audiobook / episode / folder), completion status + percentage, download status
(AA badges), content-style hints on folders.

## Playing from the car (one rule for app and car)

- `ServiceTitleStarter` is split into `resolve(serverId, key)` → items, start index and offset (same rules as
  today: local files first, else the online server, details fetched when the files are unknown, start at the saved
  position unless finished) + `start()` (resolve + set queue). New option `rewindAfterPause` (the app's rule: jump
  back after a long pause, from the progress `lastUpdate`), used for car and resumption starts; the queue advance
  keeps rewind 0 (it continues seamlessly). Speed per title is applied by `PlaybackComfort` on the media item
  transition (unchanged). Download on play (`PlayDownloadStarter.start`) after a streaming car start.
- `onSetMediaItems`: items that already carry a URI (the app's own `MediaController`, its `replaceMediaItem`) pass
  through unchanged. Otherwise the request item (`startIndex` or 0) is resolved: a `title/…` id → that title; a
  `RequestMetadata.searchQuery` (also empty) → play-from-search resolution. One title becomes the player queue (its
  files); later titles come from Lauschig's own queue (QueueAdvancer), never from a car list.
- `onAddMediaItems` with unresolved items: refused (a car "add to queue" would mix titles in the file queue).
- Failure (offline + not downloaded, unknown id, details fail): `sendError` with a short localized message (AA
  shows it); nothing else changes, the previous title stays loaded.
- `PlayerControllerImpl` adopts a title the service started while the app had nothing loaded (today it only adopts
  when it already had a title): `refresh()` adopts when `current == null` and the player reports a title, and the
  service calls `PlayerController.adoptFromService()` (connects the app's controller) on every title change it did
  not cause. This keeps the UI, the mini player and the sleep timer (which reads the controller state) in sync.

## Voice

- `onSearch` / `onGetSearchResult`: `Catalog.search` over the account catalog + device titles (word prefix, no
  network); titles first, then series hits as browsable folders, capped at 50.
- Play from search (`RequestMetadata.searchQuery`, Assistant's `EXTRA_MEDIA_FOCUS` / title / artist extras):
  1. empty query → the last title on this device (`LastTitleStarter.last()`), else the first of "Weiterhören";
  2. a series whose name matches the query exactly or by all words → its volume to listen to now: the in-progress
     volume with the newest progress, else the first unfinished volume, else volume 1;
  3. title hits → the best one (exact title, then in-progress, then title order);
  4. author hits → the author's in-progress title, else their first title;
  offline, only playable titles count. Nothing found → error "Nichts gefunden".
- Phone voice without the car: `MainActivity` handles `android.media.action.MEDIA_PLAY_FROM_SEARCH` with the same
  resolution and plays through `PlayerController`.
- Pause, resume, next/previous, "Was läuft gerade?" use the standard session (title, author, chapter as subtitle).

## Playback resumption

- `androidx.media3.session.MediaButtonReceiver` in the manifest: Bluetooth/car "play" with a dead process starts the
  service; Media3 then calls `onPlaybackResumption`, and answers SystemUI's recent root itself.
- `onPlaybackResumption(isForPlayback)`: the title from `LastTitleStarter.last()` (this device, signed-in account or
  device title, not finished/hidden/orphaned) at its saved position (with the rewind rule). No last title → failure
  (Media3 then just plays nothing). `isForPlayback = false` (boot, SystemUI card): metadata only (title, author,
  content-URI artwork, completion), no server needed.

## Car controls

Media button preferences (session-wide: AA, notification, SystemUI controls):

| Slot | Button | Command |
|---|---|---|
| back | −30 s (`ICON_SKIP_BACK_30`) | `lauschig.SEEK_BACK` (crosses file boundaries, never before the book start) |
| forward | +30 s (`ICON_SKIP_FORWARD_30`) | `lauschig.SEEK_FORWARD` (crosses file boundaries, never past the end) |
| overflow | speed (icon of the current step) | `lauschig.SPEED`: 1.0 → 1.2 → 1.5 → 1.8 → 2.0 → 1.0 (others go to the next step up) |
| overflow | sleep timer (own icon, filled while active) | `lauschig.SLEEP`: off → 15 → 30 → 60 min → end of chapter → off |
| overflow | previous chapter / chapter start | `lauschig.CHAPTER_BACK` (>3 s into the chapter: its start, else the previous one) |
| overflow | next chapter | `lauschig.CHAPTER_NEXT` |

The labels of speed and sleep are updated when they change. The session player is a `ForwardingPlayer` that maps
`seekToNext/Previous(MediaItem)` and `seekBack/Forward` to the same ±30 s steps: a steering-wheel or headset "previous"
on a one-file m4b must never jump to the start of the book (ExoPlayer's default). Chapter-aware seek bars do not exist
in AA; the chapter title is published as `subtitle`/`displayTitle` metadata of the playing item (updated in place via
`replaceMediaItem` with the same URI, which ExoPlayer applies without re-preparing).

## Covers in the car

`CoverProvider` (`${applicationId}.covers`, not exported, `grantUriPermissions`):
`content://<authority>/cover/<serverId>/<key>/<version>`. Resolution: downloaded cover (`DownloadFiles.playableCover`),
device cover (`LocalCovers`), cached server cover (`cacheDir/car_covers`), else — only while online — fetched once with
the authenticated client of the online server (bounded parallelism, short timeouts), cached, served; otherwise
`FileNotFoundException`. Path parts are validated by regex, never used as a path. Read access: a prefix grant
(`FLAG_GRANT_PREFIX_URI_PERMISSION`) of `content://<authority>/cover/` to every allowed non-own controller on
connect (AA, SystemUI, Automotive media, Assistant). Browse items, search results, resumption metadata and the playing
items (`MediaItemFactory`) all use these URIs, so streamed titles get artwork too.

## Who may connect (`CarAccess`)

Full access (browse, search, custom commands): own app, `isTrusted` callers (system apps, `MEDIA_CONTENT_CONTROL`),
Android Auto (`com.google.android.projection.gearhead`), Automotive media (`com.android.car.media`, launcher),
Assistant (`com.google.android.googlequicksearchbox`), SystemUI. Every other app: playback control only (Media3 default
player commands, no library commands, no custom commands, no cover grant), so an unknown app can pause but cannot
read the library.

## Manifest

`res/xml/automotive_app_desc.xml` (`<uses name="media"/>`) + `com.google.android.gms.car.application` meta-data;
`MediaButtonReceiver`; `CoverProvider`; the service keeps both intent filters (`androidx.media3.session.MediaLibraryService`,
`android.media.browse.MediaBrowserService`); `MainActivity` gets `MEDIA_PLAY_FROM_SEARCH`.

## Files

- `auto/MediaIds.kt`, `auto/Paging.kt` – id scheme, page slicing.
- `auto/CarLibrary.kt` – the tree from Room (pure entries), `auto/CarMediaItems.kt` – entries → Media3 items.
- `auto/CarSearch.kt` – search + play-from-search resolution.
- `auto/CarControls.kt` – speed/sleep cycles, ±30 s steps, chapter targets, button list.
- `auto/CarAccess.kt` – caller policy. `auto/CoverUris.kt`, `auto/CoverProvider.kt`, `auto/CoverStore.kt` – covers.
- `auto/LibraryCallback.kt` – the `MediaLibrarySession.Callback` (glue only). `auto/ChapterTracker.kt` – chapter of
  the playing title in the service (subtitle, chapter commands). `playback/CarSafePlayer.kt` – the ForwardingPlayer.
- Changed: `PlaybackService`, `ServiceTitleStarter`, `PlayerControllerImpl`/`PlayerController`/`PlayerFacade`/
  `MediaControllerFacade`, `MediaItemFactory` (artwork URI, display title), `MainActivity`, manifest, strings.

## Tests

Robolectric (TDD, red → green):
- `MediaIdsTest`: round trips (episode key, `local:` key, series names with `/`, umlauts), malformed ids, foreign servers.
- `PagingTest`: pages, last partial page, out of range, `Int.MAX_VALUE` page size.
- `CarLibraryTest` (in-memory Room): root = 4 browsable nodes; continue mixes account + device + episodes by recency,
  hides hidden/finished; downloads; series list order + volumes in sequence; libraries incl. device node, paging of a
  big library; podcast → episodes; offline hides streaming-only titles; `getItem` for titles and folders.
- `CarSearchTest`: title/author/series hits; play-from-search: empty → last title, series → in-progress volume / first
  unfinished, title, author, offline skips streaming-only, nothing found.
- `CarResumptionTest`: last title of this device at its position (+ rewind), other account / finished → none.
- `CarControlsTest`: speed cycle, sleep cycle, ±30 s across files and clamps, chapter back/next targets.
- `CarAccessTest`, `CoverUrisTest`, `CoverStoreTest` (local before cache before fetch, offline → none).
- `ServiceTitleStarterTest`: `resolve` with rewind, failure reasons. `PlayerControllerTest`: a title the service
  started with nothing loaded in the app is adopted.
- `LibraryCallbackTest`: own-app items pass through unchanged; a `title/…` request resolves to the file queue.

Instrumented (`app/src/androidTest`, emulator-5554 with the logged-in test server): `CarBrowseTest` – a Media3
`MediaBrowser` and the platform `MediaBrowser` (the legacy path AA and SystemUI use) connect to `PlaybackService`, get the
root, the children of every root node, search "Testreihe", play a title from the tree; then pause. Afterwards
`logcat`: SystemUI's `MediaResumeListener` no longer logs `onConnectFailed`.
