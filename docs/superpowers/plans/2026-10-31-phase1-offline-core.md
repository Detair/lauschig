# Lauschig Phase 1 – Fundament, Offline-Wiedergabe, Bibliothek – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Am Ende von Phase 1 steht eine installierbare Android-App. Sie meldet sich an Audiobookshelf an, synchronisiert die Bibliothek in eine lokale Datenbank, lädt Bücher herunter und spielt sie mit Kapiteln ab. Sie startet und spielt auch **ohne jede Server-Verbindung**. Das Release-Bundle ist bereit für den geschlossenen Play-Test der Familie.

**Architecture:** Es gibt zwei Gradle-Module. Das reine Kotlin/JVM-Modul `:abs-api` kapselt das ABS-HTTP-API und lässt sich mit MockWebServer schnell testen. Das Android-Modul `:app` enthält Room (die einzige Quelle für die UI), Sync, Downloads (WorkManager), Wiedergabe (Media3 `MediaLibraryService`) und die Compose-UI. Die UI liest ausschließlich aus Room. Netzwerkarbeit läuft im Hintergrund und schreibt nur nach Room. Kein UI-Pfad wartet auf das Netz.

**Tech Stack:** Kotlin, Jetpack Compose + Material 3, Hilt, Room, DataStore + Tink, Retrofit + OkHttp + kotlinx.serialization, WorkManager, Media3 (ExoPlayer, Session), Coil 3, JUnit 4, MockWebServer, Robolectric, Turbine, Compose UI Test.

**Spec:** `docs/superpowers/specs/2026-10-31-lauschig-design.md`

## Phasen-Roadmap (je ein eigener Plan, jeder lauffähig)

| Phase | Inhalt (Spec-Abschnitte) |
|---|---|
| **1 (dieser Plan)** | Fundament, Login (Passwort), lokale/öffentliche Adresse, Room, Sync von Fortschritt und Offline-Sessions, Downloads, Wiedergabe mit Kapiteln, Bibliothek, Detail, Mini-Player und Player, Filter-Chips, Offline-Hinweis, Release-Bundle (2.1, 2.2 teilweise, 2.2a Filter, 2.4 ohne Verlinkung, 2.5 Basis, 5, 6, 6a) |
| 2a (erledigt, `2026-11-01-phase2a-browse.md`) | Start-Bildschirm vollständig, Entdecken (Store), Suche, Autor-/Sprecher-/Serien-/Genre-/Tag-Seiten, Serienseite mit Lücken aus der eigenen Bibliothek (2.2, 2.3, 2.4) |
| 2b (erledigt, `2026-11-01-phase2b-series-podcasts-sso.md`) | Fehlende Bände aus externen Metadaten + RMAB-Anfrage, Podcasts (inkl. Filter-Chip), OIDC-Login (2.2a, 2.3, 6a) |
| 3 | Player-Komfort: Sleep-Timer (Fade, Schütteln), Lesezeichen, Hörverlauf, Warteschlange, Sammlungen/Playlists, Geschwindigkeit pro Buch, Stille überspringen, Lautstärke-Boost, Zurückspringen nach Pause, Auto-Resume bei Bluetooth/Auto, Download-Verwaltung, lokale Ordner (Dateien, die nur auf dem Gerät liegen, ohne Server abspielen), nächster Band (vorschlagen/vorladen), „Stand vom Server holen“, wöchentliche Aufräum-Erinnerung (2.5, 2.7a, 2.7b) |
| 4 | Android-Integration: Android Auto, Homescreen-Widget, Sprachsteuerung (Play from search), App Links, Verbindungsstatus, Admin-Menü, Optik-/Navigations-Einstellungen, „Was ist neu“, Tablet-/Foldable-Layouts (2.7, 2.7a) |
| 5 | Stats, Achievements, Jahresrückblick (2.6) |
| 6 | AppFunctions für Gemini, sobald stabil, und TV-Modus (2.7, 2.8) |

## Global Constraints

- Nur Android. `minSdk = 28`, `targetSdk`/`compileSdk` = aktuelles stabiles Android-Release.
- Paket-ID / `applicationId` / Namespace: `io.wolftown.lauschig` (API-Modul: `io.wolftown.lauschig.absapi`).
- App-Name: **Lauschig**. Lizenz **GPL-3.0-or-later** (`LICENSE` im Repo-Root, SPDX-Header nicht nötig).
- Alle Bibliotheksversionen: neueste **stabile** Version zum Zeitpunkt von Task 1, zentral in `gradle/libs.versions.toml`. Keine Alpha-/Beta-Versionen außer bei Media3, wenn eine benötigte API nur dort existiert (dann begründen).
- Alle nutzersichtbaren Texte als String-Ressourcen in `values/strings.xml` (Englisch, Default) **und** `values-de/strings.xml` (Deutsch). Keine hart codierten UI-Strings.
- **Offline zuerst:** Kein Code im Startpfad (Application, MainActivity, erste Composition, Wiedergabe lokaler Titel) darf Netz-I/O awaiten oder auf ein Netz-Ergebnis blockieren. Netzwerk läuft nur in Hintergrund-Coroutinen bzw. WorkManager.
- Ein abgelaufener oder ungültiger Token darf nie die Wiedergabe oder die Anzeige lokaler Daten verhindern. Ein 401 führt höchstens zu einem Hinweis „Bitte neu anmelden“, niemals zum Logout oder zum Löschen von Daten.
- Unterstützte ABS-Server: 2.x mit dem neuen Token-Schema (`x-return-tokens: true` beim Login, Refresh über `POST /auth/refresh`). Referenzserver: Fabians ABS v2.37.1.
- Netzwerk: System-CAs und vom Nutzer installierte CAs (`network_security_config.xml` mit `<certificates src="user"/>`). Cleartext-HTTP ist nur für Adressen erlaubt, die der Nutzer beim Einrichten ausdrücklich bestätigt hat (Dialog). Technisch wird cleartext global erlaubt, die Bestätigung erzwingt die App.
- Heruntergeladen werden die Originaldateien (kein Transcode), im App-eigenen Speicher (`context.filesDir/downloads/<itemId>/`).
- Fortschritt zwischen Geräten: Der neueste `lastUpdate` gewinnt. Springt die Position dadurch um mehr als 60 s, wird ein `PositionJump`-Ereignis mit der alten Position erzeugt (die UI bietet dann „Zurück zu hh:mm:ss“ an).
- Navigation unten: vier Reiter **Start · Bibliothek · Entdecken · Suche**, jeweils Icon **und** Text. In Phase 1 zeigen Entdecken und Suche nur einen Platzhalter „Kommt bald“.
- Repo `github.com/detair/lauschig`, öffentlich. Commit-Nachrichten nach Conventional Commits.

