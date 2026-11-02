# Lauschig Phase 2b – Fehlende Bände + RMAB, SSO-Login, Podcasts – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- Serienseiten zeigen fehlende Bände aus dem Audible-Katalog. Ist RMAB eingerichtet, lassen sie sich direkt anfragen.
- Die Einrichtung bietet „Mit SSO anmelden“ (ABS-OpenID), sobald der Server es anbietet.
- ABS-Podcasts funktionieren: Podcast-Seite mit Episoden, Abspielen und Herunterladen einzelner Episoden,
  Episoden-Fortschritt mit Sync und neue Episoden auf dem Start-Bildschirm.

**Architecture:**
- **Fehlende Bände** kommen aus dem öffentlichen Audible-Katalog.
  - Weg dorthin: ASIN eines eigenen Bands → Serien-ASIN → Kinder der Serie → Details im Paket.
  - Das Ergebnis wird pro Serie in Room gecacht (7 Tage).
  - Es ergänzt die Serie aus ABS nur, angezeigt wird es nur online. Ein Ausfall heißt: die Lücken bleiben wie
    in 2a („Band 3 – nicht in der Bibliothek“ ohne Details).
- **RMAB:** Lauschig meldet sich einmal mit RMAB-Benutzername und -Passwort an und erzeugt dabei einen dauerhaften
  API-Token. Gespeichert wird nur dieser Token, verschlüsselt mit `TokenCipher`.
- **SSO:** PKCE-Flow der offiziellen App. Lauschig nutzt dafür eigenes OkHttp mit Cookie-Jar, einen Custom Tab und
  die Redirect-URI `lauschig://oauth`.
- **Podcasts:** Episoden bekommen eine eigene Tabelle. Fortschritt, Audiodateien, Kapitel, Downloads und lokale
  Sessions einer Episode stehen in den **bestehenden** Tabellen unter einem **Playable-Key**
  `"<itemId>~<episodeId>"` in der Spalte `itemId`. Damit bleiben Player, Download-Worker und Sync fast unverändert.
  Nur die Stellen, die mit dem Server sprechen oder die Item-Zeile laden, zerlegen den Key.

**Tech Stack:** wie bisher. Neu ist nur `androidx.browser` (Custom Tabs), stabile Version.

**Spec:** `docs/superpowers/specs/2026-10-31-lauschig-design.md` (2.2a, 2.3, 6a „Login“, 6a „Fehlende Bände“).
API-Recherche mit allen Endpunkten und Feldern: `docs/superpowers/research/2026-11-01-phase2b-apis.md`
(**jede Task liest den für sie relevanten Abschnitt**).

## Global Constraints

- Alle Constraints aus Phase 1 und 2a gelten weiter:
  - Offline zuerst: kein Netz im Startpfad.
  - Strings EN + DE.
  - Conventional Commits.
  - Gradle-Läufe immer mit `timeout`.
  - Robolectric `@Config(sdk = [35])`.
- **Externe Dienste dürfen ausfallen:** Audible und RMAB sind inoffiziell bzw. optional.
  - Jeder Fehler (Timeout, 4xx/5xx, kaputtes JSON) endet in „Daten fehlen“ bzw. einer verständlichen Meldung,
    nie in einem Absturz oder einem blockierten Bildschirm.
  - Timeout jeweils 10 s.
  - Audible-JSON wird lenient geparst (`AbsJson`-artig: `ignoreUnknownKeys`, `explicitNulls=false`, `coerceInputValues`).
- **Keine Anfrage ohne Bestätigung:** Eine RMAB-Anfrage löst Downloads auf dem Server aus. Deshalb geht ihr immer ein
  Bestätigungsdialog mit Titel voraus. Bulk-Anfragen gibt es nicht.
- **Privatsphäre:** Ein neuer Schalter „Fehlende Bände von Audible ergänzen“ (Standard **an**) regelt, ob Audible
  angefragt wird.
  - An Audible gehen nur ASINs, keine Nutzerdaten.
  - Die Datenschutzerklärung (`docs/privacy-policy.md`, DE + EN) wird angepasst.
- **Keine Änderung an echten Daten in Tests:** Tests nutzen MockWebServer bzw. Fakes, nie echte Server.
- **Playable-Key:**
  - Einzige Stelle für Bau und Zerlegen ist `io.wolftown.lauschig.playback.PlayableKey`.
  - ABS-IDs enthalten kein `~`.
  - Bücher behalten ihre bisherige `itemId` als Key. Für sie ändert sich nichts, auch nicht für bestehende Daten.

