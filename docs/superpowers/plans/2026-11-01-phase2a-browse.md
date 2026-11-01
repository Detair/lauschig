# Lauschig Phase 2a – Stöbern: Start, Entdecken, Suche, Autor-/Sprecher-/Serien-/Genre-Seiten – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die Reiter Entdecken und Suche funktionieren, der Start-Bildschirm ist vollständig, und Autor, Sprecher, Serie, Genre und Tag sind antippbar. Jede dieser Seiten funktioniert auch offline, weil sie aus Room liest.

**Architecture:** Der Library-Sync spiegelt den ganzen Server-Katalog bereits nach Room (minifizierte Items mit Autor, Sprecher, Serie, Genres, Tags). Phase 2a baut darauf auf: Stöbern, Suche und Entitätsseiten sind **Room-Abfragen**, das Netz wird nur für Zusatzinfos genutzt (Autorenbild und -beschreibung), die ausfallen dürfen. Neu in Room sind `addedAt` und `publishedYear` (Migration v2 → v3).

**Tech Stack:** wie Phase 1 (Kotlin, Compose/M3, Hilt, Room, Retrofit, Coil 3, Robolectric).

**Spec:** `docs/superpowers/specs/2026-10-31-lauschig-design.md` (2.2, 2.2a, 2.3, 2.4). Phase-Übersicht: `docs/superpowers/plans/2026-10-31-phase1-offline-core.md`.

**Nicht in 2a (folgt in 2b):** fehlende Serienbände aus externen Metadaten (Audible/Audnexus) und die RMAB-Anfrage, Podcasts, OIDC-Login.

## Global Constraints

- Alle Constraints aus Phase 1 gelten weiter (Offline zuerst, Strings EN + DE, keine hart codierten Texte, Conventional Commits, Gradle-Läufe immer mit `timeout`).
- **Stöbern, Suche und Entitätsseiten lesen ausschließlich aus Room.** Online-Zusatzdaten (Autor-Bild/-Beschreibung) sind optional, gecacht im Speicher, und ihr Ausfall ändert nur, dass sie fehlen.
- Cover-Kacheln überall wie in Phase 1 nach dem ersten Gerätetest: Tippen → Details, ▶ → abspielen, Long-Press → Aktionsmenü (`CoverTile(onClick, onLongClick, onPlay)` + `ItemActionsSheet`).
- Filter-Chips „Alle · Hörbücher · Hörspiele“ (`KindFilterChips`, gespeichert pro Bildschirm über `FilterPreferences`) auf Entdecken, Suche und Entitätsseiten.
- ABS speichert mehrere Autoren bzw. Sprecher als `authorName`/`narratorName` = Namen getrennt durch `", "`. Eine Person gilt als Treffer, wenn einer der getrennten Namen (getrimmt, Groß-/Kleinschreibung egal) passt, nie per Teilstring („Ann“ ≠ „Anne“).
- Große Bibliotheken: Abfragen und Gruppierungen laufen auf `Dispatchers.Default`/Room-Executor, nie auf Main. Ziel: Suche reagiert bei 5 000 Titeln in < 150 ms nach dem Debounce (200 ms).

## Review Focus

1. **Offline:** Entdecken, Suche und alle Entitätsseiten zeigen offline den vollständigen Katalog aus Room; nur das Autorenbild fehlt. *(Task 2: `authorPage_offline_showsBooksWithoutBio`; Task 3: `search_offline_findsServerOnlyTitles`.)*
2. **Mehrere Autoren/Sprecher pro Titel:** „Stephen Hawking, Lucy Hawking“ erscheint auf beiden Autorenseiten, „Ann“ findet nicht „Anne“. *(Task 1: `byPerson_matchesEachCommaSeparatedName_exactly`.)*
3. **Serien mit Dezimal- oder fehlenden Bandnummern** (1, 1.5, 2, 4 und Bände ohne Nummer): richtige Reihenfolge, Lücke 3 wird als „Band 3 – nicht in der Bibliothek“ angezeigt, Bände ohne Nummer stehen am Ende. *(Task 2: `seriesPage_ordersBySequence_andShowsGaps`.)*
4. **Migration v2 → v3** auf einem Gerät mit Fortschritt und Downloads: nichts geht verloren, `addedAt` füllt sich beim nächsten Sync. *(Task 1: `v2ToV3_keepsData`.)*
5. **Leere oder fehlende Metadaten** (kein Autor, keine Genres, keine Serie): keine leeren Chips, keine Abstürze, Titel erscheint trotzdem in „Alle Titel“ und in der Suche. *(Task 4: `discover_itemsWithoutMetadata_listedOnlyInAllTitles`.)*