## Review Focus

1. **Kaltstart ohne Server und mit abgelaufenem Token:** Die App zeigt die Bibliothek aus Room und spielt Heruntergeladenes sofort. Kein Spinner, kein Login-Screen. *(Test in Task 11: `coldStart_serverUnreachable_showsLibraryAndPlays`.)*
2. **Abgebrochener Download** (App gekillt, Netz weg mitten in einer Datei): Eine Teildatei gilt nie als heruntergeladen, der Download wird fortgesetzt bzw. neu begonnen, und die Wiedergabe nutzt nur vollständige Dateien. *(Test in Task 8: `interruptedDownload_isNotMarkedComplete_andResumes`.)*
3. **Mehrteilige mp3-Bücher:** Kapitel, Position und Fortschritt stimmen über Dateigrenzen hinweg (globale Zeit ↔ Datei-Index/Offset). *(Test in Task 9: `globalPosition_mapsAcrossFileBoundaries`.)*
4. **Offline gehört, Server inzwischen weiter (anderes Gerät):** Der neueste Stand gewinnt, die alte Position geht nicht verloren, offline gehörte Zeit wird als Session hochgeladen. *(Tests in Task 7: `newerServerProgress_winsAndEmitsJump`, `offlineSessions_uploadedOnceAndMarkedSynced`.)*
5. **Lokale Adresse im Heim-WLAN nicht erreichbar** (Server-Neustart, falsche IP): Die App fällt auf die öffentliche Adresse zurück, sonst still offline. Kein Fehlerdialog. *(Test in Task 4: `localUnreachable_fallsBackToPublic_thenOffline`.)*

---

### Task 0: Toolchain auf der Workstation

Auf `detair` (CachyOS) fehlen JDK und Android-SDK. Für `pacman` braucht es sudo, **das führt Fabian selbst aus**.

- [ ] **Step 1: JDK 21 installieren (Fabian)**

```bash
sudo pacman -S --needed jdk21-openjdk
```

- [ ] **Step 2: Android-Commandline-Tools nach `~/Android/Sdk` (ohne sudo)**

Die aktuellen „Command line tools only“ für Linux von developer.android.com/studio laden, nach `~/Android/Sdk/cmdline-tools/latest/` entpacken. In `~/.zshrc`: `export ANDROID_HOME=$HOME/Android/Sdk` und `PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH`.

- [ ] **Step 3: SDK-Pakete installieren**

Run: `sdkmanager --licenses && sdkmanager "platform-tools" "platforms;android-<aktuell>" "build-tools;<aktuell>"`
Expected: `adb version` und `java -version` (21) laufen ohne Fehler.

---

### Task 1: Projekt-Gerüst, Lizenz, CI

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, Gradle-Wrapper
- Create: `abs-api/build.gradle.kts` (Kotlin JVM + serialization)
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/io/wolftown/lauschig/LauschigApp.kt` (`@HiltAndroidApp`), `MainActivity.kt` (leeres Compose-Theme)
- Create: `app/src/main/res/values/strings.xml`, `values-de/strings.xml` (`app_name` = Lauschig)
- Create: `LICENSE` (GPL-3.0-Volltext), `README.md` (kurz: was, warum, Status, Lizenz), `.gitignore` (inkl. `.superpowers/`, `local.properties`, `*.keystore`)
- Create: `.github/workflows/ci.yml`
- Test: `app/src/test/java/io/wolftown/lauschig/SmokeTest.kt`

**Interfaces:**
- Produces: Module `:abs-api`, `:app`, und `:app` hängt von `:abs-api` ab. Version-Catalog-Aliase für alle Bibliotheken aus dem Tech-Stack.

- [ ] **Step 1: Smoke-Test schreiben:** `SmokeTest.appIdIsCorrect()` prüft `BuildConfig.APPLICATION_ID == "io.wolftown.lauschig"`.
- [ ] **Step 2: Gerüst anlegen** (Dateien oben, Compose, Hilt, KSP für Room/Hilt, `buildConfig = true`).
- [ ] **Step 3: Build und Tests lokal**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest :abs-api:test`
Expected: BUILD SUCCESSFUL, SmokeTest grün.

- [ ] **Step 4: CI:** In `ci.yml` läuft bei Push und PR auf `ubuntu-latest`: JDK 21, `./gradlew lint testDebugUnitTest :abs-api:test assembleDebug`, die Debug-APK wird als Artefakt hochgeladen.
- [ ] **Step 5: Commit** `chore: scaffold lauschig android project (GPL-3.0)`

---

### Task 2: ABS-API – Auth

**Files:**
- Create: `abs-api/src/main/kotlin/io/wolftown/lauschig/absapi/AbsJson.kt` (`Json { ignoreUnknownKeys = true; explicitNulls = false }`)
- Create: `.../absapi/auth/AuthApi.kt`, `.../absapi/auth/AuthModels.kt`
- Create: `.../absapi/AbsClientFactory.kt`
- Test: `abs-api/src/test/kotlin/.../auth/AuthApiTest.kt`, Fixtures unter `abs-api/src/test/resources/fixtures/login.json`, `refresh.json`

**Interfaces:**
- Produces:
  - `data class LoginRequest(val username: String, val password: String)`
  - `data class LoginResponse(val user: AbsUser, val userDefaultLibraryId: String?, val serverSettings: ServerSettings?)`
  - `data class AbsUser(val id: String, val username: String, val type: String, val accessToken: String?, val refreshToken: String?, val mediaProgress: List<MediaProgressDto> = emptyList())`
  - `interface AuthApi { @POST("login") @Headers("x-return-tokens: true") suspend fun login(@Body r: LoginRequest): LoginResponse; @POST("auth/refresh") suspend fun refresh(@Header("x-refresh-token") refreshToken: String): LoginResponse; @GET("ping") suspend fun ping(): PingResponse }`
  - `data class PingResponse(val success: Boolean)`
  - `object AbsClientFactory { fun retrofit(baseUrl: HttpUrl, client: OkHttpClient): Retrofit }`