## Review Focus

1. **Serie mit uneinheitlichen Bandnummern auf Audible** (Boxen „1-3“, leere Sequenz, mehrere Ausgaben derselben
   Nummer, gekürzte Fassung, englische Ausgabe neben deutscher, unveröffentlicht/Platzhalterdatum 2200).
   Erwartung:
   - Je Nummer genau ein fehlender Band in der Sprache der eigenen Bände, ungekürzt bevorzugt.
   - Boxen und Bände ohne Nummer werden nicht angezeigt.
   - Künftige Bände erscheinen mit „erscheint am …“.
   - Datum ≥ 2100 wird verworfen.
   *(Task 2: `gaps_pickOnePerSequence_preferLanguageAndUnabridged`, `gaps_skipBoxSetsAndUnnumbered`, `gaps_futureRelease_marked_placeholderDropped`.)*
2. **ABS-Band trägt eine ASIN aus der anderen Region** (US-ASIN, Region DE). Die Serie wird trotzdem gefunden
   (Fallback-Region), oder es bleibt still bei den Lücken aus 2a. *(Task 2: `lookup_fallsBackToOtherRegion_on404`.)*
3. **Podcast-Episode offline:** Eine heruntergeladene Episode spielt im Flugmodus sofort. Ihr Fortschritt wird
   später korrekt als Episode (nicht als Buch) hochgeladen. *(Task 6: `episodeProgress_pushedWithEpisodeId`;
   Task 7: `downloadedEpisode_playsOffline`.)*
4. **Bestehende Bücher-Daten nach Migration v3 → v4:** Fortschritt, Downloads und Sessions bleiben, keine Zeile
   ändert ihren Key. *(Task 1: `v3ToV4_keepsData`.)*
5. **SSO, wenn der Server `lauschig://oauth` nicht freigegeben hat** (Standardfall): klare Meldung mit Anleitung für
   den Admin, kein endloser Custom Tab. Wird der Tab ohne Rückkehr geschlossen, steht die Einrichtung wieder bereit.
   *(Task 5: `oidcStart_invalidRedirect_showsAdminHint`, `oidc_tabClosedWithoutCallback_resetsLoading`.)*

---

### Task 1: Grundlagen – API-Modelle, Room v4, Playable-Key

**Files:**
- Modify:
  - `abs-api/.../library/LibraryModels.kt`:
    - `BookMetadataDto`: `asin: String? = null`, `language: String? = null`, `author: String? = null` (Podcast-Autor).
    - `BookMediaDto`: `numEpisodes: Int? = null`, `episodes: List<EpisodeDto> = emptyList()`.
    - Neu `EpisodeDto`: `id, index: Int? = null, season: String? = null, episode: String? = null, title, subtitle?,
      description?, publishedAt: Long? = null, duration: Double? = null, size: Long? = null,
      audioFile: AudioFileDto? = null, chapters: List<ChapterDto> = emptyList(), libraryItemId: String? = null`.
- Modify: `abs-api/.../library/LibraryApi.kt`: `@GET("api/libraries/{id}/recent-episodes") suspend fun recentEpisodes(@Path("id") id: String, @Query("limit") limit: Int, @Query("page") page: Int = 0): RecentEpisodesResponse` (`{episodes: List<EpisodeDto>}`, lenient Liste).
- Modify: `abs-api/.../me/MeApi.kt`: `@PATCH("api/me/progress/{itemId}/{episodeId}") suspend fun updateEpisodeProgress(...)`. `LocalSessionDto` (MeModels) bekommt `episodeId: String? = null`, falls noch nicht vorhanden.
- Modify: `abs-api/.../auth/AuthApi.kt` + Modelle: `@GET("status") suspend fun status(): StatusResponse` mit `StatusResponse(serverVersion: String? = null, authMethods: List<String> = emptyList(), authFormData: AuthFormData? = null)`, `AuthFormData(authOpenIDButtonText: String? = null, authOpenIDAutoLaunch: Boolean = false, authLoginCustomMessage: String? = null)`.
- Modify: `app/.../db/entities/ItemEntity.kt`:
  - `asin: String?` und `language: String?` (beide `@ColumnInfo(defaultValue = "NULL")`).
  - `numEpisodes: Int` (`defaultValue = "0"`).
  - `author` wird bei Podcasts aus `metadata.author` gefüllt.