---

### Task 1: Katalog-Daten in Room (addedAt, publishedYear, Abfragen)

**Files:**
- Modify: `abs-api/.../library/LibraryModels.kt` (`LibraryItemDto.addedAt: Long = 0`, `BookMetadataDto.publishedYear: String? = null`)
- Modify: `app/.../db/entities/ItemEntity.kt` (`addedAt: Long = 0`, `publishedYear: String? = null`, beide mit `@ColumnInfo(defaultValue = …)`), `db/LauschigDatabase.kt` (v3, `AutoMigration(2, 3)`), `sync/LibrarySync.kt` (Mapping)
- Create: `app/.../db/dao/CatalogDao.kt`, `app/.../catalog/Catalog.kt`
- Test: `app/src/test/.../catalog/CatalogTest.kt`, `db/MigrationTest.kt` (erweitern)

**Interfaces:**
- Produces:
  - `CatalogDao` (Room): `observeAll(serverId): Flow<List<ItemEntity>>`, `observeRecentlyAdded(serverId, limit: Int): Flow<List<ItemEntity>>`.
  - `class Catalog @Inject constructor(...)`: reine Kotlin-Auswertung über einer Item-Liste, damit sie testbar und schnell ist:
    - `fun splitPeople(names: String?): List<String>` (Trennung an `", "`, getrimmt, leere raus)
    - `fun byPerson(items, role: PersonRole, name): List<ItemEntity>` mit `enum class PersonRole { AUTHOR, NARRATOR }`
    - `fun bySeries(items, name): List<SeriesEntry>` mit `sealed interface SeriesEntry { data class Volume(item); data class Missing(sequence: String) }`, sortiert nach `seriesSortKey`, ganzzahlige Lücken zwischen min und max als `Missing`, Bände ohne Sortkey am Ende
    - `fun byGenre(items, genre)`, `fun byTag(items, tag)` (exakt, Groß-/Kleinschreibung egal)
    - `fun facets(items): Facets` mit `data class Facets(val authors: List<Facet>, val narrators: List<Facet>, val series: List<Facet>, val genres: List<Facet>, val tags: List<Facet>)`, `data class Facet(val name: String, val count: Int, val coverItemId: String?)`, nach Anzahl absteigend, dann Name
    - `fun search(items, query): SearchResult` mit Titeln (Treffer in Titel/Untertitel/Autor/Sprecher/Serie, Titeltreffer zuerst) und Facetten-Treffern (Präfix eines Wortes, Groß-/Kleinschreibung und Umlaute über `Normalizer` NFD ohne Diakritika egal)

- [ ] **Step 1: Tests schreiben** – `byPerson_matchesEachCommaSeparatedName_exactly` („Stephen Hawking, Lucy Hawking“ auf beiden Seiten; „Ann“ trifft nicht „Anne“), `bySeries_ordersAndMarksGaps` (Sortkeys 1, 1.5, 2, 4, null → Volume 1, 1.5, 2, Missing „3“, Volume 4, Volume ohne Nummer), `facets_countAndSkipEmpty` (keine leeren Namen), `search_matchesWordPrefix_ignoringCaseAndUmlauts` („schlus“ findet „Schlüssel“; „ssel“ findet nichts), `v2ToV3_keepsData` (wie bestehender MigrationTest, Ausgangsschema `2.json`).
- [ ] **Step 2: Tests laufen lassen → FAIL.** `timeout 15m ./gradlew :app:testDebugUnitTest --tests '*CatalogTest' --tests '*MigrationTest'`
- [ ] **Step 3: Implementieren** (DTO-Felder, Entity + Migration, Mapping in `toListEntity`, `CatalogDao`, `Catalog`).
- [ ] **Step 4: Tests → PASS**, plus `:abs-api:test` (Fixture für `addedAt`/`publishedYear` ergänzen).
- [ ] **Step 5: Commit** `feat(catalog): addedAt/publishedYear and catalog queries`