- [ ] **Step 1: Failing Tests**
  - `login_sendsReturnTokensHeader_andParsesTokens`: MockWebServer liefert `login.json`. Assert: Request-Header `x-return-tokens == "true"`, Pfad `/login`, `user.accessToken` und `user.refreshToken` nicht null.
  - `refresh_sendsRefreshHeader`: Assert: Pfad `/auth/refresh`, Header `x-refresh-token == "r1"`.
  - `unknownFields_areIgnored`: Fixture mit Zusatzfeldern parst ohne Exception.
- [ ] **Step 2:** `./gradlew :abs-api:test` muss rot sein (Klassen fehlen).
- [ ] **Step 3:** Implementieren. Fixtures aus einer echten ABS-2.37-Antwort ableiten (Tokens/IDs anonymisieren).
- [ ] **Step 4:** `./gradlew :abs-api:test` ist grün.
- [ ] **Step 5: Commit** `feat(abs-api): login, token refresh and ping`

---

### Task 3: ABS-API – Bibliotheken, Items, Fortschritt, Sessions

**Files:**
- Create: `.../absapi/library/LibraryApi.kt`, `LibraryModels.kt`
- Create: `.../absapi/me/MeApi.kt`, `MeModels.kt`
- Test: `.../library/LibraryApiTest.kt`, `.../me/MeApiTest.kt`, Fixtures `libraries.json`, `library-items-page.json`, `item-expanded-m4b.json`, `item-expanded-multimp3.json`, `me.json`

**Interfaces:**
- Consumes: `AbsJson`, `AbsClientFactory` (Task 2)
- Produces:
  - `data class LibraryDto(val id: String, val name: String, val mediaType: String /* "book" | "podcast" */, val displayOrder: Int)`
  - `data class LibraryItemsPage(val results: List<LibraryItemDto>, val total: Int, val limit: Int, val page: Int)`
  - `data class LibraryItemDto(val id: String, val libraryId: String, val mediaType: String, val updatedAt: Long, val media: BookMediaDto)`
  - `data class BookMediaDto(val metadata: BookMetadataDto, val duration: Double?, val chapters: List<ChapterDto> = emptyList(), val audioFiles: List<AudioFileDto> = emptyList(), val tags: List<String> = emptyList())`
  - `data class BookMetadataDto(val title: String, val subtitle: String?, val authorName: String?, val narratorName: String?, val seriesName: String?, val series: List<SeriesRefDto> = emptyList(), val genres: List<String> = emptyList(), val description: String?)`
  - `data class SeriesRefDto(val id: String, val name: String, val sequence: String?)`
  - `data class ChapterDto(val id: Int, val start: Double, val end: Double, val title: String)`
  - `data class AudioFileDto(val index: Int, val ino: String, val duration: Double, val metadata: FileMetadataDto)` with `FileMetadataDto(val filename: String, val ext: String, val size: Long)`
  - `data class MediaProgressDto(val libraryItemId: String, val episodeId: String?, val currentTime: Double, val duration: Double, val progress: Double, val isFinished: Boolean, val lastUpdate: Long)`
  - `data class ProgressUpdate(val currentTime: Double, val duration: Double, val progress: Double, val isFinished: Boolean)`
  - `data class LocalSessionDto(val id: String, val libraryItemId: String, val episodeId: String?, val mediaType: String, val displayTitle: String, val duration: Double, val startTime: Double, val currentTime: Double, val timeListening: Double, val startedAt: Long, val updatedAt: Long, val deviceInfo: DeviceInfoDto)`
  - `interface LibraryApi { @GET("api/libraries") suspend fun libraries(): LibrariesResponse; @GET("api/libraries/{id}/items") suspend fun items(@Path("id") libraryId: String, @Query("limit") limit: Int, @Query("page") page: Int, @Query("minified") minified: Int = 1): LibraryItemsPage; @GET("api/items/{id}") suspend fun item(@Path("id") id: String, @Query("expanded") expanded: Int = 1): LibraryItemDto }`
  - `interface MeApi { @GET("api/me") suspend fun me(): AbsUser; @PATCH("api/me/progress/{itemId}") suspend fun updateProgress(@Path("itemId") itemId: String, @Body u: ProgressUpdate); @POST("api/session/local-all") suspend fun syncLocalSessions(@Body body: LocalSessionsBody): LocalSessionsResult }`
  - Download- und Cover-URLs als reine Funktionen: `fun audioFileUrl(base: HttpUrl, itemId: String, ino: String): HttpUrl` → `api/items/{id}/file/{ino}/download`, `fun coverUrl(base: HttpUrl, itemId: String): HttpUrl` → `api/items/{id}/cover`

- [ ] **Step 1: Failing Tests**
  - `items_paginates_andParsesSeriesSequence`: Fixture-Seite, Assert `results[0].media.metadata.series[0].sequence == "3"`, Query `limit` und `page` gesetzt.
  - `itemExpanded_m4b_hasChapters`: Assert `chapters.size > 1`, `audioFiles.size == 1`.
  - `itemExpanded_multiMp3_hasOrderedAudioFiles`: Assert `audioFiles.map { it.index }` aufsteigend.
  - `localAll_postsSessions`: Body enthält `sessions` mit `timeListening`.
  - `audioFileUrl_buildsDownloadPath`: Assert `encodedPath == "/api/items/li_1/file/123/download"`, auch mit Base-URL inklusive Subpfad (`https://h/abs/`).
- [ ] **Step 2:** Tests rot.
- [ ] **Step 3:** Implementieren. Der Auth-Header wird nicht hier gesetzt, das macht der Interceptor in Task 5.
- [ ] **Step 4:** `./gradlew :abs-api:test` ist grün.
- [ ] **Step 5: Commit** `feat(abs-api): libraries, items, progress and local sessions`

---

### Task 4: Server-Adressen und Erreichbarkeit