- Create:
  - `app/.../db/entities/EpisodeEntity.kt`: Tabelle `episodes`, PK `(serverId, itemId, id)`, Index `(serverId, publishedAt)`.
    Felder `serverId, itemId, id, title, subtitle?, description?, season?, episode?, publishedAt: Long, durationSec: Double, sizeBytes: Long, recent: Boolean = false`.
  - `EpisodeDao` mit `upsert(list)`, `replaceForItem(serverId, itemId, list)` (löscht nur Episoden des Items, die
    weder Download noch Fortschritt haben), `observeForItem(serverId, itemId): Flow<List<EpisodeEntity>>` (neueste
    zuerst), `get(serverId, itemId, id)`, `observeRecent(serverId, limit)`.
- Modify: `db/LauschigDatabase.kt` (v4, `AutoMigration(3, 4)`), `sync/LibrarySync.kt` (`toListEntity` mappt
  `asin`, `language`, `numEpisodes`, Podcast-Autor).
- Create: `app/.../playback/PlayableKey.kt`:
  - `object PlayableKey`
  - `fun episode(itemId: String, episodeId: String): String = "$itemId~$episodeId"`
  - `fun parse(key: String): Parsed`, mit `data class Parsed(val libraryItemId: String, val episodeId: String?)`
- Test: `PlayableKeyTest`, `db/MigrationTest.kt` (`v3ToV4_keepsData`, Ausgangsschema `3.json`, Fortschritt +
  Download + Session), `abs-api` Fixture-Tests für Podcast-Liste, expandierten Podcast, `recent-episodes`, `/status`
  (JSON aus dem Recherche-Dokument nachgebaut).

**Interfaces:**
- Produces: alle oben genannten Typen und DAO-Methoden. `ItemEntity.asin`/`language`/`numEpisodes`.
  `PlayableKey.episode/parse`.

- [ ] Step 1: Tests schreiben (Parsing, `PlayableKey` hin und zurück, Buch-Key ohne `~` → `episodeId == null`, Migration).
- [ ] Step 2: FAIL. `timeout 15m ./gradlew :abs-api:test :app:testDebugUnitTest --tests '*PlayableKeyTest' --tests '*MigrationTest'`
- [ ] Step 3: Implementieren (inkl. exportiertes Schema `4.json`).
- [ ] Step 4: PASS, dazu der volle `:app:testDebugUnitTest` grün (bestehende Tests dürfen nicht brechen).
- [ ] Step 5: Commit `feat(data): asin/language, podcast episodes table, playable keys (room v4)`

### Task 2: Audible-Katalog-Client und Lückenberechnung

**Files:**
- Create: `app/.../external/AudibleCatalog.kt`:
  - Retrofit-Interface, Basis-URL je Region, eigener OkHttp-Client.
  - Kein Auth-Header, 10 s Timeout, `Accept: application/json`.
  - Methoden: `product(asin, responseGroups)`, `seriesChildren(seriesAsin)`, `products(asins)` (höchstens 50 pro
    Aufruf, mehrere Aufrufe zusammenfügen).
  - DTOs nach Recherche-Dokument Abschnitt 1.
- Create: `app/.../external/SeriesGaps.kt`, reine Logik:
  - `fun missingVolumes(owned: List<ItemEntity>, children: List<AudibleVolume>, today: LocalDate): List<MissingVolume>`
  - `data class AudibleVolume(asin, title, subtitle?, authors: List<String>, narrators: List<String>, sequence: String?, sort: Int?, releaseDate: LocalDate?, runtimeMin: Int?, language: String?, abridged: Boolean, coverUrl: String?, summary: String?)`
  - `data class MissingVolume(sequence: String, sortKey: Double, volume: AudibleVolume, upcoming: Boolean)`
  - Regeln:
    - Nummer per bestehendem `seriesSortKey` normalisieren. Eine Nummer gilt als vorhanden, wenn ein eigener Band
      (egal welche Sprache) denselben Sortkey hat.
    - Sequenzen mit `-` oder `,` (Boxen) sowie leere Sequenzen werden verworfen.
    - Mehrere Kandidaten pro Nummer: bevorzugt die häufigste Sprache der eigenen Bände (ABS `language`, abgeglichen
      über die Namen „german“/„deutsch“/„de“ usw.), dann ungekürzt, dann frühestes Erscheinungsdatum.
    - Erscheinungsdatum nach `today` → `upcoming = true`. Datum ≥ 2100-01-01 → verwerfen.