### Task 2: Entitätsseiten (Autor, Sprecher, Serie, Genre, Tag)

**Files:**
- Create: `app/.../ui/browse/BrowseScreen.kt`, `BrowseViewModel.kt`; `abs-api/.../library/LibraryApi.kt` (`@GET("api/libraries/{id}/authors") suspend fun authors(@Path("id") id: String): AuthorsResponse`), Modelle `AuthorsResponse(authors: List<AuthorDto>)`, `AuthorDto(id, name, description?, imagePath?)`
- Modify: `ui/nav/Destinations.kt` (`@Serializable data class Browse(val kind: BrowseKind, val name: String)`, `enum class BrowseKind { AUTHOR, NARRATOR, SERIES, GENRE, TAG }`), `ui/nav/AppNavHost.kt`, `ui/detail/ItemDetailScreen.kt` (Chips antippbar → `Browse`)
- Test: `ui/browse/BrowseViewModelTest.kt`, `ui/browse/BrowseScreenTest.kt`

**Interfaces:**
- Consumes: `Catalog`, `CatalogDao`, `CoverTile`, `ItemActionsSheet`, `KindFilterChips`.
- Produces: Route `Browse(kind, name)`; `ItemDetailRoute(onOpenBrowse: (BrowseKind, String) -> Unit)`.

Seitenaufbau: Kopf mit Name und Anzahl („12 Titel“); bei AUTOR zusätzlich Bild (Coil, URL `api/authors/{id}/image`) und Beschreibung (ausklappbar), sobald online geladen (`AuthorInfoCache`, Singleton, Map name→AuthorDto, einmal pro Bibliothek und App-Lauf geladen, Fehler = leer). Darunter Gitter der Titel; bei SERIE eine Liste in Bandreihenfolge mit `Missing`-Platzhaltern (ausgegraute Kachel „Band 3“ + „nicht in der Bibliothek“). Auf der Autorenseite zusätzlich eine Zeile „Serien“ (Facetten der Treffer) mit Link auf die Serienseite. In der Detailansicht werden Autor und Sprecher pro Person einzeln als Chip angezeigt.

- [ ] **Step 1: Tests** – `authorPage_offline_showsBooksWithoutBio`, `seriesPage_ordersBySequence_andShowsGaps`, `detailChips_openBrowse_perPerson` (Chip „Lucy Hawking“ → `Browse(AUTHOR, "Lucy Hawking")`), `browse_kindFilter_appliesAndIsSaved`.
- [ ] **Step 2: → FAIL**
- [ ] **Step 3: Implementieren**
- [ ] **Step 4: → PASS**
- [ ] **Step 5: Commit** `feat(browse): author, narrator, series, genre and tag pages`

### Task 3: Suche

**Files:**
- Create: `app/.../ui/search/SearchScreen.kt`, `SearchViewModel.kt`
- Modify: `ui/nav/AppNavHost.kt` (Reiter Suche ersetzt `ComingSoonScreen`)
- Test: `ui/search/SearchViewModelTest.kt`, `ui/search/SearchScreenTest.kt`

Aufbau: Suchfeld oben (Fokus beim Öffnen des Reiters, Löschen-Knopf), Filter-Chips, dann Abschnitte „Titel“ (Gitter), „Autoren“, „Sprecher“, „Serien“, „Genres“ (Chips → `Browse`). Leeres Feld: zuletzt gesuchte Begriffe (max. 8, DataStore) als Chips. Kein Treffer: „Nichts gefunden für „…““. Debounce 200 ms; Auswertung über `Catalog.search` auf `Dispatchers.Default`.