**Files:**
- Create: `app/src/main/java/io/wolftown/lauschig/connection/ServerAddresses.kt`
- Create: `.../connection/AddressResolver.kt`
- Create: `.../connection/ConnectionState.kt`
- Create: `.../connection/AndroidNetworkInfo.kt` (ConnectivityManager-Callback, SSID über `WifiInfo` mit Berechtigung `ACCESS_FINE_LOCATION`/`NEARBY_WIFI_DEVICES`, nur wenn SSID-Bindung aktiv)
- Test: `app/src/test/java/io/wolftown/lauschig/connection/AddressResolverTest.kt`

**Interfaces:**
- Consumes: `AuthApi.ping()` (Task 2), gekapselt als `ReachabilityProbe`
- Produces:
  - `data class ServerAddresses(val local: HttpUrl?, val public: HttpUrl?, val homeSsids: Set<String> = emptySet())`
  - `fun interface ReachabilityProbe { suspend fun isReachable(url: HttpUrl): Boolean }` (Timeout 3 s, `GET ping` → `success == true`)
  - `interface NetworkInfo { val isConnected: Flow<Boolean>; suspend fun currentSsid(): String? }`
  - `sealed interface ConnectionState { data class Online(val baseUrl: HttpUrl, val via: Via) : ConnectionState; data object Offline : ConnectionState; enum class Via { LOCAL, PUBLIC } }`
  - `class AddressResolver(probe, networkInfo, clock) { suspend fun resolve(a: ServerAddresses): ConnectionState; fun states(a: ServerAddresses): Flow<ConnectionState> }`. `states` löst bei jedem Netzwechsel neu auf, sonst alle 60 s, solange offline.

Regeln: Kein Netz → `Offline` ohne Probe. Ist `homeSsids` gesetzt und die aktuelle SSID darin, zuerst die lokale Adresse probieren. Ist `homeSsids` gesetzt und die SSID nicht darin, die lokale überspringen. Ist `homeSsids` leer, zuerst die lokale probieren, dann die öffentliche. Sind beide nicht erreichbar → `Offline`.

- [ ] **Step 1: Failing Tests** (mit Fake-Probe und Fake-NetworkInfo)
  - `noNetwork_isOffline_withoutProbing` (Assert: Probe nie aufgerufen)
  - `homeSsid_prefersLocal`
  - `foreignSsid_skipsLocal_usesPublic` (Assert: lokale nie geprobt)
  - `localUnreachable_fallsBackToPublic_thenOffline` (lokal false → PUBLIC; beide false → Offline)
  - `onlyPublicConfigured_works`
- [ ] **Step 2:** Tests rot. `./gradlew :app:testDebugUnitTest --tests '*AddressResolverTest*'`
- [ ] **Step 3:** Implementieren.
- [ ] **Step 4:** Tests grün.
- [ ] **Step 5: Commit** `feat(connection): local/public address resolution with offline fallback`

---

### Task 5: Konto, Token-Speicher, Authenticator

**Files:**
- Create: `.../account/AccountStore.kt` (DataStore Proto/JSON, Tokens mit Tink-AEAD und Android-Keystore-Schlüssel verschlüsselt)
- Create: `.../account/Account.kt`
- Create: `.../net/AuthInterceptor.kt`, `.../net/TokenAuthenticator.kt`, `.../net/ApiProvider.kt`
- Test: `.../net/TokenAuthenticatorTest.kt` (MockWebServer + Fake-AccountStore)

**Interfaces:**
- Consumes: `AuthApi` (Task 2), `ConnectionState` (Task 4)
- Produces:
  - `data class Account(val serverId: String, val userId: String, val username: String, val isAdmin: Boolean, val addresses: ServerAddresses, val allowCleartext: Set<String>)`
  - `interface AccountStore { val account: Flow<Account?>; suspend fun save(a: Account, accessToken: String, refreshToken: String); suspend fun accessToken(): String?; suspend fun refreshToken(): String?; suspend fun updateTokens(access: String, refresh: String); suspend fun markNeedsReauth(); val needsReauth: Flow<Boolean>; suspend fun clear(keepDownloads: Boolean) }`
  - `class ApiProvider { val libraryApi: Flow<LibraryApi?>; val meApi: Flow<MeApi?> }`, abgeleitet aus `ConnectionState.Online.baseUrl`. Bei `Offline` ist es `null`.
  - `serverId` = Hash aus Server-Adresse + userId, stabil, Teil aller Room-Primärschlüssel (Task 6).

Regeln: Der Interceptor setzt `Authorization: Bearer <access>`. Bei 401 versucht der Authenticator genau einmal `refresh`, speichert die neuen Tokens und wiederholt den Request. Scheitert der Refresh mit 401/403 → `markNeedsReauth()`, kein Logout, keine Daten werden gelöscht. Netzfehler beim Refresh → kein Reauth-Flag.

- [ ] **Step 1: Failing Tests**
  - `expiredAccess_refreshesOnce_andRetries`
  - `refreshRejected_marksNeedsReauth_keepsAccount` (Assert: `account.first() != null`)
  - `refreshNetworkError_doesNotMarkReauth`
  - `concurrent401s_triggerSingleRefresh` (zwei parallele Requests → genau ein `/auth/refresh`)
- [ ] **Step 2:** rot. **Step 3:** implementieren. **Step 4:** grün.
- [ ] **Step 5: Commit** `feat(account): encrypted token store and refresh authenticator`

---

### Task 6: Room-Schema

**Files:**
- Create: `.../db/LauschigDatabase.kt`, `.../db/entities/*.kt`, `.../db/dao/*.kt`, `.../db/Converters.kt`
- Create: `app/schemas/` (Room-Schema-Export, wird committet)
- Test: `app/src/test/java/io/wolftown/lauschig/db/DaoTest.kt` (Robolectric, In-Memory)