- Create: `app/.../external/ExternalSeriesRepository.kt` (Singleton):
  - Signatur: `suspend fun missingFor(serverId, libraryId, seriesName, owned: List<ItemEntity>): List<MissingVolume>?`
    (null = keine Daten).
  - Ablauf:
    1. Erste ASIN eines eigenen Bands nehmen.
    2. Das Produkt mit `series` holen. Die Serie wählen, deren Titel normalisiert dem ABS-Namen entspricht, sonst die
       erste.
    3. Die Kinder holen, dann die Details.
  - Region aus den Einstellungen (`AppSettings.audibleRegion`, Standard `de`). Bei 404 bzw. leerem Produkt einmal
    die Alternativregion (`de` ↔ `us`, sonst → `us`).
  - Cache:
    - Neue Room-Tabelle `external_series` (Teil dieser Task, `AutoMigration(4, 5)`; DB-Version 5), PK
      `(serverId, libraryId, seriesName)`, Spalten `json: String` (serialisierte `List<AudibleVolume>`),
      `fetchedAt: Long`.
    - TTL 7 Tage. Fehler beim Erneuern → alter Cache wird benutzt.
    - Ist der Schalter aus, wird nichts angefragt.
  - In-flight-Anfragen pro Serie werden zusammengelegt (Mutex-Map), damit Neuzeichnen nicht doppelt lädt.
- Modify: `settings/AppSettings.kt`: `missingVolumesEnabled: Flow<Boolean>` (Standard `true`),
  `audibleRegion: Flow<String>` (Standard `de`).
- Test: `SeriesGapsTest` (die drei Review-Focus-Tests aus Punkt 1 plus `gaps_ownedSequenceInOtherLanguage_notMissing`), `ExternalSeriesRepositoryTest` mit MockWebServer (`lookup_fallsBackToOtherRegion_on404`, `cache_freshWithinTtl_noNetwork`, `networkError_returnsStaleCacheOrNull`, `disabled_noRequest`).

**Interfaces:**
- Consumes: `ItemEntity.asin/language/seriesSortKey`, `seriesSortKey()` aus `db/SeriesParsing.kt`.
- Produces: `ExternalSeriesRepository.missingFor(...)`, `MissingVolume`, `AudibleVolume`.
  `AppSettings.missingVolumesEnabled/audibleRegion` + Setter.

- [ ] Step 1–5 wie üblich (Tests → FAIL → Implementierung → PASS → Commit `feat(series): missing volumes from the audible catalog`).

### Task 3: Fehlende Bände auf der Serienseite

**Files:**
- Modify:
  - `ui/browse/BrowseViewModel.kt` und `BrowseScreen.kt`.
  - `settings/SettingsScreen.kt`: Schalter „Fehlende Bände von Audible ergänzen“ und Region-Auswahl (de, us, uk, fr,
    it, es, ca, au), beides im Abschnitt „Serien“.
  - `docs/privacy-policy.md`.
- Create: `ui/browse/MissingVolumeSheet.kt`.
- Verhalten:
  - Serienseite (`BrowseKind.SERIES`), online und Schalter an → `ExternalSeriesRepository.missingFor` im Hintergrund.
    Bis Ergebnisse da sind, bleiben die 2a-Platzhalter.
  - `BrowseEntry.Missing` bekommt ein optionales `volume: MissingVolume?`.
  - Lücken aus 2a, für die es einen Audible-Band gibt, werden ersetzt. Audible-Bände außerhalb des Bereichs der
    eigenen Bände (z. B. Band 12, wenn man nur 1–5 hat) werden in Reihenfolge eingefügt.
  - Missing-Kachel: ausgegrautes Audible-Cover (Coil, Netz-URL), Bandnummer-Badge, Text „Nicht in der Bibliothek“
    bzw. „Erscheint am 12.03.2027“.
  - Tippen öffnet `MissingVolumeSheet`: Cover, Titel, Untertitel, Autor, Sprecher, Dauer, Erscheinungsdatum,
    Zusammenfassung (HTML → Klartext) und unten den Platz für den RMAB-Knopf (Slot, Task 4 füllt ihn).
  - Offline: keine Audible-Kacheln, nur die 2a-Platzhalter.
- Test: `BrowseViewModelTest` (`series_online_mergesAudibleVolumesInOrder`, `series_offline_keepsPlainGaps`, `series_disabled_noLookup`), `BrowseScreenTest` (`missingTile_tap_opensSheet`, Semantik „Band 3, nicht in der Bibliothek“).