- [ ] **Step 1: Tests** – `search_offline_findsServerOnlyTitles` (Item ohne Download, Verbindung offline → Treffer), `search_debounces_andGroupsSections`, `search_recentQueries_storedAndCapped`, `searchScreen_tapAuthor_opensBrowse`.
- [ ] **Step 2: → FAIL**
- [ ] **Step 3: Implementieren**
- [ ] **Step 4: → PASS**
- [ ] **Step 5: Commit** `feat(search): local catalog search with sections and recent queries`

### Task 4: Entdecken

**Files:**
- Create: `app/.../ui/discover/DiscoverScreen.kt`, `DiscoverViewModel.kt`
- Modify: `ui/nav/AppNavHost.kt` (Reiter Entdecken ersetzt `ComingSoonScreen`; `ComingSoonScreen` danach löschen, wenn ungenutzt)
- Test: `ui/discover/DiscoverViewModelTest.kt`, `ui/discover/DiscoverScreenTest.kt`

Aufbau (eine `LazyVerticalGrid` mit Abschnitten über die volle Breite): Filter-Chips; „Neu hinzugefügt“ (Zeile, 20 nach `addedAt`); „Serien“ (Zeile, Kachel = Cover des ersten Bands + Name + „n Bände“, → Serienseite); „Genres“ (Chips, → Genre-Seite); „Autoren“ (Zeile mit Namen und Anzahl, → Autorenseite; Bild, falls `AuthorInfoCache` es hat); „Alle Titel“ mit Sortier-Menü (Titel, Autor, Neu hinzugefügt, Dauer; gespeichert pro Bildschirm) als Gitter.

- [ ] **Step 1: Tests** – `discover_sectionsFromCatalog_respectKindFilter`, `discover_sortAllTitles_persists`, `discover_itemsWithoutMetadata_listedOnlyInAllTitles`, `discoverScreen_tapSeries_opensSeriesPage`.
- [ ] **Step 2: → FAIL**
- [ ] **Step 3: Implementieren**
- [ ] **Step 4: → PASS**
- [ ] **Step 5: Commit** `feat(discover): store-like browsing of the whole catalog`

### Task 5: Start-Bildschirm vollständig

**Files:**
- Modify: `ui/start/StartViewModel.kt`, `ui/start/StartScreen.kt`
- Test: `ui/start/StartViewModelTest.kt` (neu), `ui/StartScreenTest.kt` (erweitern)

Abschnitte in dieser Reihenfolge, leere Abschnitte entfallen: „Weiterhören“ (alle angefangenen, nicht ausgeblendeten, neuester zuerst, max. 10), „Als Nächstes in deinen Serien“ (pro Serie mit mindestens einem gehörten oder angefangenen Band der nächste ungehörte, nicht angefangene Band; neueste Aktivität zuerst; max. 10), „Neu auf dem Server“ (10 nach `addedAt`), „Heruntergeladen“. Jede Abschnittsüberschrift außer „Heruntergeladen“ hat „Alle“ → passender Ort (Bibliothek „Am Hören“, Entdecken). Leerer Zustand wie bisher (Knopf zur Bibliothek).

- [ ] **Step 1: Tests** – `start_nextInSeries_skipsHeardAndStarted`, `start_continueRow_excludesHidden_newestFirst`, `start_recentlyAdded_fromAddedAt`, `startScreen_sectionsInOrder_emptyOnesHidden`.
- [ ] **Step 2: → FAIL**
- [ ] **Step 3: Implementieren**
- [ ] **Step 4: → PASS**
- [ ] **Step 5: Commit** `feat(start): continue row, next in your series, new on the server`

### Task 6: Abschluss

- [ ] **Step 1:** `timeout 20m ./gradlew :abs-api:test :app:testDebugUnitTest lint assembleDebug` → grün.
- [ ] **Step 2:** Auf Fabians Handy installieren (`:app:installDebug`) und per adb/uiautomator selbst prüfen: Entdecken, Suche (auch im Flugmodus nur nach Rückfrage), Autor-/Serien-/Genre-Seite, Start. Nichts auslösen, was ABS-Daten ändert.
- [ ] **Step 3:** Roadmap in `2026-10-31-phase1-offline-core.md` aktualisieren (Phase 2 → 2a erledigt, 2b offen), Commit, PR gegen `main`.