**Interfaces:**
- Produces (alle Entities haben `serverId` als Teil des Schlüssels):
  - `LibraryEntity(serverId, id, name, mediaType, displayOrder)`, mit `LibraryKind` = `AUDIOBOOK | HOERSPIEL | PODCAST`. Der Typ wird aus `mediaType` abgeleitet, Hörspiel/Hörbuch aus einer Nutzerzuordnung pro Bibliothek (Default: Name enthält „Hörspiel“ → HOERSPIEL). Die Zuordnung lässt sich in den Einstellungen ändern.
  - `ItemEntity(serverId, id, libraryId, title, subtitle, author, narrator, seriesId, seriesName, seriesSequence: String?, seriesSortKey: Double?, durationSec: Double, coverUpdatedAt: Long, genres: List<String>, tags: List<String>, description, updatedAt, detailsLoaded: Boolean, orphaned: Boolean = false)`
  - `ChapterEntity(serverId, itemId, index, startSec, endSec, title)`
  - `AudioFileEntity(serverId, itemId, index, ino, durationSec, sizeBytes, ext, localPath: String?, completedBytes: Long)`
  - `ProgressEntity(serverId, itemId, currentTimeSec, durationSec, isFinished, lastUpdate: Long, dirty: Boolean)`
  - `LocalSessionEntity(id: String /* UUID */, serverId, itemId, startTimeSec, currentTimeSec, timeListeningSec, startedAt, updatedAt, synced: Boolean)`
  - `DownloadEntity(serverId, itemId, state: DownloadState /* QUEUED, RUNNING, PAUSED, COMPLETE, FAILED */, requestedAt, error: String?)`
  - DAOs mit Flows: `ItemDao.observeInProgress(serverId)`, `observeFinished(serverId)`, `observeDownloaded(serverId)`, `observeByLibraryKinds(serverId, kinds)`, `observeItem(serverId, id)` (inkl. Chapters, AudioFiles, Progress, Download als `@Relation` → `ItemWithDetails`), `ProgressDao.dirty(serverId)`, `SessionDao.unsynced(serverId)`.
  - `seriesSortKey`: `seriesSequence` als Double geparst („3“ → 3.0, „3.5“ → 3.5, „1-2“ → 1.0, unparsebar → null).

- [ ] **Step 1: Failing Tests**
  - `inProgress_excludesFinishedAndUnstarted`
  - `downloaded_onlyWhenAllAudioFilesComplete` (eine Datei mit `localPath == null` → nicht in `observeDownloaded`)
  - `seriesSortKey_parsesSequences` (Tabelle: "3"→3.0, "3.5"→3.5, "1-2"→1.0, "Band X"→null)
  - `kindsFilter_returnsOnlySelectedKinds`
- [ ] **Step 2:** rot. **Step 3:** implementieren (`exportSchema = true`). **Step 4:** grün.
- [ ] **Step 5: Commit** `feat(db): room schema for items, chapters, files, progress, sessions, downloads`

---

### Task 7: Sync

**Files:**
- Create: `.../sync/LibrarySync.kt`, `.../sync/ProgressSync.kt`, `.../sync/SyncWorker.kt`, `.../sync/PositionJump.kt`
- Test: `.../sync/LibrarySyncTest.kt`, `.../sync/ProgressSyncTest.kt` (Fake-APIs + In-Memory-Room)

**Interfaces:**
- Consumes: `LibraryApi`, `MeApi` (Task 3), `ApiProvider` (Task 5), DAOs (Task 6)
- Produces:
  - `class LibrarySync { suspend fun syncAll(serverId: String) }` holt seitenweise (`limit = 200`) alle Items aller Bibliotheken und macht Upsert. Items, die auf dem Server fehlen, werden gelöscht, **außer** sie haben einen Download oder Fortschritt (dann bleiben sie mit `orphaned = true`). `suspend fun loadDetails(serverId, itemId)` holt Kapitel und Audiodateien (expanded).
  - `class ProgressSync { suspend fun sync(serverId: String): List<PositionJump> }`. Ablauf: (1) unsynced Sessions per `local-all` hochladen und als synced markieren, (2) dirty Progress per PATCH hochladen, wenn lokal neuer als Server, (3) Server-Progress aus `me()` übernehmen, wenn `server.lastUpdate > local.lastUpdate`. Weicht die Position dabei um mehr als 60 s ab, entsteht ein `PositionJump(itemId, fromSec, toSec)`.
  - `PositionJumpBus`: `SharedFlow<PositionJump>` für die UI.
  - `SyncWorker` (WorkManager, Constraint `CONNECTED`, periodisch alle 15 min, plus einmalig bei jedem Übergang `Offline → Online`).

- [ ] **Step 1: Failing Tests**
  - `newerServerProgress_winsAndEmitsJump`
  - `newerLocalProgress_isPushed_andNotOverwritten`
  - `offlineSessions_uploadedOnceAndMarkedSynced` (zweiter `sync`-Aufruf → kein zweiter Upload)
  - `serverRemovedItem_withDownload_isKeptAsOrphan`
  - `syncFailure_midway_leavesDirtyFlagsIntact` (Fake wirft IOException nach Session-Upload → Progress bleibt `dirty`)
- [ ] **Step 2:** rot. **Step 3:** implementieren. **Step 4:** grün.
- [ ] **Step 5: Commit** `feat(sync): library and progress sync with newest-wins and jump events`

---

### Task 8: Downloads

**Files:**
- Create: `.../download/DownloadManager.kt`, `.../download/DownloadWorker.kt`, `.../download/DownloadSettings.kt`
- Test: `.../download/DownloadWorkerTest.kt` (Robolectric + `work-testing` + MockWebServer)

**Interfaces:**
- Consumes: `audioFileUrl`, `coverUrl` (Task 3), `ApiProvider`/OkHttp mit Auth (Task 5), DAOs (Task 6), `LibrarySync.loadDetails` (Task 7)
- Produces:
  - `interface DownloadManager { suspend fun enqueue(serverId: String, itemId: String); suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean); fun observe(serverId: String, itemId: String): Flow<DownloadProgress> }`
  - `data class DownloadProgress(val state: DownloadState, val bytesDone: Long, val bytesTotal: Long)`
  - `DownloadSettings.wifiOnly: Flow<Boolean>` (Default `true`), wirkt als WorkManager-Constraint `UNMETERED`
  - Ablage: `filesDir/downloads/<serverId>/<itemId>/<index>.<ext>` und `cover.jpg`. Es wird erst nach `<name>.part` geschrieben, dann per atomarem Rename umbenannt. Fortsetzen per HTTP-`Range` ab `completedBytes`. Foreground-Service-Benachrichtigung mit Fortschritt (`dataSync`).