**Interfaces:**
- Consumes: Task 2.
- Produces: `@Composable fun MissingVolumeSheet(volume: MissingVolume, onDismiss: () -> Unit, actions: @Composable () -> Unit)`.

- [ ] Step 1–5, Commit `feat(series): show missing volumes with details on the series page`

### Task 4: RMAB anbinden und anfragen

**Files:**
- Create: `app/.../rmab/RmabApi.kt` (Retrofit) für die Endpunkte aus Recherche-Abschnitt 3: `providers`, `localLogin`,
  `createApiToken`, `me`, `createRequest`, `listRequests(myOnly=true, take=100, cursor)`.
- Create: `rmab/RmabRepository.kt`:
  - **Konfiguration:** URL und verschlüsselter Token in einem eigenen DataStore, Token über `TokenCipher`.
  - `config: Flow<RmabConfig?>` mit `RmabConfig(url, username)`.
  - **Anmelden:** `suspend fun connect(url, username, password): RmabConnectResult`. Ablauf: Login, dann
    `POST /api/user/api-tokens` mit `name = "Lauschig (<Gerätename>)"` und `expiresAt = null`; gespeichert wird nur
    `fullToken`. Ergebnis ist eins von `Ok`, `WrongLogin`, `PendingApproval`, `LocalLoginDisabled`, `Unreachable`.
  - **Token direkt eintragen:** `suspend fun connectWithToken(url, token)`, geprüft per `/api/auth/me`. Das ist für
    RMAB-Konten, die nur OIDC haben.
  - **Trennen:** `disconnect()`.
  - **Anfragen:** `suspend fun request(volume: AudibleVolume): RmabRequestResult` mit den Fällen `Created`,
    `AlreadyAvailable`, `AlreadyRequested`, `BeingProcessed`, `Failed(message)`, `NotConnected`.
  - **Status:** `suspend fun statusByAsin(): Map<String, RmabStatus>`, über alle Seiten der eigenen Anfragen.
    Gecacht 60 s im Speicher, nach eigener Anfrage verworfen.
  - **Statusgruppen:** `enum class RmabStatus { WAITING, ACTIVE, DONE, FAILED }`. Zuordnung:

    | Gruppe | RMAB-Status |
    |---|---|
    | WAITING | pending, awaiting_approval, awaiting_release, awaiting_search |
    | ACTIVE | searching, downloading, processing, awaiting_import, downloaded |
    | DONE | available |
    | FAILED | failed, cancelled, denied, warn |

    Ein FAILED-Titel lässt sich erneut anfragen.
- Netzwerk:
  - Eigener OkHttp-Client mit denselben CA-Regeln wie ABS (System + Nutzer).
  - Cleartext nur nach Bestätigung, analog zur ABS-Einrichtung. Wiederverwenden, was es dort gibt.
- UI:
  - **Einstellungen:** Abschnitt „ReadMeABook“. Nicht verbunden: „Verbinden“ öffnet einen Dialog mit Adresse,
    Benutzer und Passwort sowie dem Link „stattdessen API-Token eintragen“. Verbunden: „Verbunden als X“ und „Trennen“.
  - **`MissingVolumeSheet`-Slot:**
    - Ohne RMAB kein Knopf.
    - Mit RMAB je nach Status:
      - kein Eintrag oder FAILED: „Anfragen“
      - WAITING: „Angefragt · wartet“
      - ACTIVE: „Angefragt · wird geladen“
      - DONE: „Bald in der Bibliothek“
    - Die Kachel auf der Serienseite zeigt den Status als kleines Symbol.
  - **Anfragen:**
    - „Anfragen“ öffnet den Bestätigungsdialog „‚Titel‘ bei ReadMeABook anfragen?“, danach Snackbar mit dem Ergebnis.
    - Autor = erster Audible-Autor. Fehlt er, wird nicht angefragt, sondern es erscheint eine Meldung.
- Test: `RmabRepositoryTest` mit MockWebServer:
  - `connect_createsApiToken_storesOnlyToken`
  - `connect_pendingApproval`
  - `request_409Duplicate_mapsAlreadyRequested`
  - `request_sendsAsinTitleAuthor`
  - `status_pagesThroughCursor`
  - `unreachable_returnsResultNoThrow`

  UI-Test `missingSheet_withRmab_confirmBeforeRequest`.

**Interfaces:**
- Consumes: `MissingVolumeSheet(actions)`, `TokenCipher`, `AudibleVolume`.
- Produces: `RmabRepository` (Singleton, Hilt).

- [ ] Step 1–5, Commit `feat(rmab): connect to ReadMeABook and request missing volumes`

### Task 5: SSO-Login (ABS OpenID)

**Files:**
- Create: `ui/setup/OidcLogin.kt`:
  - `class OidcLogin @Inject constructor(...)` mit `suspend fun start(baseUrl): OidcStart` und
    `suspend fun finish(callbackUri: Uri): LoginResponse`.
  - `start` erzeugt PKCE (Verifier 64 Zeichen aus `SecureRandom`, S256) und `state`. Es ruft `/auth/openid` mit
    eigenem OkHttp (`followRedirects(false)`, persistentem Cookie-Jar) und liefert die `Location`.
  - Bei 400 kommt `OidcStart.RedirectNotAllowed`, Netzfehler ergeben `Unreachable`.
  - **Wartender Vorgang:** `baseUrl`, Verifier, `state`, Cookies und Startzeit werden in einem kleinen,
    verschlüsselten DataStore gehalten (gültig 10 min). Er übersteht damit einen Prozess-Neustart, während der
    Custom Tab offen ist.
  - `finish` prüft `state` und ruft `/auth/openid/callback` mit demselben Cookie-Jar. Erst danach wird der wartende
    Vorgang gelöscht.
- Modify: `AndroidManifest.xml`: Intent-Filter `lauschig://oauth` (VIEW, BROWSABLE) auf `MainActivity`
  (`launchMode="singleTask"`, falls nicht schon so). `MainActivity` reicht die URI an `SetupViewModel` weiter, über
  `onNewIntent` bzw. den Start-Intent.
- Modify: `ui/setup/SetupViewModel.kt` + `SetupScreen.kt`:
  - Nach Eingabe der Adresse, also beim Login-Versuch bzw. sobald die Adresse gültig ist, `GET /status` abfragen
    (Fehler werden ignoriert).
  - Enthält `authMethods` `"openid"`, erscheint unter dem Passwort-Login der Knopf mit `authOpenIDButtonText`
    (Fallback „Mit SSO anmelden“).
  - Nach erfolgreichem `finish` läuft derselbe Speicherweg wie beim Passwort-Login (Account, Tokens,
    Adress-/Cleartext-Regeln). Fehlender `refreshToken` → `SERVER_TOO_OLD` wie bisher.
  - **Fehlermeldung `RedirectNotAllowed`** (DE): „Dein Server erlaubt Lauschig noch nicht für SSO. Ein Admin muss in
    Audiobookshelf unter Einstellungen → Authentifizierung bei ‚Allowed Mobile Redirect URIs‘ `lauschig://oauth`
    eintragen.“
  - Kehrt die App ohne Callback in den Vordergrund zurück (`ON_RESUME` nach dem Start), endet `loading` nach 1 s
    ohne Fehlermeldung.
  - Die Neuanmeldung nach „Bitte neu anmelden“ nutzt dieselbe Einrichtung und bekommt den SSO-Knopf damit
    automatisch. Prüfen und gegebenenfalls anschließen.
- Modify: `gradle/libs.versions.toml` + `app/build.gradle.kts`: `androidx.browser`.
- Test:
  - `OidcLoginTest` mit MockWebServer:
    - `start_returnsLocation_andKeepsCookies`
    - `oidcStart_invalidRedirect_showsAdminHint` (VM-Ebene)
    - `finish_wrongState_rejected`
    - `finish_sendsVerifierAndSessionCookie`
    - `pending_survivesRecreate`
  - `SetupViewModelTest`:
    - `status_withOpenid_showsSsoButton`
    - `oidc_tabClosedWithoutCallback_resetsLoading`
- Docs: `docs/release-anleitung.md` bekommt einen kurzen Abschnitt „SSO aktivieren“ (die ABS-Einstellung oben).

**Interfaces:**
- Consumes: bestehender Login-Speicherweg in `SetupViewModel.attempt` (in eine gemeinsame private Funktion
  auslagern, die `LoginResponse` + Adressen nimmt).
- Produces: `OidcLogin`, Deep-Link `lauschig://oauth`.

- [ ] Step 1–5, Commit `feat(setup): sign in with SSO (audiobookshelf openid)`

### Task 6: Podcast-Sync und Episoden-Fortschritt