- [ ] **Step 1: Failing Tests**
  - `download_writesAllFilesAndCover_marksComplete`
  - `interruptedDownload_isNotMarkedComplete_andResumes` (MockWebServer bricht nach der Hälfte ab → `.part` existiert, `localPath == null`. Ein Retry sendet `Range: bytes=<n>-`, danach COMPLETE)
  - `serverWithoutRangeSupport_restartsFile` (Antwort 200 statt 206 → Datei neu von vorn)
  - `cancelWithDelete_removesFilesAndRows`
- [ ] **Step 2:** rot. **Step 3:** implementieren. **Step 4:** grün.
- [ ] **Step 5: Commit** `feat(download): resumable original-file downloads via WorkManager`

---

### Task 9: Wiedergabe-Kern

**Files:**
- Create: `.../playback/PlaybackService.kt` (`MediaLibraryService`, ExoPlayer, `MediaLibrarySession`)
- Create: `.../playback/TimelineMapper.kt`, `.../playback/MediaItemFactory.kt`, `.../playback/ProgressTracker.kt`, `.../playback/PlayerController.kt`
- Modify: `AndroidManifest.xml` (Service mit `foregroundServiceType="mediaPlayback"`, Intent-Filter `androidx.media3.session.MediaLibraryService`)
- Test: `.../playback/TimelineMapperTest.kt`, `.../playback/ProgressTrackerTest.kt`

**Interfaces:**
- Consumes: `ItemWithDetails` (Task 6), `ApiProvider` und Auth-Header (Task 5) zum Streamen nicht heruntergeladener Dateien
- Produces:
  - `class TimelineMapper(files: List<AudioFileEntity>) { fun toFile(globalSec: Double): Pair<Int /*fileIndex*/, Long /*offsetMs*/>; fun toGlobal(fileIndex: Int, offsetMs: Long): Double; fun chapterAt(globalSec: Double, chapters: List<ChapterEntity>): Int }`
  - `MediaItemFactory.build(item: ItemWithDetails, baseUrl: HttpUrl?): List<MediaItem>`: eine `MediaItem` pro Audiodatei. Für fertige Dateien wird die lokale `file://`-URI verwendet, sonst die Stream-URL (nur online, Header über `DefaultHttpDataSource.Factory.setDefaultRequestProperties`). Ist `baseUrl == null` (offline) und nicht jede Datei lokal vorhanden, wirft die Funktion `class PlaybackUnavailableException(val itemId: String) : Exception()`. `PlayerController.play` fängt sie ab und setzt `PlayerUiState.error = R.string.error_not_downloaded_offline`.
  - `class ProgressTracker(progressDao, sessionDao, clock)`: speichert alle 10 s während der Wiedergabe sowie bei Pause, Stop und Service-Ende den Fortschritt (`dirty = true`, `lastUpdate = now`) und akkumuliert `timeListeningSec` in der laufenden `LocalSessionEntity`. Eine neue Session beginnt bei Play nach mehr als 10 min Pause oder bei Wechsel des Titels. `isFinished = true` ab ≥ 99,5 % oder bei Ende der letzten Datei.
  - `interface PlayerController { val state: StateFlow<PlayerUiState>; fun play(serverId: String, itemId: String, startSec: Double? = null); fun togglePlayPause(); fun seekBy(deltaSec: Int); fun seekToChapter(index: Int); fun setSpeed(speed: Float) }`. `PlayerUiState(itemId, title, coverPath, isPlaying, positionSec, durationSec, chapterIndex, chapterTitle, chapterStartSec, chapterEndSec, speed, error: Int? /* @StringRes */)`
  - Audio-Fokus und „Becoming noisy“ (Kopfhörer raus → Pause) über ExoPlayer-Optionen `handleAudioFocus = true`, `setHandleAudioBecomingNoisy(true)`.

- [ ] **Step 1: Failing Tests**
  - `globalPosition_mapsAcrossFileBoundaries` (Dateien 600 s, 300 s, 900 s: global 750 → (1, 150 000 ms); `toGlobal(2, 0) == 900.0`)
  - `chapterAt_usesChapterStarts_notFiles` (m4b mit einer Datei und 5 Kapiteln)
  - `tracker_savesEvery10s_andOnPause`
  - `tracker_accumulatesListeningTime_inOneSession` (Play 30 s, Pause 2 min, Play 30 s → eine Session mit 60 s)
  - `tracker_newSessionAfterLongPause` (Pause 11 min → zwei Sessions)
  - `finishedAt99_5Percent`
- [ ] **Step 2:** rot. **Step 3:** implementieren. **Step 4:** grün.
- [ ] **Step 5: Manuelle Prüfung auf dem Gerät:** ein heruntergeladenes m4b im Flugmodus abspielen. Benachrichtigung und Sperrbildschirm zeigen Cover, Play/Pause und Sprungtasten. Kopfhörertaste pausiert.
- [ ] **Step 6: Commit** `feat(playback): media3 service, multi-file timeline, progress and session tracking`

---

### Task 10: App-Shell, Navigation, Offline-Hinweis, Theme

**Files:**
- Create: `.../ui/LauschigTheme.kt` (Material 3, dynamische Farben ab API 31, Fallback-Palette warm/dunkel)
- Create: `.../ui/AppScaffold.kt` (`NavigationBar` mit 4 Items, Icon und Label, Mini-Player-Slot über der Leiste, Offline-Banner oben)
- Create: `.../ui/nav/Destinations.kt` (typsichere Compose-Navigation)
- Create: `.../ui/placeholder/ComingSoonScreen.kt` (Entdecken, Suche)
- Create: `.../ui/common/PositionJumpSnackbar.kt`
- Modify: `MainActivity.kt`
- Test: `app/src/androidTest/.../ui/AppScaffoldTest.kt`

**Interfaces:**
- Consumes: `ConnectionState`-Flow (Task 4), `PositionJumpBus` (Task 7), `PlayerController.state` (Task 9)
- Produces: `@Composable fun AppScaffold(connection: ConnectionState, miniPlayer: @Composable () -> Unit, content: @Composable (PaddingValues) -> Unit)`, Routen `Start`, `Library`, `Discover`, `Search`, `ItemDetail(itemId)`, `Player`, `Setup`.