**Files:**
- Modify: `sync/LibrarySync.kt`:
  - `loadDetails` erkennt Podcasts (`mediaType == "podcast"`). Ablauf:
    1. Episoden in `episodes` speichern (`replaceForItem`).
    2. Je Episode Audiodatei und Kapitel unter `PlayableKey.episode(itemId, episodeId)` in `audio_files`/`chapters`
       ablegen (`index = 1`).
    3. Die Item-Zeile wie bisher.
  - Neu: `suspend fun loadEpisodes(serverId, itemId)` als Alias für Klarheit.
  - `syncAll`: Für jede Podcast-Bibliothek zusätzlich `recentEpisodes(limit = 30)` holen und als `recent = true`
    upserten (vorherige `recent`-Markierungen der Bibliothek zurücksetzen). Ein Fehler dabei bricht den Sync nicht ab.
- Modify: `sync/ProgressSync.kt`:
  - **Pull:** `mediaProgress` mit `episodeId` landet unter dem Episoden-Key, statt gefiltert zu werden.
  - **Push:** Für dirty Zeilen den Key zerlegen; Episoden gehen an `updateEpisodeProgress`.
  - **Sessions:** Beim Hochladen `libraryItemId` + `episodeId` aus dem Key setzen.
  - Dieselbe Neuester-gewinnt-Regel und dieselben `PositionJump`-Regeln wie bei Büchern.
- Modify: `playback/ProgressActions.kt` (`pullFromServer`, Dauer für Episoden aus `EpisodeDao`),
  `db/dao/ItemDao.kt` bzw. `OtherDaos.kt`. Bestehende Abfragen, die `progress`/`downloads` mit `items` joinen, dürfen
  Episoden-Keys nicht als Bücher zeigen; per Join auf `items.id` ist das automatisch so. Prüfen und für Start
  „Weiterhören“/„Heruntergeladen“ Podcast-Items ausschließen (in 2a geparkt).
- Test:
  - `ProgressSyncTest`:
    - `episodeProgress_pulledUnderEpisodeKey`
    - `episodeProgress_pushedWithEpisodeId`
    - `episodeSession_uploadedWithEpisodeId`
  - `LibrarySyncTest`:
    - `podcastDetails_storeEpisodesAndFilesUnderKeys`
    - `recentEpisodes_failure_doesNotFailSync`
    - `replaceForItem_keepsDownloadedEpisodes`

**Interfaces:**
- Consumes: Task 1.
- Produces: `LibrarySync.loadEpisodes`, Episoden-Daten in Room. Fortschritt unter dem Key, z. B.
  `progress.get(serverId, PlayableKey.episode(...))`.

- [ ] Step 1–5, Commit `feat(podcasts): sync episodes and episode progress`

### Task 7: Episoden abspielen und herunterladen

**Files:**
- Modify: `playback/PlayerControllerImpl.kt` (Inject-Konstruktor):
  - `loadItem` für einen Episoden-Key liefert ein `ItemWithDetails`. Dessen `item` ist eine synthetische
    `ItemEntity(id = key, title = Episodentitel, author = Podcast-Titel, durationSec = Episodendauer)`, dazu Kapitel,
    Audiodateien, Fortschritt und Download unter dem Key.
  - `coverFile` und `loadDetails` bekommen den zerlegten Key (Cover = Podcast-Cover, Details = `loadEpisodes`).
  - Die Logik in `play()` bleibt unverändert.
- Modify: `playback/MediaItemFactory.kt` und `download/DownloadWorker.kt`: URLs (`audioFileUrl`, `coverUrl`) mit
  `PlayableKey.parse(key).libraryItemId`.
- Modify: Media-Metadaten (Benachrichtigung/Sperrbildschirm): Untertitel = Podcast-Titel.
- Modify: `download/DownloadManager.kt`: Episoden-Keys gehen durch (der Worker nutzt die Dateien unter dem Key).
  Speicherort `downloads/<key>/` ist in Ordnung.
- Modify: `cleanup/*` und `NextVolumePrefetcher`: Episoden ignorieren (kein nächster Band, keine Aufräum-Erinnerung).
- Test:
  - `PlayerControllerTest`:
    - `episode_playsWithEpisodeTitleAndParentCover`
    - `downloadedEpisode_playsOffline`
    - `episode_withoutFiles_online_loadsEpisodes`
  - `DownloadWorkerTest`: `episode_downloadUsesParentItemIdInUrl`
  - `MediaItemFactoryTest`: Stream-URL einer Episode