Icons: Start = `Icons.Rounded.Home`, Bibliothek = `Icons.Rounded.LibraryBooks`, Entdecken = `Icons.Rounded.Explore`, Suche = `Icons.Rounded.Search`. Texte: `nav_start` „Start“/„Home“, `nav_library` „Bibliothek“/„Library“, `nav_discover` „Entdecken“/„Discover“, `nav_search` „Suche“/„Search“, `offline_banner` „Offline – nur heruntergeladene Titel“/„Offline – downloaded titles only“, `position_jump` „Auf einem anderen Gerät weitergehört. Zurück zu %1$s?“/„Continued on another device. Go back to %1$s?“.

Der Start-Bildschirm zeigt in Phase 1 nur „Weiterhören“ (zuletzt gehörter Titel aus Room) und „Heruntergeladen“.

- [ ] **Step 1: Failing UI-Tests**
  - `bottomBar_hasFourItemsWithIconAndLabel`
  - `offlineState_showsBanner_onlineHidesIt`
  - `positionJump_showsSnackbar_andUndoSeeksBack`
- [ ] **Step 2:** rot (`./gradlew :app:connectedDebugAndroidTest` auf Emulator/Gerät). **Step 3:** implementieren. **Step 4:** grün.
- [ ] **Step 5: Commit** `feat(ui): app shell with bottom navigation, offline banner, jump snackbar`

---

### Task 11: Einrichtung und Login

**Files:**
- Create: `.../ui/setup/SetupScreen.kt`, `.../ui/setup/SetupViewModel.kt`
- Test: `.../ui/setup/SetupViewModelTest.kt`, `app/src/androidTest/.../ColdStartTest.kt`

**Interfaces:**
- Consumes: `AuthApi` (Task 2), `AddressResolver` (Task 4), `AccountStore` (Task 5), `LibrarySync` (Task 7)
- Produces: Der Start-Router in `MainActivity` zeigt `Setup` nur, wenn `AccountStore.account` `null` ist (lokaler Read, kein Netz). Sonst wird sofort `Start` angezeigt.

Felder: „Lokale Adresse (optional)“, „Öffentliche Adresse (optional)“ (mindestens eine Pflicht), „Nur im WLAN … lokal verwenden“ (optional, SSID-Auswahl), Benutzername, Passwort. Für `http://`-Adressen erscheint ein Bestätigungsdialog („unverschlüsselt“), danach landet die Adresse in `allowCleartext`. Fehlertexte: Server nicht erreichbar, Login falsch, Server zu alt (keine Tokens in der Login-Antwort).

- [ ] **Step 1: Failing Tests**
  - `noAddress_disablesLogin`
  - `httpAddress_requiresCleartextConfirmation`
  - `oldServerWithoutTokens_showsServerTooOld`
  - `successfulLogin_savesAccount_andStartsLibrarySync`
  - `coldStart_serverUnreachable_showsLibraryAndPlays` (androidTest: Konto und ein komplett „heruntergeladenes“ Test-Item mit kleiner Audiodatei aus `androidTest/assets` in Room und filesDir vorbefüllen, Probe liefert immer false, Access-Token abgelaufen. Assert: Innerhalb von 2 s nach Start ist der Titel sichtbar, kein Setup-Screen. Tippen auf Play → `PlayerUiState.isPlaying == true`)
- [ ] **Step 2:** rot. **Step 3:** implementieren. **Step 4:** grün.
- [ ] **Step 5: Commit** `feat(setup): server setup with local/public address and password login`

---

### Task 12: Bibliothek mit Tabs und Filter

**Files:**
- Create: `.../ui/library/LibraryScreen.kt`, `.../ui/library/LibraryViewModel.kt`, `.../ui/common/CoverTile.kt`, `.../ui/common/KindFilterChips.kt`
- Test: `.../ui/library/LibraryViewModelTest.kt`, `app/src/androidTest/.../ui/CoverTileTest.kt`

**Interfaces:**
- Consumes: `ItemDao` (Task 6), `ConnectionState` (Task 4)
- Produces:
  - `@Composable fun CoverTile(item: ItemSummary, modifier: Modifier = Modifier)`: Cover (Coil, lokales `cover.jpg` bevorzugt, sonst `coverUrl` nur online, sonst Platzhalter mit Titel-Initialen). **Badge mit Bandnummer** oben links (`#<seriesSequence>`), Offline-Symbol unten rechts, wenn heruntergeladen, Fortschrittsbalken unten.
  - `@Composable fun KindFilterChips(available: Set<LibraryKind>, selected: Set<LibraryKind>, onChange: (Set<LibraryKind>) -> Unit)` mit „Alle“, „Hörbücher“, „Hörspiele“ (Podcasts sind ab Phase 2 sichtbar). Die Auswahl wird pro Bildschirm in DataStore gespeichert.
  - `data class ItemSummary(serverId, id, title, author, seriesName, seriesSequence, coverPath: String?, progress: Float, isDownloaded: Boolean, isFinished: Boolean)`

Tabs: *Am Hören* (nach `lastUpdate` absteigend), *Gehört*, *Heruntergeladen*, *Serien* (gruppiert nach `seriesName`, innerhalb nach `seriesSortKey`, Bände ohne Sortschlüssel ans Ende). Offline werden nicht heruntergeladene Titel ausgegraut, ein Tippen öffnet trotzdem die Detailseite. Listen laufen über `LazyVerticalGrid` mit stabilen Keys. Für 5 000 Items gilt: Scrollen ohne Ruckler (Baseline Profile in Task 15).

- [ ] **Step 1: Failing Tests**
  - `seriesTab_ordersBySequence_unknownLast`
  - `kindFilter_hoerspieleOnly_hidesAudiobooks`
  - `offline_nonDownloadedItems_areMarkedUnavailable`
  - `coverTile_showsSeriesBadgeAndOfflineIcon` (androidTest, Semantics-Assertions auf ContentDescription „Band 3“ und „Offline verfügbar“)
- [ ] **Step 2:** rot. **Step 3:** implementieren. **Step 4:** grün.
- [ ] **Step 5: Commit** `feat(library): tabs, kind filter chips and cover tiles with series badge`

---

### Task 13: Buch-Detail mit Kapiteln und Download

**Files:**
- Create: `.../ui/detail/ItemDetailScreen.kt`, `.../ui/detail/ItemDetailViewModel.kt`, `.../ui/detail/ChapterList.kt`
- Test: `.../ui/detail/ItemDetailViewModelTest.kt`

**Interfaces:**
- Consumes: `ItemDao.observeItem` (Task 6), `LibrarySync.loadDetails` (Task 7), `DownloadManager` (Task 8), `PlayerController` (Task 9)
- Produces: Route `ItemDetail(itemId)`. Chips für Autor, Sprecher, Serie („Serie · Band 3“) und Genre werden angezeigt. Ihre Navigation folgt in Phase 2, bis dahin sind sie nicht klickbar.

Inhalt: Cover groß, Titel, Untertitel, Chips, Hauptknopf („Abspielen“ / „Weiterhören · Kap. N“ / „Nochmal hören“), Download-Knopf mit Status (Fortschritt, Pause/Abbrechen, „Heruntergeladen (812 MB)“, Löschen), Beschreibung, **Kapitelliste** (Titel, Dauer, aktuelles Kapitel hervorgehoben mit Position „12:30 / 44:15“, Tippen → `seekToChapter`). Fehlen die Details (`detailsLoaded == false`) und ist die App online, werden sie im Hintergrund geladen. Offline ohne Details erscheint der Hinweis „Kapitel verfügbar, sobald online“.

- [ ] **Step 1: Failing Tests**
  - `primaryAction_reflectsProgress` (0 → Abspielen, 40 % → Weiterhören · Kap. N, finished → Nochmal hören)
  - `detailsMissing_online_triggersLoad_once`
  - `offline_notDownloaded_playDisabled_withReason`
  - `chapterTap_seeksToChapterStart`
- [ ] **Step 2:** rot. **Step 3:** implementieren. **Step 4:** grün.
- [ ] **Step 5: Commit** `feat(detail): item detail with chapters and download controls`

---

### Task 14: Mini-Player und Player-Bildschirm

**Files:**
- Create: `.../ui/player/MiniPlayer.kt`, `.../ui/player/PlayerScreen.kt`, `.../ui/player/PlayerViewModel.kt`
- Test: `.../ui/player/PlayerViewModelTest.kt`, `app/src/androidTest/.../ui/MiniPlayerTest.kt`

**Interfaces:**
- Consumes: `PlayerController` (Task 9), `AppScaffold.miniPlayer`-Slot (Task 10)
- Produces: `@Composable fun MiniPlayer(state: PlayerUiState, onToggle: () -> Unit, onOpen: () -> Unit)`. Der Play/Pause-Knopf hat mindestens **56 dp** Tippfläche und ist auf jedem Bildschirm sichtbar, solange ein Titel geladen ist.

Player-Bildschirm: Cover, Titel, Kapiteltitel, Kapitel-Fortschrittsleiste (Zeit im Kapitel) plus Gesamtzeit, −30 s / Play-Pause / +30 s, vorheriges/nächstes Kapitel, Geschwindigkeit (0,5–3,0 in 0,05-Schritten, Presets 1,0/1,25/1,5/2,0), Kapitelliste als Bottom-Sheet.

- [ ] **Step 1: Failing Tests**
  - `miniPlayer_toggleTarget_isAtLeast56dp` (androidTest)
  - `miniPlayer_hiddenWhenNothingLoaded`
  - `chapterProgress_isRelativeToChapter`
  - `speedPresets_applyToController`
- [ ] **Step 2:** rot. **Step 3:** implementieren. **Step 4:** grün.
- [ ] **Step 5: Commit** `feat(player): mini player and full player screen`

---

### Task 15: Release-Build und geschlossener Test

**Files:**
- Modify: `app/build.gradle.kts` (Release-Signing aus Umgebungsvariablen `LAUSCHIG_KEYSTORE`, `LAUSCHIG_KEYSTORE_PASSWORD`, `LAUSCHIG_KEY_ALIAS`, `LAUSCHIG_KEY_PASSWORD`; R8 an; `bundleRelease`)
- Create: `baselineprofile/`-Modul mit Startup- und Library-Scroll-Journey
- Create: `docs/privacy-policy.md` (DE/EN: Die App spricht nur mit dem vom Nutzer eingetragenen ABS-Server, später optional mit RMAB und Audible-Metadaten. Keine Analyse, keine Werbung, keine Weitergabe)
- Create: `docs/play-store/listing-de.md`, `listing-en.md`, `docs/play-store/data-safety.md` (Antworten für das Formular)
- Create: `.github/workflows/release.yml` (bei Tag `v*`: signiertes AAB als Artefakt; Upload in den Play Store macht Fabian manuell)
- Test: Macrobenchmark `StartupBenchmark.coldStartOffline`

- [ ] **Step 1:** Benchmark schreiben. Ziel: Kaltstart offline bis zum ersten Frame der Bibliothek < 800 ms auf einem Mittelklasse-Gerät (Messwert festhalten, nicht hart failen).
- [ ] **Step 2:** Release-Konfiguration, Baseline-Profile und Docs anlegen.
- [ ] **Step 3:** `./gradlew :app:bundleRelease` mit einem lokalen Upload-Key (Fabian erzeugt ihn per `keytool` und bewahrt ihn außerhalb des Repos auf, Kopie in OpenBao). Expected: signiertes AAB unter `app/build/outputs/bundle/release/`.
- [ ] **Step 4:** Manuelle Abnahme auf Fabians Handy: Einrichtung über die öffentliche Adresse, Buch herunterladen, NetBird aus und Flugmodus, App beenden und neu starten → Bibliothek sichtbar, Buch spielt, Fortschritt nach Wiederverbindung auf dem Server.
- [ ] **Step 5: Commit** `chore(release): signing, baseline profile, privacy policy and play listing drafts`
- [ ] **Step 6 (Fabian):** Play-Entwicklerkonto anlegen, App anlegen, AAB in den Track „Geschlossener Test“ hochladen, Familie als Tester einladen.