**Interfaces:**
- Consumes: Task 1, Task 6.
- Produces: `PlayerController.play(serverId, PlayableKey.episode(itemId, episodeId))` funktioniert;
  `DownloadManager.enqueue(serverId, episodeKey)` funktioniert.

- [ ] Step 1–5, Commit `feat(podcasts): play and download single episodes`

### Task 8: Podcast-Oberfläche

**Files:**
- Create: `ui/podcast/PodcastScreen.kt`, `PodcastViewModel.kt`.
  - Die Route `ItemDetail(itemId)` zeigt für Podcast-Items (Bibliothek `PODCAST` oder `numEpisodes > 0`) den
    Podcast-Bildschirm statt der Buch-Details. Die Weiche sitzt in `AppNavHost` bzw. `ItemDetailRoute`.
  - **Kopf:** Cover, Titel, Autor, Beschreibung (ausklappbar), Anzahl Episoden.
  - **Episodenliste:** neueste zuerst, Lazy, stabile Keys. Pro Zeile:
    - Datum, Titel, Dauer bzw. Restzeit
    - Fortschrittsbalken, „gehört“-Haken
    - ▶ (spielt bzw. setzt fort), Download-Symbol mit Status (`DownloadIconButton` wiederverwenden)
  - **Long-Press** auf eine Episode öffnet die Fortschritts-Aktionen (`progressEntries`/`ProgressActionHost` mit
    Episoden-Key).
  - Fehlen die Episoden und ist die App online, werden sie geladen. Offline erscheinen nur die gespeicherten,
    darüber der Hinweis „Weitere Episoden, sobald online“.
  - Filter oben: „Alle · Ungehört · Heruntergeladen“.
- Modify: `ui/start/StartViewModel.kt` + `StartScreen.kt`:
  - Neue Zeile **„Neue Episoden“**, nur wenn es Podcast-Bibliotheken gibt.
  - Inhalt: angefangene Episoden zuerst, dann `recent`-Episoden, ungehört, höchstens 15.
  - Kachel = Podcast-Cover mit Episodentitel. Tippen öffnet den Podcast, ▶ spielt die Episode.
  - Die Zeilen „Weiterhören“ und „Heruntergeladen“ zeigen keine Podcast-Items (siehe Task 6).
- Modify: `ui/common/KindFilterChips.kt`: Chip „Podcasts“ erscheint, sobald eine Podcast-Bibliothek existiert. Der
  Platzhalter-Hinweis aus Phase 1 entfällt.
- Modify: `settings/*`: „Neue Episoden automatisch laden“: Aus / neueste 1 / 3 / 5 pro Podcast, Standard **Aus**.
  - Nach jedem Sync lädt `SyncWorker` die neuesten N ungehörten `recent`-Episoden je Podcast über `DownloadManager`
    (WLAN-Regel greift dort).
  - Bereits geladene oder gehörte Episoden werden nicht erneut geladen.
- Test:
  - `PodcastViewModelTest`:
    - `episodes_newestFirst_withProgress`
    - `filter_unplayed`
    - `offline_showsStoredOnlyWithHint`
  - `StartViewModelTest`:
    - `newEpisodes_row_inProgressFirst`
    - `noPodcastLibrary_noRow`
    - `continueRow_excludesPodcasts`
  - `AutoDownloadTest`: `downloadsNewestN_skipsPlayedAndDownloaded`
  - UI-Test: `episodeRow_playAndDownloadButtons`

**Interfaces:**
- Consumes: Tasks 1, 6, 7; `ItemActionsViewModel` (mit Key).
- Produces: Podcast-Bildschirm, Start-Zeile, Einstellung.

- [ ] Step 1–5, Commit `feat(podcasts): podcast page, new episodes on start, auto-download`

### Task 9: Abschluss

- Spec 2.2a/2.3: die Entscheidungen nachtragen (Playable-Key, Audible-Weg, RMAB-Token, SSO-Redirect `lauschig://oauth`).
- Roadmap in `docs/superpowers/plans/2026-10-31-phase1-offline-core.md`: 2b erledigt.
- `docs/release-anleitung.md`: SSO-Abschnitt (falls nicht in Task 5) und RMAB-Hinweis (Token wird beim Verbinden
  erzeugt und taucht in RMAB unter den API-Tokens des Nutzers auf).
- Voller Lauf: `timeout 40m ./gradlew :app:assembleDebug :app:testDebugUnitTest :abs-api:test lint`. Grün.
- Commit `docs: phase 2b decisions, roadmap, release notes`
