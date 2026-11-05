# Phase 6 – Qualität, Performance & Komfort (Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Implement the post-Phase-5 quality, performance, and user-comfort improvements identified during the project review: configurable asymmetric seek intervals, consistent player buttons (Variant B), search facet caching, sleep timer audio warning, external storage (SD card) support, family support diagnostic log sharing, optional auto-cleanup of completed downloads, a warm night theme, dynamic speech compression, core performance optimizations (Coil disk cache, Room database indexes, recomposition throttling), incremental multi-module architecture, UI polish & ergonomics (2-timestamp system with chapter/total toggle, replacement of the downloaded badge icon, detail metadata quick bar, cover thumbnail play-button removal, stats tooltips, mini-player swipe gesture), and architecture preparation for future multi-profile / cast / chapter search extensions.

**Branch:** `phase-6` (branched from `main` after PR #16 / Phase 5C).

---

## 1. Übersicht & Architekturentscheidungen

| Thema | Problem | Entscheidung |
|---|---|---|
| **Asymmetrische Sprungzeiten** | 30s fest verdrahtet (`SEEK_MS`). Zurück ist oft zu viel (Satz verpasst), vor oft zu wenig (Intro). | Zwei Einstellungen in `PlaybackSettings`: `seekBackSec` (5, 10, **15**, 30 s) und `seekForwardSec` (15, **30**, 45, 60 s). Anbindung an ExoPlayer `setSeekBackIncrementMs` / `setSeekForwardIncrementMs`, dynamische Icons/Labels im Player (`Replay10`, `Replay15`, `Forward30`, etc.) und Android Auto. |
| **Katalogsuche: Facetten-Caching** | `Catalog.search` berechnet bei jedem Tastenanschlag `val facets = facets(items)` über den gesamten Katalog neu. | Facetten-Extraktion von der Suchbegriff-Filterung trennen: `SearchViewModel` cacht die berechneten Facetten basierend auf der Katalog-Liste (`observeAll`). Bei neuer Eingabe werden nur noch die gecachten Facetten gefiltert. |
| **Sleep-Timer Hinweiston** | Hörer mit geschlossenen Augen merken das Ausblenden oft erst, wenn die Pause bereits eingetreten ist. | Optionale Einstellung `sleepChimeEnabled` (Standard: an). 30 Sekunden vor Ablauf (Beginn des Fade-Outs) ertönt ein dezenter, leiser Hinweiston, der signalisiert: „Jetzt schütteln, falls du noch wach bist“. |
| **SD-Karten-Auswahl für Downloads** | Downloads liegen fest in `context.filesDir` (interner Flash-Speicher). Große Bibliotheken füllen das Gerät. | `DownloadSettings.storageVolume`: Erkennung sekundärer Volumes via `ContextCompat.getExternalFilesDirs(context, null)`. Bei mehreren Speichermedien Auswahl in den Download-Einstellungen (Intern / SD-Karte). `DownloadFiles` nutzt den konfigurierten Pfad. |
| **Diagnose-Log teilen („Familien-Support“)** | Fehleranalyse bei Familienmitgliedern ohne technisches Wissen scheitert an `adb logcat`. | Ringpuffer-/Datei-Logger (Token und Passwörter maskiert). In Einstellungen → Über Lauschig: Knopf „Diagnose-Log teilen“ via Android ShareSheet (`FileProvider` + `Intent.ACTION_SEND`). |
| **Automatisches Aufräumen beendeter Titel** | Beendete Downloads verbleiben auf dem Gerät, bis man manuell aufräumt. | Einstellung `autoDeleteFinished`: *Nie (Standard)*, *Sofort*, *Nach 3 Tagen*, *Nach 7 Tagen*. Ausführung im Hintergrund durch `CleanupWorker` bzw. nach Abschluss eines Buchs. |
| **Warmes Nacht-Farbschema** | Das aktuelle Dark-Theme nutzt kühles Nachtblau (`#18213A`), was nachts im Bett blendet und die Augen reizt. | Warmes Farb-Preset *„Kaminfeuer & Bernstein“*: Warmes Mokka-Anthrazit (`#161311`), samtweiches Creme-Weiß (`#F4ECE1`) und warmer Honig-Bernstein-Akzent (`#F5BA42`). Wählbar in den Einstellungen unter *Erscheinungsbild*. |
| **Player-Tastenleiste** | Das alte 4+3 Raster ist unruhig; Sleep-Timer und Tempo waren isoliert uneinheitlich. | **Entscheidung Fabian (2026-11-04): Variante B.** Drei symmetrische Regler oben (`[⚡ Tempo / 1.25×]`, `[🌙 Sleep / 30 Min.]`, `[🎙️ Klang / +3 dB]`) plus ein großzügiges horizontales Auszieh-Banner für Inhalte (`Kapitel, Warteschlange & Notizen`). Jeder Button besitzt dieselbe Anatomie (Icon + Name + Wert). |
| **Dynamik-Kompressor & Sprachfokus** | Ältere Hörspiele haben teils extreme Lautstärkesprünge (Flüstern vs. laute Soundeffekte/Musik), was nachts im Bett aufschreckt. | Sanfter Nacht-Kompressor via Androids `DynamicsProcessing` (ab API 28 / minSdk). Begrenzt laute Pegelspitzen und hebt leise Sprache verständlich an. Ein/Aus-Schalter im [🎙️ Klang]-Sheet. |
| **Performance: Caching & Indizes** | Coil hat keinen DiskCache (Cover werden nach Neustart neu geladen); Room-Queries machen Full Table Scans; Recomposition-Spikes bei Playback-Ticks. | (1) 150 MB Coil DiskCache in `context.cacheDir`. (2) Compound-Indizes auf `progress(serverId, isFinished, lastUpdate)`, `downloads(serverId, state)` und `items(serverId, seriesName, seriesSortKey)`. (3) Throttling von `observeAll`-Listen während Playback. |
| **Schrittweise Modularisierung** | Monolithisches `:app`-Modul mit >95.000 Zeilen verlangsamt KSP, Hilt und Gradle-Builds spürbar. | Schrittweiser Auszug: (1) `:core:model` (reine Datenmodelle), (2) `:core:database` (Room-Entities, DAOs, Migrations getrennt von Compose), (3) `:core:playback` (ExoPlayer & Media3 Service). |
| **UI-Feinschliff & Ergonomie** | 3 Zeitstempel im Player sind überladen; Play-Buttons auf Mini-Covern verdecken Typografie; Detailseite fehlt Quick-Info; Statistikbalken nicht interaktiv. | 2-Zeitstempel-System (Elapsed links, Remaining rechts mit Tap-Toggle für Kapitel/Gesamt), Entfernung der Play-Overlays von Thumbnails, Metadaten-Leiste auf Detailseite, interaktive Diagramm-Tooltips, Mini-Player Wischgeste. |
| **Symbol für lokal gespeicherte Titel** | `Icons.Rounded.DownloadDone` (Häkchen auf Querbalken `✓_`) wirkt unruhig/missverständlich und wird oft mit „Gelesen“ verwechselt. | Ersatz durch `Icons.Rounded.ArrowCircleDown` (bewährter Streaming-Standard analog Spotify/Pocket Casts) oder `Icons.Rounded.OfflinePin` (Google Material Offline-Standard) für maximale Klarheit auf Cover-Badges, Detail-Aktionen und Source-Indikatoren. |
| **Zukünftige Ökosystem-Erweiterungen** | Familien teilen sich Tablets; Smart-Lautsprecher im Haus; lange Hörbücher haben unübersichtliche Kapitellisten. | Architektur-Vorbereitung für schnellen Profilwechsel (`AccountStore`), Google Cast / Chromecast (`CastPlayer` Media3) und Kapitelsuche im Bottom Sheet. |

---

## 2. Detaillierte Aufgaben (TDD-Schritte)

### Task 1: Asymmetrische Sprungzeiten (Skip Back & Forward)
- [ ] **1.1 PlaybackSettings & DataStore:**
  - Neue Keys: `seek_back_sec` (Int, Default 15) und `seek_forward_sec` (Int, Default 30).
  - Gültige Optionen: `SEEK_BACK_CHOICES = listOf(5, 10, 15, 30)`, `SEEK_FORWARD_CHOICES = listOf(15, 30, 45, 60)`.
  - Methoden `setSeekBackSec(sec: Int)` und `setSeekForwardSec(sec: Int)`.
  - Unit-Test: `PlaybackSettingsTest`.
- [ ] **1.2 Player & ExoPlayer Verdrahtung:**
  - `PlaybackService`: Settings beobachten und `exo.setSeekBackIncrementMs(backSec * 1000L)` und `exo.setSeekForwardIncrementMs(forwardSec * 1000L)` dynamisch setzen.
  - `PlayerController` / `PlayerControllerImpl`: `seekBy(sec: Int)` respektiert die konfigurierten Werte; `PlayerUiState` exponiert `seekBackSec` und `seekForwardSec`.
  - `CarButtons`: Android Auto Transport-Controls anpassen.
- [ ] **1.3 UI (PlayerScreen & SettingsScreen):**
  - `SettingsScreen`: Neue Zeilen in der Wiedergabe-Sektion mit `ChoiceRow` für Zurückspringen und Vorspringen.
  - `PlayerScreen`: 
    - Buttons für Replay/Forward zeigen die Sekundenzahl dynamisch (oder passende Icons `Replay10`, `Replay15`, `Forward30`, etc.) mit Barrierefreiheits-Texten („X Sekunden zurückspringen“ / „X Sekunden vorspringen“).
    - **Aktions-Buttons (Variante B – Fabians Wahl):** Das alte 4+3-Raster wird durch **Variante B** ersetzt:
      - Oben 3 gleichwertige Kacheln: `[⚡ TEMPO / 1.25×]`, `[🌙 SLEEP / 30 Min.]`, `[🎙️ KLANG / +3 dB]`. Jeder Button hat dieselbe 3-teilige Anatomie (Icon + Name + Wert). Der Sleep-Timer ist unmissverständlich klar und zeigt bei Aktivität seine Restzeit warm leuchtend an (inaktiv: „Aus“).
      - Darunter ein breites, horizontales Banner: `[📑 Kapitel & Wiedergabeliste / Aktuell: Kap. X / N >]`, das das ausziehbare Multi-Tab-Sheet (Kapitel, Warteschlange, Lesezeichen) öffnet.
  - Strings in `values/strings.xml` und `values-de/strings.xml`.
  - Tests: `PlayerScreenTest`, `SettingsScreenTest`.

---

### Task 2: Katalogsuche – Facetten-Caching & Performance
- [ ] **2.1 Reines Caching in `Catalog`:**
  - `Catalog.search(items, query)` überladen oder trennen: `Catalog.search(items: List<ItemEntity>, facets: Facets, query: String): SearchResult`.
  - Unit-Test: Verifizieren, dass `search` identische Ergebnisse liefert, ohne `facets(items)` intern jedes Mal neu aufzurufen.
- [ ] **2.2 SearchViewModel Optimierung:**
  - `SearchViewModel`: Ein Flow `val catalogFacets = catalogDao.observeAll(sid).map { catalog.facets(it) }.flowOn(Dispatchers.Default)` erstellen.
  - Die Suche kombiniert `(visibleItems, catalogFacets, query)`: Nur wenn sich die Items ändern, werden die Facetten neu indiziert; wenn nur `query` tippt, wird die gefilterte Trefferliste sofort aus den vorhandenen Facetten erzeugt.
  - Tests: `SearchViewModelTest` (Zähler für `facets`-Aufrufe bei mehreren Query-Tastendrücken).

---

### Task 3: Sleep-Timer Hinweiston (Sanftes Wecken)
- [ ] **3.1 SleepTimerMachine Erweiterung:**
  - `SleepInput`: neues Flag oder Event für die 30s-Schwelle vor Ablauf.
  - `SleepEffect`: neues Feld `playChime: Boolean = false`.
  - Reine Tests in `SleepTimerMachineTest`.
- [ ] **3.2 Audio-Ausgabe in `SleepTimer`:**
  - Setting `sleepChimeEnabled` in `PlaybackSettings` (Default `true`).
  - Wenn `playChime` aktiv und Einstellung an: leiser, sanfter System-Hinweiston (z. B. via `ToneGenerator(AudioManager.STREAM_MUSIC, 40)` oder kurzer Audio-Asset).
  - Tests in `SleepTimerTest`.

---

### Task 4: SD-Karten- und externer Speicher-Support
- [ ] **4.1 Speicherort-Erkennung & Verwaltung:**
  - Neuer Helper `StorageVolumes(context: Context)`: ermittelt alle Pfade aus `ContextCompat.getExternalFilesDirs(context, null)`.
  - Prüft Lesbarkeit, Schreibbarkeit und freien Speicherplatz (`usableSpace`).
  - Kennzeichnet internen Speicher vs. SD-Karte.
- [ ] **4.2 DownloadSettings & DownloadFiles:**
  - `DownloadSettings`: `storagePath: Flow<String?>` (null = Default interner Speicher).
  - `DownloadFiles`: initialisiert das Root-Verzeichnis mit dem konfigurierten Pfad oder fällt sicher auf den internen Speicher zurück (z. B. wenn die SD-Karte entfernt wurde).
  - Wenn ein Pfad ungültig oder nicht beschreibbar ist: automatischer Fallback auf intern mit Toast/Hinweis.
- [ ] **4.3 UI in den Download-Einstellungen:**
  - Zeigt aktuellen Speicherort und freien Platz an.
  - Auswahldialog für Speichermedium, wenn mehr als ein Volume vorhanden ist.
  - Strings in `strings.xml` / `values-de/strings.xml`.
  - Tests: `DownloadFilesTest`, `DownloadSettingsTest`.

---

### Task 5: Diagnose-Log teilen („Familien-Support“)
- [ ] **5.1 In-Memory Ringpuffer-Logger (`DiagnosticLog`):**
  - Singleton-Ringpuffer (z. B. letzte 500 Log-Zeilen).
  - Filtert sensible Daten heraus (Tokens, Passwörter, Authorization-Header, private ABS-URLs).
  - Schreibt bei Absturz oder auf Anforderung in eine Datei `cache/diagnostic-log.txt`.
- [ ] **5.2 FileProvider & Share-Intent:**
  - `FileProvider`-Eintrag für Log-Dateien.
  - In `AboutSection` / `ConnectionScreen`: Button „Diagnose-Log teilen“.
  - Triggert Android ShareSheet (`Intent.ACTION_SEND`, `text/plain`).
  - Tests: Maskierungstests für sensible Tokens, Share-Intent-Generierung.

---

### Task 6: Automatisches Löschen fertig gehörter Titel
- [ ] **6.1 Einstellung & Modell:**
  - `AutoDeleteMode`: `NEVER` (Standard), `IMMEDIATELY`, `AFTER_3_DAYS`, `AFTER_7_DAYS`.
  - In `AppSettings` / `DownloadSettings` speichern.
- [ ] **6.2 Bereinigungslogik:**
  - `CleanupManager` erweitern: prüft beendete Downloads (`progress.isFinished` und `progress.lastUpdate`).
  - Automatisches Löschen der Audio-Dateien über `DownloadFiles.delete(serverId, itemId)`, Progress und History bleiben natürlich erhalten.
  - Tests: `CleanupTest`.

---

### Task 7: Warmes Nacht-Farbschema (Kaminfeuer & Bernstein)
- [ ] **7.1 Palette in `LauschigTheme`:**
  - Neue `ColorScheme`-Definition `LauschigWarmDarkColors`:
    - `background = Color(0xFF161311)` (warmes tiefes Mokka-Anthrazit statt kaltem `#18213A`)
    - `surface = Color(0xFF161311)`
    - `surfaceContainer = Color(0xFF231C18)`
    - `surfaceContainerHigh = Color(0xFF2B231F)`
    - `primary = Color(0xFFF5BA42)` (warmes Honig-Bernstein)
    - `onSurface = Color(0xFFF4ECE1)` (weiches Creme-Weiß statt kaltem `#E2E6F2`)
    - `onSurfaceVariant = Color(0xFFB8ACA0)` (warmer Zwischenton)
  - Unit-Test: Kontrastprüfung (mind. 4.5:1 für Text, 3:1 für Steuerelemente).
- [ ] **7.2 Settings & Auswahllogik:**
  - `DarkThemeTone` in `UiSettings`: `NIGHT_BLUE` (Standard) vs. `WARM_AMBER`.
  - `colorSchemeFor(dark, pureBlack, tone)` bindet die warme Farbpalette ein.
  - UI-Auswahl in `AppearanceSection`.
  - Tests: `ColorSchemeTest`, `AppearanceSectionTest`.

---

### Task 8: Audio-Dynamikkompressor & Sprachfokus (Nachtmodus-Klang)
- [ ] **8.1 Kompressor-Engine in `PlaybackComfort`:**
  - `android.media.audiofx.DynamicsProcessing` (ab Android 9 / API 28, exakt Lauschigs `minSdk`).
  - Sanfter Single-Band / MBC-Kompressor mit Limiter (sanfter Anlauf, dämpft laute Soundeffekte/Musik und hebt leises Flüstern an).
  - Klasse `AudioCompressor`: bindet sich wie `AudioBoost` an `exo.audioSessionId`.
  - Tests: Aktivierung, Deaktivierung, Session-ID-Wechsel, sauberes Release ohne Memory Leaks.
- [ ] **8.2 Settings & UI im [🎙️ KLANG]-Sheet:**
  - `PlaybackSettings`: `val speechCompressor: Flow<Boolean>` (Default `false`).
  - Schalter im `Klang`-Sheet (Button `[🎙️ KLANG]`): *„Sprachfokus & Nacht-Kompression“* (Untertitel: *„Dämpft laute Geräusche und hebt leise Stimmen an – ideal zum Einschlafen“*).
  - Tests: `PlaybackSettingsTest`, `ComfortViewModelTest`.

---

### Task 9: Performance, Caching & Room-Indizes
- [ ] **9.1 Coil 3 DiskCache:**
  - `newCoverImageLoader` in `CoverImageLoader.kt` erweitern:
    - `.diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("coil_covers")).maxSizeBytes(150L * 1024 * 1024).build() }`
  - Cover laden offline und nach App-Neustart sofort aus dem lokalen Speicher, ohne Netzwerk-Roundtrips.
  - Tests: `CoverImageLoaderTest` (Verifizieren, dass DiskCache initialisiert ist).
- [ ] **9.2 Room Compound-Indizes:**
  - `ProgressEntity`: `@Index(value = ["serverId", "isFinished", "lastUpdate"])`
  - `DownloadEntity`: `@Index(value = ["serverId", "state"])`
  - `ItemEntity`: `@Index(value = ["serverId", "seriesName", "seriesSortKey"])`
  - DB-Version anpassen (AutoMigration von v10 auf v11).
  - Tests: `DatabaseTest`, Schema-Export verifizieren.
- [ ] **9.3 Recomposition & Playback-Throttling:**
  - `LibraryViewModel`: `progressDao.observeAll(sid)` während aktiver Wiedergabe leicht throttlen/debouncen (`debounce(1_500L)`), damit nicht jeder Sekunden-Tick des Players die gesamte Bibliotheksliste neu berechnet.
  - Tests: Recomposition- und Emissions-Zähler in `LibraryViewModelTest`.

---

### Task 10: Schrittweise Modularisierung (Build-Zeiten & Entflechtung)
- [ ] **10.1 Vorbereitung & Modul `:core:model`:**
  - Anlegen von `core/model/build.gradle.kts` (reines Kotlin- oder leichtes Android-Modul).
  - Umzug zustandsloser Datenklassen (`ItemSummary`, `PlayableKey`, `Timeline`, Enums).
  - Verifikation: `:app` und `:abs-api` kompilieren sauber gegen `:core:model`.
- [ ] **10.2 Modul `:core:database`:**
  - Anlegen von `core/database/build.gradle.kts` mit Room + KSP.
  - Umzug von `LauschigDatabase`, `Converters`, DAOs und Entities.
  - Trennt Room-KSP-Kompilierung vom Compose-Compiler im `:app`-Modul.
  - Verifikation: Datenbank-Tests laufen isoliert in `:core:database`.
- [ ] **10.3 Modul `:core:playback`:**
  - Anlegen von `core/playback/build.gradle.kts` mit Media3, ExoPlayer und Audio-Effekten (`DynamicsProcessing`, `LoudnessEnhancer`).
  - Umzug von `PlaybackService`, `PlayerController`, `SleepTimer`, `CarSafePlayer`.
  - Hilt-Bindings anpassen.
- [ ] **10.4 Verifikation & Build-Zeiten:**
  - Verifikation: Alle 1.492+ Tests grün.
  - Benchmark der Gradle-Build-Zeiten vor und nach der Modularisierung.

---

### Task 11: UI-Feinschliff, Ergonomie & Symbole
- [ ] **11.1 Player 2-Zeitstempel-System & Umschalt-Logik:**
  - `PlayerScreen.kt` & `PlayerProgress`: Ersetzen der 3 redundanten Zeitstempel (`18:07`, `3:09:58 / 7:54:00`, `1:02:04`) durch 2 Werte am Fortschrittsbalken: Links abgelaufene Zeit (`18:07`), Rechts verbleibende Zeit (`-1:02:04`).
  - Umschaltung der rechten Restzeit per Fingertipp (Toggle):
    - Modus A: *„Verbleibend im aktuellen Kapitel“* (z. B. `-14:20`)
    - Modus B: *„Verbleibend im gesamten Buch“* (z. B. `-3:18:42`)
  - Persistenz der Benutzerauswahl in `UiSettings` (`playerRemainingTimeMode`).
  - Barrierefreie Screenreader-Ansage („Kapitel verbleibend: X Minuten, zum Umschalten doppeltippen“).
  - Tests: `PlayerScreenTest`, `PlayerProgressTest`.
- [ ] **11.2 Neues Symbol für lokal heruntergeladene Titel („Lokal auf dem Gerät“):**
  - **Problem:** Das bisherige Symbol `Icons.Rounded.DownloadDone` (Häkchen über einer Querlinie `✓_`) wirkt auf kleinen Cover-Badges unruhig, unscharf und wird leicht mit einer erledigten To-Do-Checkbox („Gelesen“) verwechselt.
  - **Lösung:** Ersetzung durch das etablierte Audio-Offline-Symbol `Icons.Rounded.ArrowCircleDown` (Streaming-Standard analog Spotify / Pocket Casts) oder `Icons.Rounded.OfflinePin` (Google Material Design Standard für offline gepinnte Inhalte).
  - Konsistente Anwendung an allen relevanten UI-Stellen:
    - `CoverTile.kt`: `StatusBadge` in der oberen rechten Ecke der Buch-Cover mit warmem Kontrast-Badge.
    - `ItemActions.kt`: `DownloadIconButton` im Zustand `Complete`.
    - `CollectionsUi.kt`: Download-Status in Bibliotheks- und Sammlungslisten.
    - `SourceIndicator.kt`: Indikator `PlaySource.DOWNLOAD` im Player.
    - `PodcastScreen.kt`: Status in Podcast-Episodenlisten.
  - Text-Ressource: `R.string.cover_offline_available` („Auf diesem Gerät verfügbar“).
  - Tests: `CoverTileTest`, `ItemActionsTest`, `SourceIndicatorTest`.
- [ ] **11.3 Buch-Detailseite Quick-Info & Button-Harmonisierung:**
  - `BookDetailScreen.kt`:
    - Kompakte Metadaten-Leiste mit Badges direkt unter dem Titel vor den CTA-Buttons:
      `[⏱ 14h 22m]`, `[🎙 David Nathan]`, `[📖 Ungekürzt]`, `[📅 2024]`
    - Button-Hierarchie harmonisieren:
      - Großer, gut treffbarer Haupt-Button für Wiedergabe (`[ ▶ Weiterhören ]` bzw. `[ ▶ Jetzt hören ]`) in Primärfarbe.
      - Daneben kompakter Begleit-Button für Download (als `FilledTonalButton` oder eleganter `DownloadIconButton`).
  - Tests: `BookDetailScreenTest`.
- [ ] **11.4 Startseite & Bibliotheks-Cover: Entfernung des Play-Button Overlays:**
  - `CoverTile.kt`: Entfernen des runden Play-Button-Overlays (`FilledIconButton(Play)`) auf kleinen Cover-Thumbnails.
  - Cover-Tap öffnet die Buch-Detailseite (oder Player bei „Zuletzt gehört“). Verhindert das Verdecken der Cover-Typografie und versehentliches Abspielen beim horizontalen Scrollen.
  - Tests: `CoverTileTest`, `StartScreenTest`.
- [ ] **11.5 Statistik-Diagramm: Interaktive Tooltips:**
  - `StatsScreen.kt`: Antippen eines Balkens (Tages-, Wochen- oder Monatsverlauf) zeigt einen schwebenden, sanft animierten Tooltip über dem Balken mit exakter Minutenzahl und Datum.
  - Barrierefreiheit: Screenreader liest bei Fokus den Wert des ausgewählten Balkens vor.
  - Tests: `StatsScreenTest`.
- [ ] **11.6 Mini-Player Wischgeste (Swipe-up to expand):**
  - `MiniPlayer.kt`: Unterstützung von Wischgesten nach oben (`swipeable` / `draggable`), um den Vollbild-Player flüssig aufzuziehen.
  - Tests: `MiniPlayerTest`.

---

### Task 12: Zukünftige Ökosystem-Erweiterungen (Vorbereitung Phase 7)
- [ ] **12.1 Schneller Profilwechsel (Multi-Account auf Familien-Tablets):**
  - Schnelles Umschalten zwischen hinterlegten ABS-Konten im Profilmenü ohne vollständiges Abmelden und Cache-Löschen.
  - `AccountStore` & `AccountDataJanitor`: Vorbereitung der Multi-Account-Session-Verwaltung.
  - Tests: `AccountStoreTest`.
- [ ] **12.2 Google Cast / Chromecast (Smart-Speaker Integration):**
  - Architektur-Vorbereitung für Google Cast via Media3 `CastPlayer`.
  - Abstraction in `PlayerController` für nahtloses Umschalten zwischen lokaler ExoPlayer-Wiedergabe und Cast-Session.
  - Tests: `PlayerControllerCastTest`.
- [ ] **12.3 Kapitelsuche & Filterung im Sheet:**
  - `SearchTextField` im oberen Bereich des Kapitel-Bottom-Sheets zum schnellen Filtern langer Kapitel-/Tracklisten bei umfangreichen Hörbüchern.
  - Tests: `ChapterListTest`.

---

## 3. Reihenfolge & Vorgehensweise

1. **Zweig anlegen:** `git checkout -b phase-6 main`.
2. **Commit pro Task** nach strengem TDD-Muster (Test schreiben -> Implementieren -> Verifizieren).
3. **Abschluss:**
   - `./gradlew testDebugUnitTest :abs-api:test lintDebug assembleDebug -q`
   - Überprüfung auf Emulator/Gerät.
   - PR erstellen und mergen.

---

## Umsetzung Task 1 (2026-11-05)

Paket „Player & Klang“ (Branch `p6-player`), zusammen mit Task 3, 8, 11.1 und 11.6.

- **1.1** `PlaybackSettings`: `seek_back_sec` (5/10/**15**/30) und `seek_forward_sec` (15/**30**/45/60); Werte außerhalb der
  Auswahl werden beim Schreiben ignoriert und fallen beim Lesen auf den Standard zurück. `PlaybackSettings` nimmt jetzt wie
  `UiSettings` einen `DataStore` (testbar).
- **1.2** **Claudes Wahl:** ExoPlayer kennt die Sprungweiten nur beim Bauen (`ExoPlayer.Builder`), es gibt kein
  `exo.setSeekBackIncrementMs` zur Laufzeit. Deshalb trägt der Session-Player `CarSafePlayer` die Einstellungen
  (`setSteps`, meldet sie als `seekBack/ForwardIncrement`); er bedient ohnehin jedes Zurück/Vor von Benachrichtigung,
  Kopfhörer, Lenkrad und Android Auto. ExoPlayer behält die Standardwerte. `PlayerController.seekBy(delta)` bleibt, der
  Player übergibt ±`seekBackSec`/`seekForwardSec` aus `PlayerUiState`. Auto/Benachrichtigung: Media3-Symbole mit Zahl für
  5/10/15/30 s, für 45/60 s der Pfeil ohne Zahl; Beschriftung „15 Sekunden zurück“.
- **1.3** Einstellungen → Wiedergabe: „Zurückspringen“ und „Vorspringen“ ganz oben. **Claudes Wahl:** Die Player-Tasten
  zeichnen für jeden Wert dasselbe Symbol (runder Replay-Pfeil, für vor gespiegelt, Zahl darin), weil Material nur 5/10/30
  liefert. Das **Widget** zeigt keine Zahl mehr („Zurückspringen“/„Vorspringen“, Pfeil ohne Ziffern): es springt über den
  Session-Player um die eingestellten Sekunden und würde sonst „30“ lügen.
- **Variante B:** drei gleiche Kacheln `Tempo` / `Sleep` / `Klang` (EN Speed / Sleep / Sound), je Icon + Name + Wert. Sleep:
  „Aus“ oder die Restzeit, aktiv in `primaryContainer` (warmes Bernstein in Lauschigs eigener Palette; mit
  Hintergrundbild-Farben folgt es dem Hintergrundbild). **Claudes Wahl** Klang-Wert: Boost „+3 dB“ vor „Nacht“ (Kompressor)
  vor „Ohne Stille“ vor „Aus“; TalkBack hört alles („Klang: +3 dB, Sprachfokus & Nacht-Kompression“). Darunter das Banner
  „Kapitel & Wiedergabeliste“ mit „Aktuell: Kap. 3 / 12“ (ohne Kapitel: „Warteschlange, Lesezeichen & Verlauf“).
- **Claudes Wahl Verlauf:** vierter Tab „Verlauf“ im selben Blatt (Kapitel, Warteschlange, Lesezeichen, Verlauf; scrollbare
  Tab-Leiste), nicht im Überlaufmenü – das Menü gehört zum Fortschritt des Titels (beendet, zurücksetzen), dort wäre der
  Verlauf versteckt. Das Blatt öffnet auf Kapitel, bei Titeln ohne Kapitel auf Warteschlange, hat eine feste Höhe (85 %), damit
  es beim Tab-Wechsel nicht springt; ein Sprung, ein Kapitel oder ein Start aus der Warteschlange schließt es.
- Version 0.4.0 / versionCode 4 mit `whatsnew_0_4_0` – trugen „Darstellung“ und dieses Paket parallel ein; beim Zusammenführen ein Release, beide Zeilenblöcke.

## Umsetzung Task 3 (2026-11-05)

- **Claudes Wahl Zeitpunkt:** der Ton kommt **30 s vor dem Ende**, nicht „zu Beginn des Fade-Outs“ – der Fade dauert nur
  10 s. 30 s lassen Zeit zum Schütteln (Schüttelfenster: letzte 60 s). Einmal pro Unterschreiten; nach einer Verlängerung
  (Schütteln, Sprung zurück) wieder; kein Ton, wenn der Timer mit weniger als 30 s startet (wer ihn gerade stellt, ist wach)
  oder wenn die Grenze im Pausenzustand unterschritten wird.
- **Claudes Wahl Ton:** statt `ToneGenerator` (harte Pieptöne) ein synthetisierter, leiser Zwei-Ton-Gong (G5 → E5,
  glockenartiges Abklingen, 1 s, Spitze 20 % Vollaussteuerung, weicher Einsatz, ausgeblendetes Ende – kein Klicken). Er
  läuft als Media-`AudioTrack` ohne Audio-Fokus: folgt der Medienlautstärke und dem Ausgabegerät (Lautsprecher, Kopfhörer,
  Auto), das Buch läuft darunter weiter.
- Einstellung `sleep_chime_enabled` (Standard an) im Sleep-Blatt und unter Einstellungen → Wiedergabe („Hinweiston vor dem
  Ende“).

## Umsetzung Task 8 (2026-11-05)

- `AudioCompressor` (wie `AudioBoost` am Audio-Session des Players): `DynamicsProcessing` nur solange die Einstellung an ist
  (aus = freigegeben, keine Rechenlast), neue Session = Effekt zieht um, Gerät ohne Effekt = nur Log.
- **Claudes Wahl Werte:** ein Band über das ganze Spektrum, Schwelle −24 dB, 3:1, Knie 8 dB, Attack 15 ms, Release 200 ms,
  +6 dB Make-up für leise Stimmen, Limiter bei −3 dB (10:1, 1/50 ms). Variante `VARIANT_FAVOR_FREQUENCY_RESOLUTION`: der
  Emulator lehnt die Zeitauflösungs-Variante ab („bad parameter value“).
- **Claudes Wahl ohne Klicken:** das Umschalten bei laufender (oder puffernder) Wiedergabe geht über `QuietSwitch` – 60 ms
  Ausblenden, Effekt in der Stille einsetzen/entfernen, 40 ms setzen lassen, wieder einblenden. Pausiert wird direkt
  geschaltet. **Fix-Runde 1:** die Lautstärke hat genau einen Besitzer, `PlayerVolume` = gewünschte Lautstärke der
  Controller (das Ausblenden des Sleep-Timers, über `CarSafePlayer.setVolume`) × eigener Faktor des Umschaltens. Der
  Kompressor-Wechsel blendet nur seinen Faktor, schreibt nie eine alte absolute Lautstärke zurück – ein Wechsel während des
  Sleep-Fades (oder ein Fade während des Wechsels) endet immer bei der Lautstärke des Timers.
- Ein Effekt, der sich nicht einschalten lässt, wird freigegeben (`AudioCompressor` hält ihn vor dem Einschalten,
  `NightCompressor.create` gibt die `DynamicsProcessing` bei einem Fehler frei).
- Global (wie „Stille überspringen“), Schalter im Klang-Blatt „Sprachfokus & Nacht-Kompression“; die Klang-Kachel zeigt
  „Nacht“.

## Umsetzung Task 11.1 (2026-11-05)

- Links die abgelaufene Kapitelzeit (passt zum Kapitel-Schieberegler), rechts die Restzeit – „Kapitel −14:20“ oder
  „Buch −3:18:42“. **Claudes Wahl:** ein kleines Wort „Kapitel“/„Buch“ vor der Restzeit, damit klar ist, welche gezeigt wird
  (der Platz der alten Mittelzeile ist frei); Buchzeit, nicht durch das Tempo geteilt (wie der Schieberegler). Beide Werte
  folgen dem Daumen beim Ziehen. Fingertipp schaltet um, gespeichert in `UiSettings` (`player_remaining_time`, Standard
  Kapitel). TalkBack: „Kapitel: noch 14 Minuten“, Aktion „Restzeit des Buchs zeigen“.
- **Fix-Runde 1:** ohne Kapitel (keins, oder eins über das ganze Buch, z. B. ein Hörspiel in einer Datei) zeigt rechts die
  Restzeit des Buchs ohne Wort und ohne Umschalten (kein Klick, TalkBack „Noch 51 Minuten“). Die Restzeit wird auf die
  Sekunde aufgerundet, die abgelaufene Zeit abgerundet – beide ergeben zusammen die Länge (00:25 + −01:15 = 01:40). Nach dem
  Loslassen des Reglers zeigen beide Zeiten das Ziel, bis der Sprung angekommen ist. **Claudes Wahl bleibt:** Buchzeit,
  nicht durch das Tempo geteilt – wie überall in der App (Fortschritt, „noch x min“, Verlauf).

## Umsetzung Task 11.6 (2026-11-05)

- Der ganze Mini-Player nimmt ein senkrechtes Ziehen an; **Claudes Wahl:** entschieden beim Loslassen – mindestens 40 dp nach
  oben oder ein Wisch mit 600 dp/s öffnet den Player über dieselbe Navigation wie ein Tipp, also mit derselben Slide-Animation
  (kein Mitziehen, nichts konkurriert mit der Slide). Tipp bleibt Tipp (Titel öffnet, Play-Taste schaltet), nach unten oder
  ein langsames Schieben tut nichts.

## Umsetzung Task 7 (2026-11-05)

Paket „Darstellung“ (Branch `p6-look`).

- `DarkThemeTone` (`NIGHT_BLUE` Standard, `WARM_AMBER`) in `UiSettings`/`Appearance`, DataStore-Key `dark_tone`
  (unbekannte Werte → Nachtblau). `LauschigWarmDarkColors` mit den Planfarben und **allen** M3-Rollen (Sand als
  Sekundär-, Salbei als Tertiärfarbe, Container stufen sich in eine Richtung). `colorSchemeFor(…, tone, …)`.
- **Claudes Wahl – Zusammenspiel:**
  - *Dynamische Farben:* Kaminfeuer ist eine bewusste Wahl für die Nacht und gewinnt im dunklen Design auch gegen die
    Hintergrundbild-Farben (die sind oft kühl und würden den Zweck aufheben). Mit `NIGHT_BLUE` bleibt alles wie bisher
    (dynamisch, sonst Nachtblau). Darum heißt das erste Segment „Dynamisch“, solange dynamische Farben aktiv sind,
    sonst „Nachtblau“; der Hinweis sagt „auch statt der Farben des Hintergrundbilds“.
  - *Reines Schwarz:* wirkt auch auf Kaminfeuer (`withPureBlack()`): schwarzer Grund, die Container behalten ihren
    warmen Stich, nur dunkler; Text- und Akzentfarben bleiben.
  - *Helles Design:* unberührt; die Zeile „Dunkler Farbton“ ist dort wie „Reines Schwarz“ deaktiviert („Nur im
    dunklen Design“). Bei „Wie das System“ bleibt sie aktiv (nachts wird es dunkel).
- UI: Zeile „Dunkler Farbton“ mit zwei Segmenten direkt unter „Design“; die Vorschau-Karte zeigt die Wirkung sofort.
- Kontrasttest (`ColorSchemeTest`): Textrollen ≥ 4,5:1 auf Fläche und allen Containern (auch Kaminfeuer + reines
  Schwarz), On-Farben ≥ 4,5:1, Steuerelemente (`outline`, `primary`) ≥ 3:1 – für alle drei eigenen Paletten;
  `BarColorTest` prüft die Statistikbalken auch in Kaminfeuer.
- Version 0.4.0 / versionCode 4 mit `whatsnew_0_4_0` angelegt (dieses Paket fand versionCode 3 vor).

## Umsetzung Task 11.2–11.5 (2026-11-05)

Paket „Darstellung“ (Branch `p6-look`); 11.1 und 11.6 gehören zu anderen Paketen.

- **11.2 – Claudes Wahl: `ArrowCircleDown`.** `OfflinePin` ist ein Haken im Kreis und sähe dem „gehört“-Haken
  (`CheckCircle`) direkt daneben in Episoden- und Sammlungslisten zum Verwechseln ähnlich – genau das Problem, das der
  Plan lösen will. Ein gemeinsames `OfflineIcon` (ui/common) wird überall genutzt: Cover-Badge, `DownloadIconButton`
  (Detail-Aktionen, Player, Podcast-Episoden), Sammlungs-Episodenzeilen, `SourceIndicator` (nur die Icon-Zuordnung,
  als `sourceIcon()`), Download-Begleitknopf der Detailseite. `cover_offline_available` heißt jetzt „Auf diesem Gerät
  verfügbar“ / „Available on this device“. Ein Wächtertest hält `DownloadDone` aus den Quellen.
- **11.3** Schnellleiste unter Titel/Untertitel: Länge (⏱), Sprecher (🎙), Erscheinungsjahr (📅), jeweils mit
  gesprochenem Label („Länge: …“, „Gelesen von …“, „Erschienen: …“); Unbekanntes fällt weg, ohne Daten keine Leiste.
  **Claudes Wahl:** „Ungekürzt“ entfällt – die App speichert dieses Feld nicht, und eine neue Spalte hieße eine
  Migration parallel zu Task 9 (v10→v11). Der Sprecher wandert aus den Chips in die Leiste (keine Doppelung) und öffnet
  weiter seine Seite; mehrere Sprecher sind *ein* Eintrag, ein kleines Menü wählt, wessen Seite. Knöpfe: großer
  Hauptknopf (56 dp, ▶ + Text, Primärfarbe) und daneben ein tonaler 56-dp-Download-Kreis, der den Zustand zeigt
  (Start; Fortschrittsring – Tippen pausiert; Fortsetzen; „Auf diesem Gerät“ – Tippen fragt nach Löschen; Fehler –
  Tippen wiederholt). Darunter steht nur, wenn es etwas zu sagen gibt, der Download-Zustand in Worten mit den feineren
  Aktionen (Pausieren, Abbrechen, Download löschen, Entfernen). Gerätetitel: kein Download-Knopf.
- **11.4** Das Play-Overlay auf `CoverTile` ist weg; Tippen öffnet überall die Detailseite (wie der Brief sagt, auch
  in „Weiterhören“ – der Plan erwähnte alternativ den Player), langes Drücken das Menü (mit „Abspielen“). Die große
  Weiterhören-Karte der Startseite behält ihren Play/Pause-Knopf. **Claudes Wahl:** `onPlay` bleibt als
  Screenreader-Aktion „<Titel> abspielen“ an der Kachel (TalkBack-Menü „Aktionen“), wo der Titel abspielbar ist –
  ohne sichtbaren Knopf, ohne Fehlstarts beim Scrollen.
- **11.5** `BarChart(tips = …)`: Tippen auf einen Balken (ganzer Spaltenbereich – dünne Monatsbalken bleiben
  treffbar) hebt seine Spalte hervor und zeigt einen Tooltip (Zeit fett, darunter das volle Datum: Wochentag + Tag,
  Monat + Jahr, Jahr; „(heute)“ beim aktuellen). Er blendet ein (Alpha + leichte Skalierung), gleitet zum nächsten
  Balken, verschwindet beim zweiten Tippen oder Periodenwechsel. **Claudes Wahl:** Der Tooltip sitzt in einem
  freigehaltenen Band über den Balken (wächst mit der Schriftgröße), damit er nie einen Balken verdeckt; die Lücke
  über dem Diagramm ersetzt dafür den bisherigen oberen Abstand. Exakte Zeit im App-üblichen Format („5 Std. 12 Min.“).
  Barrierefreiheit: zuerst die Zusammenfassung (wie bisher), dann ist jeder Balken ein auswählbarer Knoten
  „Datum: Zeit“ – Erkunden per Berührung liest den Balken unter dem Finger; der Tooltip selbst ist für Screenreader
  verborgen (keine Doppelung). Die Zusammenfassung hängt am Beschriftungsstreifen, weil Compose überdeckte Knoten (die
  Zeichnung unter den Balken-Knoten) vor Screenreadern verbirgt. Start-Karte und Jahresrückblick bleiben ein Knoten.

### Nachtrag Task 11.3/11.5 – Fix-Runde 1 nach Review (2026-11-05)

- **11.3 Download-Kreis:** Jeder Zustand nennt TalkBack, was ein Tippen tut („Herunterladen“, „Download pausieren“,
  „Download fortsetzen“, „Download abbrechen“, „Download löschen“, „Erneut versuchen“); die Beschreibung nennt den
  Zustand („Pausiert“ statt „Fortsetzen“). Ein *wartender* Download wird nicht mehr mit einem Tippen abgebrochen,
  sondern fragt („Download abbrechen?“ – „Abbrechen“ / „Weiter warten“); Pausieren bleibt ein Tippen (harmlos,
  umkehrbar). „Heruntergeladen (9,9 MB)“ bricht nie mehr um (geschütztes Leerzeichen vor der Klammer; der
  Löschen-Knopf rückt bei Platzmangel unter den Text).
- **Detailseite:** Die Überschrift „Kapitel“ erscheint nur noch mit Kapiteln oder mit dem Offline-Hinweis – nicht
  über einer leeren Liste (Titel aus einer Datei, z. B. viele Hörspiele; war schon auf main so).
- **11.5 Balken:** Die Fläche des Diagramms nimmt die Berührungen selbst: Tippen irgendwo wählt den Balken dieser
  Spalte (dünne Monatsbalken sind keine Glückssache mehr), waagerechtes Wischen fährt die Balken ab und der Tooltip
  folgt dem Finger; erneutes Tippen auf den gewählten Balken, auf den Tooltip oder sein Band hebt die Auswahl auf, eine
  neue Periode auch. Für Screenreader sind die Balken jetzt Tabs einer Gruppe (eigene Klick-Aktion wählt aus). Die
  Spalten-Markierung ist der Akzent mit 16 % statt onSurface mit 8 % – sichtbar auf leeren Tagen und reinem Schwarz.
- **Fix-Runde 2:** Das Diagramm übernimmt einen Wisch nur, wenn er flacher als steil ist (sonst scrollt die Seite),
  und das Abfahren beginnt beim berührten Balken. Die Frage „Download abbrechen?“ gilt nur, solange der Download
  wartet: läuft, endet oder verschwindet er, schließt sie sich, und ein spätes Bestätigen bricht nichts mehr ab.

## Umsetzung Task 4 (2026-11-05)

Paket „Downloads & Support“ (Branch `p6-downloads`). Keine Schemaänderung (DB bleibt v10; v11 nicht belegt).

- `StorageVolumes` liest `ContextCompat.getExternalFilesDirs`: interner Speicher immer, dazu jedes eingehängte andere
  Volume (SD-Karte = entfernbar, sonst „Externer Speicher“; Name vom System, z. B. „SanDisk SD-Karte“). Der primäre
  „externe“ Speicher ist emuliert (derselbe Flash) und wird übersprungen. Nur-lesbare Karten werden gelistet
  (Abspielen geht), aber nicht als Ziel angeboten. Die Liste ist 10 s gecacht.
- `DownloadSettings.storagePath` speichert das App-Verzeichnis des Volumes (null = intern). `VolumeDownloadRoots`
  macht daraus das Ziel – nur solange die Karte eingehängt und beschreibbar ist, sonst intern. Jede externe Wurzel
  bekommt eine `.nomedia` (Musik-Apps unter Android 9/10 sollen die Hörbücher nicht listen).
- `DownloadFiles` kennt mehrere Wurzeln: Ein Titel bleibt dort, wo sein Ordner liegt; nur ein Titel ohne Ordner geht
  an das Ziel. Liegt ein Titel auf zwei Volumes, gewinnt der interne Ordner. Größe beim Abmelden, Purge, Janitor,
  `deleteItem` und `cleanupOrphans` decken jedes eingehängte Volume ab. Der Worker wartet beim Kaltstart auf die
  gelesene Einstellung (`awaitLocation`).
- **Claudes Wahl – bestehende Downloads beim Wechsel:** Sie bleiben, wo sie sind (kein Verschieben); die
  `localPath`-Einträge sind absolut, also spielen und löschen sie sich weiter wie bisher. Der Dialog sagt das.
  Verschieben (GBs kopieren mit Fortschritt, Abbruch, Platzprüfung) wäre ein eigenes Feature.
- **Claudes Wahl – Karte fehlt:** Statt Toast ein bleibender Hinweis in den Einstellungen („SD-Karte nicht gefunden –
  neue Downloads landen vorerst im internen Speicher“) und auf der Download-Seite; die Wahl bleibt bestehen, mit der
  Karte gehen neue Downloads wieder dorthin. Ein Toast aus einem Hintergrund-Worker würde übersehen.
- **Claudes Wahl – Janitor:** Er räumt jetzt auch innerhalb der Konten auf (angemeldetes und behaltene Konten, nie
  ein offener Purge): Titelordner ohne Download-Zeile (gelöscht, während die Karte draußen war) und alte Kopien auf
  einem zweiten Volume bei fertigen Downloads (`DownloadTidy`). Laufende/pausierte Downloads werden nie angefasst.
  Was beim Abmelden auf einer fehlenden Karte liegt, löscht der Janitor beim ersten Start mit eingelegter Karte.
- UI: Zeile „Speicherort“ unter „Downloads verwalten“ (Ort + freier Platz); Antippen öffnet die Auswahl, wenn es
  mehr als ein Volume gibt oder die gewählte Karte fehlt. Der Hinweis nennt auch, dass Dateien auf einer SD-Karte in
  einem anderen Gerät lesbar sind.
- Grenze: Ein fertiger Download auf einer gerade fehlenden Karte zählt weiter als „heruntergeladen“; abgespielt wird
  er dann gestreamt (online), löschen geht.

## Umsetzung Task 5 (2026-11-05)

- **Claudes Wahl – Allowlist statt Logcat:** Kein Logcat-Mitschnitt. Jeder Log-Aufruf der App geht über
  `diagnostics.Log` (gleiche Aufrufe wie `android.util.Log`, Logcat unverändert, `d` bleibt draußen); Bibliotheks-Logs
  (ExoPlayer, OkHttp, WorkManager) kommen nie hinein. Ein Eintrag besteht aus festen Feldern: Zeit, Level, Tag,
  maskierte Nachricht und bei Fehlern die Exception-Kette (Klassennamen, maskierte Meldungen, oberste Stackframes –
  nur Code-Namen). Kopf der geteilten Datei: App-/Android-Version, Gerätemodell, online (Heimnetz/Internet)/offline,
  Download-Ziel – kein Server, kein Benutzer, kein WLAN-Name.
- `LogMasker` für Freitext: Authorization-/Cookie-/API-Key-Header, Bearer/Basic, JWTs, geheime Schlüssel=Wert-Paare
  und JSON-Felder (token, password, username, email …), URLs (nur Schema und ABS-API-Pfadwörter bleiben, Abfrage immer
  weg – auch Share-Slugs fallen), E-Mail, IPv4/IPv6, Hostnamen (Code- und Dateinamen ausgenommen), lange Schlüssel und
  die eigenen Werte des Nutzers (`DiagnosticSecrets`: Server-Hosts und Benutzernamen des angemeldeten und behaltener
  Konten, Heim-WLAN-Namen, ReadMeABook-Host und -Benutzer). Getestet mit realistischen Zeilen.
- Ringpuffer 500 Einträge, schon maskiert gespeichert; **Claudes Wahl:** Er überlebt den Prozess (app-privat in
  `no_backup/diagnostic-log.txt`, 2 s nach einem neuen Eintrag und sofort beim Absturz geschrieben, beim Start
  zurückgelesen mit „--- app started ---“) – sonst wäre das Problem von heute Morgen am Abend weg. Bleibt beim
  Abmelden (enthält nur Maskiertes; Probleme beim Abmelden sollen sichtbar bleiben).
- Teilen: Einstellungen → Über → „Diagnose-Log teilen“ zeigt erst, was drin ist und was nie (mit Vorschau der neuesten
  40 Einträge), dann `cache/diagnostics/lauschig-diagnose.txt` per `FileProvider` (nur dieses Verzeichnis) und
  `ACTION_SEND text/plain` mit Lese-Grant; Lauschig selbst ist als Ziel ausgeschlossen (es nimmt geteilten Text an).
- Die Playback-Dateien haben nur ihre Log-Import-Zeile geändert.

## Umsetzung Task 6 (2026-11-05)

- `AutoDeleteMode` (Nie Standard / Sofort / Nach 3 Tagen / Nach 7 Tagen) in `DownloadSettings`; Zeile „Gehörte
  Downloads automatisch löschen“ bei den Download-Einstellungen.
- `FinishedDownloadCleaner` löscht über `DownloadManager.cancel` (Dateien + Download-Zeile); Fortschritt, Sitzungen,
  Hörverlauf und Lesezeichen bleiben (Test zählt alle Tabellen). In der App beobachtet er die fertigen Downloads und
  handelt sofort bzw. wenn der nächste fällig wird; ein täglicher Worker deckt die Zeit ohne App ab. Folgen auch
  Podcast-Episoden.
- Maßstab ist `progress.lastUpdate`. **Claudes Wahl:** Ein Download zählt nur, wenn der Titel *nach* dem Hinzufügen
  des Downloads beendet wurde – wer einen gehörten Titel zum Wiederhören lädt, verliert ihn nicht sofort wieder; er
  geht erst, wenn er erneut beendet wird.
- Nie gelöscht: Gerätetitel, der gerade spielende Titel (danach), Titel, die man beim Aufräumen behält.
  **Claudes Wahl:** „Nicht mehr erinnern“ heißt jetzt „Behalten: keine Erinnerung, kein automatisches Löschen“ – für
  Kinder-Lieblingshörspiele, die immer wieder laufen.
- **Claudes Wahl:** Solange automatisch gelöscht wird, ruht die wöchentliche Aufräum-Erinnerung (Schalter
  deaktiviert, mit Begründung). Bei „Sofort“ entfällt die Frage „Download löschen?“ nach „Als gehört markieren“ (der
  Download ist gleich weg); bei 3/7 Tagen bleibt sie (jetzt löschen schafft jetzt Platz).

### Nachtrag Task 4–6 – Fix-Runde 1 nach Review (2026-11-05)

Keine Schemaänderung (weiter v10 auf diesem Zweig; v12 nicht belegt).

- **Task 6 – was der Player hält (D1):** Nie gelöscht wird der im Player geladene Titel (egal ob er spielt, pausiert,
  vom Sleep-Timer gestoppt oder vom Anruf unterbrochen ist), die Titel der Warteschlange und ein Titel, dessen
  „Download löschen?“-Frage offen ist. **Claudes Wahl:** Kennt der Controller der App keinen Titel (Auto ohne
  verbundene App, ein vom Tages-Worker gestarteter Prozess), gilt der zuletzt gespielte Titel (`LastPlayedStore`) als
  gehalten. Geprüft wird neu, wenn sich der geladene Titel oder die Warteschlange ändert, und eine Minute später (der
  zuletzt gespielte Titel meldet keine Änderung). Der Controller setzt seinen Titel erst, nachdem er sein `current`
  gewechselt und die Download-Beobachtung des alten Titels beendet hat – danach kann Löschen keinen Quellwechsel des
  alten Titels mehr auslösen (Rennen beim Warteschlangen-Wechsel). Emulator: „Das Ende“ zu Ende gespielt, „Die Mitte“ in
  der Warteschlange → „Die Mitte“ spielt weiter, „Das Ende“ wird 40 ms nach der Übernahme gelöscht, Fortschritt bleibt
  „gehört“.
- **„Gehört“ heißt zu Ende oder markiert (D1):** Die 99,5-%-Marke des Trackers zählt nicht (Position mehr als 2 s vor
  dem Ende und ≥ 99,5 %); eine Pause kurz vor dem Ende löscht nichts.
- **D5 – nur Gerätezeiten:** Ein Download zählt, wenn er fertig war (Änderungszeit seiner Dateien), bevor das Gerät
  den Titel zum ersten Mal als gehört sah (`FinishedSeenStore`, DataStore, wird auch bei „Nie“ geführt). **Claudes
  Wahl:** Damit ist weder `requestedAt` (bleibt beim Fortsetzen alt) noch die Serverzeit im Spiel. Beim ersten Start
  dieser Version gelten alle schon gehörten Titel als „jetzt gesehen“ – wer davor einen gehörten Titel neu geladen hat,
  bekommt ihn in der Einschalt-Frage mitgezählt. Die 3/7 Tage zählen ab diesem Gerätezeitpunkt.
- **D3:** Einschalten (von „Nie“ auf eine Stufe) fragt, wenn es gehörte Downloads träfe: „N gehörte Downloads (X)
  werden sofort gelöscht“ bzw. „… jeweils N Tage nachdem sie gehört wurden“, mit Hinweis auf „Behalten“; Abbrechen
  lässt die Einstellung. Zwischen zwei Stufen wird nicht erneut gefragt.
- **D4:** Die Frage nach „Als gehört markieren“ kommt wieder, auch bei „Sofort“; bis zur Antwort wartet das
  Aufräumen. „Löschen“ löscht sofort, „Behalten“ (auch Wegtippen) setzt den Titel bei eingeschaltetem Aufräumen auf die
  Behalten-Liste.
- **D14:** Ein Löschfehler wird pro Titel abgefangen und protokolliert; der Beobachter läuft weiter.
- **D15:** Die Aufräumen-Seite listet auch gehörte Podcast-Episoden; „Behalten“ schützt sie wie Bücher.
- **Task 4 – V17:** Jeder Lesefehler der Speicherort-Einstellung führt zu „intern“; kein Download wartet ewig.
- **V18:** Abmelden mit fehlender Karte: Der Dialog sagt, dass die Downloads auf der Karte gelöscht werden, sobald sie
  wieder steckt (bzw. beim Behalten, dass sie bleiben); nur auf der Karte liegende Downloads lassen das Behalten zu.
  Der Purge merkt sich die Download-Wurzel der Karte (aus den Pfaden in Room, `VolumePurges`), gilt als fehlgeschlagen,
  bis die Karte wieder da und das Verzeichnis weg ist – der Purge bleibt offen, der Janitor erledigt ihn.
- **16 – Karte aus einem anderen Gerät:** akzeptiert. Deren Lauschig-Downloads gehören zu keinem Download dieser
  Installation (keine Zeilen) und werden beim Aufräumen gelöscht; Übernehmen wäre ein eigenes Feature.
- **Task 5 – Maskierung (L6–L10):** Wildcard- und Punkt-Hosts (`*.example.io`), die übergeordnete Domain eigener Hosts
  als eigener Wert, Ein-Wort-Hosts in Javas „host "nas"“/„Hostname … not verified“; Schlüssel per Namensteil
  (newPassword, authToken, …ClientSecret, apiToken, x-refresh-token, connect.sid …) in einfachen und doppelten
  Anführungszeichen, Werte mit mehreren Wörtern bis zum Trenner (Autorennamen bleiben); Pfade in geteiltem Speicher und
  auf Karten behalten nur Volume und Endung (das App-Verzeichnis dort bleibt lesbar), ebenso Dokument-IDs
  (`primary:…`); IPv4-gemappte IPv6 ganz; `user:pass@host` ohne Schema. Erst maskieren, dann kürzen.
- **L9:** Einträge vor dem Bekanntwerden der eigenen Werte werden dann erneut maskiert und beim Teilen ein weiteres Mal.
- **L11–L13:** Der Log-Aufruf wirft nie in die App; ein Schreiber für die Datei; die geteilte Kopie wird beim nächsten
  Start, vor einem neuen Teilen und beim Abmelden gelöscht und ihr Lese-Grant widerrufen; **Claudes Wahl geändert:**
  Das Diagnose-Log selbst wird bei jedem Abmelden und Kontowechsel geleert (Familiengeräte).

### Nachtrag Task 4–6 – Fix-Runde 2 nach Re-Review (2026-11-05)

Leitregel „im Zweifel behalten“. Keine Schemaänderung (v10 auf diesem Zweig; v12 nicht belegt).

- **N1 Übergabe:** Jeder Startweg merkt seinen Titel vorher an (`TitleHandover`): der Warteschlangen-Wechsel und
  „Aus der Warteschlange abspielen“ *bevor* der Titel die Warteschlange verlässt, der Player der App und der Starter des
  Dienstes (Auto, Widget, Fortsetzen) bevor sie laden; der Dienst meldet, was sein Player hält. Ein gemerkter Titel ist
  60 s geschützt; jeder Titel, der den Halt des Players verlässt (Warteschlange, geladen, Dienst, gemerkt), behält ihn
  weitere 60 s – egal in welcher Reihenfolge Player, Warteschlange und Aufräumen den Wechsel sehen. **N9** ist damit
  erledigt (der alte Titel bleibt bei einem Start, der scheitert, geschützt). Emulator: „Das Ende“ (gehört, geladen,
  zählend) als Nächstes in der Warteschlange, „Der Anfang“ zu Ende gespielt → „Das Ende“ startet aus seinen Dateien.
- **N2 Konten:** Die „zuerst als gehört gesehen“-Zeiten bleiben je Server; ein Server, den das Gerät zum ersten Mal
  sieht, bekommt eine stille Grundlinie – was dann schon gehört war, löscht das automatische Aufräumen nie (das
  Aufräumen-Menü bietet es weiter an). Ein Purge vergisst die Zeiten. Die Einschalt-Frage (D3) bleibt.
- **N3/N4 offene Frage:** „Download löschen?“ wird aus einem frischen Lesen entschieden (nicht aus dem ersten Wert des
  Menüs), *vor* dem Markieren auf der Platte vermerkt und oben in der App gezeigt (übersteht Drehen; Emulator geprüft).
  Bis zur Antwort bleibt der Titel gehalten; eine Frage, die ein beendeter Prozess offen ließ, zählt beim nächsten
  Start als „Behalten“. Lässt sich der Behalten-Eintrag nicht schreiben, bleibt der Titel gehalten; lässt sich der
  Vermerk nicht lesen, löscht das Aufräumen gar nicht.
- **N5:** Ein Fehler in einem Durchgang wird protokolliert, der nächste läuft; ein nicht lesbarer Speicher wird eine
  Minute später erneut gelesen; der Tages-Worker meldet „retry“.
- **N6–N8 (Log):** Eine eigene Domain nimmt ihre Subdomains mit (`abs.example.io` → `<private>`); nach einem Pfad im
  App-Verzeichnis wird der Rest der Zeile weiter maskiert; Felder eines Zertifikat-DN (CN, O, OU, L, ST, C, E …) gehen.
- **N10:** Liegt auf der fehlenden Karte nur ein begonnener Download (`.part`), wartet der Purge auch auf sie und
  löscht dann den ganzen Ordner des Kontos dort.

### Nachtrag Task 5/6 – Fix-Runde 3 nach Re-Review 2 (2026-11-05)

Keine Schemaänderung (DataStore).

- **R1 – Einstellung je Konto:** „Gehörte Downloads automatisch löschen“ gilt je Konto (Standard „Nie“ für jedes,
  auch später hinzukommende). Die Einstellungszeile, die Einschalt-Frage, der Beobachter, der Tages-Worker, die
  Aufräum-Erinnerung und die Bedeutung von „Behalten“ richten sich nach dem angemeldeten Konto. Der alte geräteweite
  Wert gehört dem Konto, das beim Update angemeldet ist; ohne Anmeldung entfällt er. Ein „Sofort“ der Eltern löscht
  nichts vom Kind.
- **R2 – kein Abfragen im Sekundentakt:** Durchgänge laufen nur bei Änderungen (gehörte/geladene Downloads, Einstellung,
  Behalten-Liste, Player, Warteschlange, Fragen, Übergabe) plus einem Zeitgeber – der nächsten Fälligkeit oder dem Ende
  eines Picks bzw. einer Gnadenfrist, die einen zählenden Titel hält. Ein abgelaufener Pick hält nicht mehr; Dateizeiten
  von Grundlinien-Titeln werden nicht gelesen. Test auf virtueller Zeit: ein zählender Titel in der Warteschlange, ein
  anderer spielt – 1 Durchgang in 10 Minuten.
- **R3:** Starts aus dem Auto (Stöbern, Suche, Sprache) und das Fortsetzen markieren ihren Titel vor dem Auflösen.
- **R4:** Offene Fragen werden vor dem Start des Aufräumens erledigt; lässt sich eine Frage nicht vermerken, kommt der
  Titel auf die Behalten-Liste – geht auch das nicht, wird er nicht als gehört markiert.
- **R5 (Log):** E-Mail-Adressen unter einer eigenen Domain werden ganz maskiert; per Komma angehängte Pfade; DN-Werte mit
  maskierten Kommas, DNs ohne CN, Attribute in OID-Form.
- **R7:** Ist der Speicher der „gesehen“-Zeiten oder der offenen Fragen beschädigt, beginnt er leer (einmal
  protokolliert) und jedes Konto nimmt eine neue Grundlinie: nichts, was jetzt gehört ist, wird gelöscht; das Aufräumen
  arbeitet weiter.

### Nachtrag Task 5/6 – Fix-Runde 4 nach Re-Review 3 (2026-11-05)

Keine Schemaänderung (DataStore).

- **T2 – Abmelden beendet den Zeitgeber:** Meldet man sich ab (auch mit „Downloads behalten“), endet der Beobachter des
  bisherigen Kontos samt seinem Zeitgeber. Solange niemand angemeldet ist, läuft kein Durchgang, und nichts wird
  gelöscht.
- **T1 – „Abmelden und löschen“ nimmt das Aufräumen mit:** Beim Löschen der Kontodaten (Abmelden mit Löschen,
  Profil entfernen, nachgeholtes Löschen) gehen auch die Einstellung „Gehörte Downloads automatisch löschen“, die
  offenen „Download löschen?“-Fragen und die Behalten-Einträge dieses Kontos. Meldet man sich wieder an, beginnt es wie
  ein neues Konto mit „Nie“.
- **T3 – neue Grundlinie auch nach Neustart:** Ist der Speicher der offenen Fragen beschädigt, steht die Bitte um eine
  neue Grundlinie für alle Konten jetzt auf der Platte. Sie wird erst gelöscht, wenn die neue Grundlinie gespeichert
  ist; ein Neustart vor dem ersten Durchgang verliert sie nicht mehr. Die „gesehen“-Zeiten werden nur noch nacheinander
  abgeglichen.
- **T4 (Log):** Der eigene Pfad der App endet am ersten Zeichen, das keine ID enthält. Damit werden auch Pfade
  maskiert, die mit `;`, `|` oder `:` angehängt sind. Außerdem neu: DN-Werte in Anführungszeichen (mit Komma),
  weitere Attribute (SURNAME, GIVENNAME, `OID.…` u. a.) und DNs ohne CN/O/OU (Kennung `DN:` oder zwei Felder mit
  Komma).
- **Claudes Wahl:** Behalten-Einträge eines gelöschten Kontos gehen mit, denn das Konto beginnt wie neu. Die
  Aufräum-Einstellung wird vor den Datenbankzeilen gelöscht: Bricht das Löschen ab, ist das Aufräumen schon aus.

---

## Umsetzung Task 2 und 9 (2026-11-05)

Paket „Performance & Suche“ (Branch `p6-perf`).

- **2.1/2.2** `Catalog.search(items, facets, query)`; die Variante ohne Facetten delegiert (`facets(items)` + neue Überladung), Ergebnisse identisch (Test über mehrere Queries). `SearchViewModel` hält pro Katalogstand einen `Indexed`-Snapshot (sichtbar / Gerät / gesamt) mit **lazy** berechneten Facetten; jeder Tastenanschlag filtert nur noch. Test: fünf Eingaben → 5 Suchen, **1** Facetten-Berechnung (vorher: je Suche eine, also 5; mit „hidden by filter“-Prüfung bis 2 je Suche); neuer Katalogeintrag → genau eine weitere.
  - **Claudes Wahl:** Die Facetten sind nicht als eigener `catalogFacets`-Flow gebaut (Plan), sondern lazy im Snapshot: so wird die Facetten-Berechnung für den Gesamtkatalog (nur für den „Filter blendet Treffer aus“-Hinweis nötig) nur dann gemacht, wenn die Suche leer ausgeht, und bei „alle Arten gewählt“ (sichtbar = gesamt) wird sie geteilt. Zusätzlich `distinctUntilChanged` auf `observeAll`, damit ein Room-Invalidate ohne echte Änderung nichts neu indiziert.
- **9.1** Es gab bereits Coils Standard-Diskcache (`cacheDir/image_cache`, größenabhängig). Jetzt explizit: `newCoverDiskCache` → `cacheDir/coil_covers`, 150 MB; Abmeldung leert ihn weiter über `SingletonImageLoader.get(context).diskCache?.clear()` (`AccountStateCleaner` unverändert). Test: Loader hat 150-MB-Cache im Cover-Ordner, Eintrag überlebt einen neuen Loader („Neustart“), `clear()` leert ihn. **Claudes Wahl:** das alte `image_cache`-Verzeichnis wird nicht aktiv gelöscht (liegt im Cache-Ordner, Android räumt ihn bei Platzmangel; enthielt nur Cover).
- **9.2** Room **Version 11** (`AutoMigration(10, 11)`, `11.json` eingecheckt; `p6-downloads` hatte zum Zeitpunkt keine Schemaänderung): `progress(serverId, isFinished, lastUpdate)`, `downloads(serverId, state)`, `items(serverId, seriesName, seriesSortKey)`. `MigrationTest.v10ToV11…` (Daten bleiben, Indizes vorhanden), `QueryPlanTest` (EXPLAIN QUERY PLAN, Indexnamen werden geprüft). Wirkung siehe Report: Fortschritts-Listen und Download-Zustandsabfragen nutzen den neuen Index (ohne Temp-B-Tree für `ORDER BY lastUpdate`); `nextInSeries` nutzt den Serien-Index nur bis `seriesName` (`lower(trim(seriesName))` verhindert Gleichheitssuche auf der Spalte, der Index schließt aber Titel ohne Serie aus).
- **9.3** Neu `ui/common/Throttle.kt: throttleUnchangedKey(windowMs, key)`: Werte mit geändertem Schlüssel gehen sofort durch, Werte mit gleichem Schlüssel höchstens einmal je Fenster (der neueste, nichts geht verloren). In `LibraryViewModel` auf die Zeilen (Items, Fortschritt, Downloads) mit Schlüssel = Item-IDs, Download-Mengen und je Fortschrittszeile (Id, `isFinished`, `hideFromContinue`, Position == 0). Fenster 1,5 s. **Claudes Wahl:** Schlüssel-basiertes Throttling statt pauschalem `debounce(1_500)` (der würde bei Ticks unter 1,5 s Abstand nie ausliefern und „als beendet markieren“ verzögern). Messung im VM-Test: 20 Positionsschreibvorgänge → 2 Emissionen (vorher 20); „beendet markieren“ während der Ticks: 1 ms bis zur neuen Liste. Nur `LibraryViewModel` angepasst (wie im Plan); Suche trennt ohnehin Suche und Kachel-Mapping.
- What's new 0.4.0: eine Zeile (DE + EN).

### Nachtrag Task 9 – Fix-Runde 1 nach Review (2026-11-05)

- **Altes Coil-Verzeichnis:** Auf dem Emulator (Build vor der Änderung) hieß Coils Standard-Cache `cache/coil3_disk_cache` (nicht `image_cache`, das ist Coil 2). `deleteLegacyCoverCaches(context)` löscht beide Namen (idempotent, fehlendes Verzeichnis ok, wirft nie): einmal beim App-Start auf `Dispatchers.IO` (`LauschigApp.onCreate`) und im „images“-Teil von `AccountStateCleaner` (nach `diskCache.clear()`), damit Cover früherer Konten nie überleben.
- **Throttle:** Entscheiden und Senden laufen unter einem `Mutex`; ein freigegebener älterer Tick kann einen neueren Wert mit anderem Schlüssel nicht mehr überholen (Test mit virtueller Zeit).
- **Dokumentiert:** Was der Schlüssel nicht abdeckt (Positionstick eines anderen Titels, geänderte Metadaten eines vorhandenen Titels) kann bis zu 1,5 s warten, geht aber nie verloren; Strukturänderungen laufen sofort.
- **Test:** `LibraryViewModel` bekommt wie `SearchViewModel` einen injizierbaren Dispatcher (Hilt-Konstruktor unverändert); der Messtest läuft mit eigenem `TestCoroutineScheduler` (virtuelle Zeit, keine Wanduhr): 20 Schreibvorgänge → 3 Emissionen (vorher 20), „beendet“ ohne vergangene virtuelle Zeit sofort sichtbar.

---

## Umsetzung Task 12 (2026-11-05)

Paket „Kapitelsuche & Ökosystem-Vorbereitung“ (Branch `p6-eco`), dazu ein Navigations-Fix vom Tablet.

- **12.3 Kapitelsuche** im Kapitel-Tab des Inhalte-Blatts (`ContentsSheet`): Suchfeld oben, Treffer in Buchreihenfolge,
  Lösch-Knopf, leerer Zustand („Kein Kapitel zu „…““ mit Hinweis „Such nach einem Wort aus dem Titel oder nach der
  Kapitelnummer.“). `ChapterSearch` (ui/detail): Wortanfang-Suche wie die Katalogsuche (jedes Suchwort muss ein Wort des
  Titels beginnen), ohne Groß/Klein, Akzente und Umlaute – „mädchen“, „madchen“, „maedchen“ finden „Mädchen“, „strasse“
  findet „Straße“; eine Zahl findet auch das Kapitel dieser Nummer („12“ → Kapitel 12, wie es auch heißt), führende
  Nullen zählen nicht („7“ → „Track 007“), gemischte Wörter zerfallen in Teile („cd2“ → cd, 2).
  - **Claudes Wahl Zahlen:** Wortanfang auch für Nummern, wie in der Katalogsuche: bei 300 Kapiteln findet „12“ das
    Kapitel 12 und 120–129 (Kapitel 12 zuerst), „57“ nur 57. Keine Teilwort-Suche („kehr“ findet „Rückkehr“ nicht) – sie
    brächte bei Zahlen „112“ für „12“.
  - **Claudes Wahl Umlaute:** neben dem Entfernen der Akzente (ä → a) auch die ausgeschriebene Form (ä → ae, ß → ss) für
    Titel und Suche. Nur „madchen“ findet einen „Maedchen“ geschriebenen Titel nicht (weder Titel- noch Umlautschreibung).
  - **Claudes Wahl Nummern an den Treffern:** während der Suche steht vor jedem Treffer seine Kapitelnummer
    (Tabellenziffern) – ein Zahlentreffer ist erkennbar, und man sieht, wo er im Buch liegt. Ohne Suche bleibt die Liste
    wie bisher.
  - **Claudes Wahl Schwelle:** das Feld erscheint ab 10 Kapiteln (`CHAPTER_SEARCH_FROM`); kürzere Listen passen auf ein,
    zwei Bildschirme und bleiben ruhig.
  - Die Suche überlebt einen Blick in einen anderen Tab des Blatts (im Blatt gehalten, `rememberSaveable`); ein neues
    Blatt beginnt leer. Eine neue Eingabe springt zum ersten Treffer, Löschen zurück zum aktuellen Kapitel. Ein Treffer
    springt und schließt das Blatt (die Tastatur geht mit); die Tastaturtaste „Suchen“ und das Ziehen der Liste blenden
    die Tastatur aus.
  - Tastatur (Emulator, Gboard): das Blatt bleibt 85 % des Fensters; das Fenster endet über der Tastatur, das Blatt rückt
    dabei ein Stück hoch (≈ 15 % der Tastaturhöhe), die Liste endet an der Tastaturkante – kein Treffer verdeckt
    (gemessen: Liste 650–1517 px, Tastatur ab 1517 px, mit und ohne `imePadding`; es bleibt als Absicherung, ohne doppelte
    Lücke).
  - **Claudes Wahl Detailseite:** dort kein Suchfeld. Die Kapitel stehen am Ende einer langen Seite (Beschreibung,
    Metadaten), ein Feld mitten darin wäre fremd; zum Springen im Buch ist der Player da. `ChapterSearch` ist unabhängig
    und ließe sich dort später nutzen.
  - Tests: `ChapterSearchTest` (6), `ChapterListTest` (5, Compose).
- **12.1 Mehrere angemeldete Konten (Naht für den Profilwechsel):**
  - `AccountStore` hält neben dem aktiven Konto gespeicherte (`saved`, `add`, `activate`, `forget`). Das aktive Konto
    behält seine Schlüssel (`account_json`, `access_token_enc`, `refresh_token_enc`, `needs_reauth`); gespeicherte liegen
    in einem weiteren Schlüssel `saved_accounts_json` (Metadaten, verschlüsselte Tokens, Re-Auth-Flag je Konto), der nur
    existiert, solange es welche gibt. Ein einzelnes Konto wird also genau wie bisher gespeichert – Upgrade-Test mit dem
    0.3.0-Format (Schlüssel und JSON, wie die alte Version sie schrieb) und auf dem Emulator (Update über die installierte
    Version: weiter angemeldet, Serververwaltung lädt mit Token).
  - Tokens nur verschlüsselt: ein Wechsel verschiebt den Chiffretext, entschlüsselt und verschlüsselt nichts (Test zählt
    die Cipher-Aufrufe); `Credentials.toString()` zeigt keine Tokens, nichts wird geloggt.
  - **Claudes Wahl – Tokens gehören ihrem Konto:** `credentials()` liest Server-ID und Tokens in einem Zug;
    `AuthInterceptor` markiert jede Anfrage mit dem Konto, dessen Token sie trägt (`SentBy`; in Fix-Runde 1 ersetzt durch
    Clients je Konto, siehe dort – erst damit ist auch das erste Senden gebunden); `TokenAuthenticator`
    beantwortet einen 401 nach einem Wechsel nie mit dem Token des anderen Kontos, und ein Refresh (oder seine Ablehnung),
    der erst nach dem Wechsel ankommt, landet beim eigenen Konto (`updateTokens(serverId, …)`,
    `markNeedsReauth(serverId)`), nicht beim neuen. Ohne das könnte ein spät ankommender Refresh dem neuen Profil die
    Tokens des alten geben. Für ein einzelnes Konto unverändert; einzige Änderung: ein Refresh, der nach einer Abmeldung
    ankommt, schickt seine Anfrage nicht mehr erneut ab.
  - `ProfileSwitch` (umgesetzt von `SignOut`, in Hilt gebunden, noch ohne Oberfläche): `switchProfile(serverId)`,
    `addProfile(next, tokens)`. Sie verlassen das aktive Konto mit den Schritten der Abmeldung im neuen Modus
    „beiseitelegen“: Wiedergabe und Arbeit stoppen, wartende Downloads pausieren, der zuletzt gespielte Titel und der
    Kontozustand außerhalb Room gehen (RMAB, SSO, letzte Suchen, Download-Frage, Cover-Caches, Aufräum-Benachrichtigung)
    – aber **kein Server-Logout**, Daten und Downloads bleiben, kein „Behalten“-Eintrag (keine Frage an das nächste
    Konto, kein Löschangebot), eine alte Löschschuld verfällt; als letzter Schritt wird das nächste Konto aktiv (nie
    „kein Konto“ dazwischen).
  - `AccountDataJanitor` und `KeptAccountDeletion` behandeln gespeicherte Konten wie angemeldete (nie gelöscht,
    Löschschuld verfällt). Im Janitor genau: `SignedIn` trägt zusätzlich `saved` (aus `accounts.saved`), die beiden
    Bedingungen „gewollt“/„besessen“ um `current.saved` ergänzt, ein Satz im KDoc. `AccountStateCleaner` unverändert.
  - Abmelden, „Behalten“ und `switchTo` (Re-Auth als anderes Konto) unverändert; Abmelden betrifft nur das aktive Konto,
    gespeicherte bleiben.
  - Keine sichtbare Oberfläche (zum Testen nicht nötig: Speicher, Wechselablauf, Janitor, Löschschutz und die Token-
    Rennen sind mit Unit-Tests abgedeckt). Tests: `AccountStoreTest` (10), `SignOutTest` +5, `TokenAuthenticatorTest` +3,
    `AccountDataJanitorTest` +1, `KeptAccountDeletionTest` +1.
- **12.2 Cast-Naht:** `PlaybackOutput` (playback/) trennt `player` – was spielt: die Session (und über sie App-
  `PlayerController`, Benachrichtigung, Sperrbildschirm, Kopfhörer, Auto), Fortschritt, Warteschlange, Kapitel, Widget,
  Auto-Tasten, Play-Tasten-Wächter – von `device`, dem ExoPlayer des Geräts (Effekte, Stille überspringen, Lautstärke des
  Sleep-Fades, Stream-Monitor); `isLocal` aus `Player.deviceInfo`. `DeviceOutput(exo)`: beides derselbe ExoPlayer, das
  Verhalten bleibt gleich. `PlaybackService` hält `output` statt `player: ExoPlayer`. Keine Cast-Abhängigkeit.
  - **Claudes Wahl – die Naht sitzt an der Session, nicht im `PlayerController`:** Media3 ≥ 1.9 hat einen `CastPlayer`
    mit lokalem Player (`CastPlayer.Builder(context).setLocalPlayer(exo)`), der selbst zwischen Gerät und Empfänger
    wechselt und die Warteschlange mitnimmt. Steht er in der Session, folgen App (`PlayerController` über den
    `MediaController`), Benachrichtigung, Sperrbildschirm und Auto ohne Änderung; ein Umschalten im `PlayerController`
    ginge an der Session vorbei. Darum statt `PlayerControllerCastTest` ein `PlaybackOutputTest` (3: Gerät = ExoPlayer und
    lokal; Freigabe; ein Ausgang, dessen Player woanders spielt, ist nicht lokal und behält den Geräte-Player).
  - Emulator: Gerätetitel spielt, Medientasten Pause und Weiter (30 s über `CarSafePlayer` auf `output.player`) wie bisher.
- **Nachtrag (Controller, Tablet): erneutes Tippen auf den offenen Tab** führt zu seiner Wurzel (Material-Muster): ist
  der getippte Tab der aktive – auch mit Einstellungen oder einem Titel darüber –, geht es zurück bis zu seiner Wurzel; an
  der Wurzel passiert nichts (derselbe Eintrag, Zustand bleibt). Andere Tabs behalten ihre gespeicherten Seiten. Aktiv ist
  die Tab-Wurzel über dem Start-Tab (ein Tabwechsel lässt nur die Start-Wurzel und höchstens eine weitere Tab-Wurzel auf
  dem Stapel); `currentBackStack` darf die App nicht nutzen (Lint RestrictedApi), darum `getBackStackEntry` je Tab.
  **Claudes Wahl:** kein Hochscrollen an der Wurzel (jede Seite hält ihren eigenen Listenzustand – nicht billig); (in
  Fix-Runde 1 geändert, siehe dort:) mit zwei
  Bereichen (Tablet) bleibt die Detailseite rechts in der Bibliothek beim erneuten Tippen stehen (sie liegt nicht auf dem
  äußeren Stapel). Tests: `TabReselectTest` (5).
- What's new 0.4.0: zwei Zeilen (Kapitelsuche, erneutes Tippen auf den Tab), DE + EN.

### Was Phase 7 noch braucht

- **Profilwechsel (12.1):**
  - Oberfläche: Profilmenü (aktives Konto + `saved`), „Profil hinzufügen“ → Anmeldung, die `addProfile` statt
    `save`/`switchTo` ruft; „Profil entfernen“ für ein gespeichertes Konto (Server-Logout mit seinem Refresh-Token, Purge
    seiner Daten, `forget`) – den Ablauf dafür gibt es seit Fix-Runde 1 (`ProfileSwitch.removeProfile`), es fehlt die
    Oberfläche.
  - Abmelden des aktiven Kontos, wenn weitere gespeichert sind: auf eines wechseln statt zur Anmeldung (heute:
    Anmeldeseite, die gespeicherten bleiben erhalten).
  - Sync gespeicherter Konten: Worker und `ApiProvider` arbeiten nur mit dem aktiven Konto; ungesyncte Änderungen eines
    beiseitegelegten Kontos bleiben in Room, bis es wieder aktiv ist. Vor dem Wechsel `check()` (Push) aufrufen oder je
    Konto syncen.
  - Pausierte Downloads nach dem Zurückwechseln fortsetzen (heute pausiert wie bei „Behalten“); Downloads gespeicherter
    Konten in der Speicheranzeige der Downloads-Seite zeigen.
  - Zustand, der heute beim Wechsel gelöscht wird, je Konto speichern: RMAB-Verbindung, letzte Suchen.
  - Schutz vor dem Wechsel in ein Erwachsenenprofil (PIN/Gerätesperre) auf Kinder-Tablets – Entscheidung Fabian.
- **Cast (12.2):**
  - `media3-cast` (Version wie Media3) + Cast-Framework, `CastOptionsProvider`, Cast-Knopf (`MediaRouteButton`, Compose-
    Variante in media3-cast) oder System-Ausgabeumschalter; `CastPlayer.Builder(context).setLocalPlayer(exo)` als
    `PlaybackOutput.player`.
  - `MediaItemFactory`: Empfänger brauchen erreichbare URLs – Server-Stream mit Token im Query, keine `file://`-Downloads,
    keine Gerätetitel (CastPlayer überträgt nicht abspielbare Items nicht); der Empfänger muss die Serveradresse erreichen
    (öffentliche Adresse, keine Cleartext-Ausnahmen).
  - `PlaybackComfort` teilen: Titel-Teile (Tempo je Titel, Rückspulen nach Pause, letzte Aktivität) auf `output.player`,
    Effekte und Stille überspringen auf `output.device`; der Sleep-Fade an die Lautstärke des Empfängers statt an
    `exo.volume`.
  - `PlayerUiState`/Player: die Ausgabe zeigen (`isLocal`, Gerätename), z. B. im Quellen-Hinweis.

### Nachtrag Task 12 – Fix-Runde 1 nach Review (2026-11-05)

- **E1 – das erste Senden ist an sein Konto gebunden.** `ApiProvider.clientFor` baut Clients je (Konto, Basis-URL): ein
  Client gehört dem Konto, das beim Bauen angemeldet war (`retrofit` ebenso; ein Kontowechsel setzt die Verbindung
  ohnehin zurück, das nächste „online“ baut für das neue Konto). `AuthInterceptor` hängt dessen Token nur an, solange es
  das angemeldete Konto ist und die Basis-URL eine seiner Adressen ist; sonst geht die Anfrage gar nicht hinaus
  (`AccountChangedException`, eine `IOException` – Aufrufer behandeln sie wie einen Netzfehler). `TokenAuthenticator`
  refresht nur unter denselben Bedingungen. Damit: nach einem Wechsel erreicht kein Token von B den Server von A (auch
  nicht im kurzen Fenster, in dem der geteilte Zustand noch „online bei A“ meldet), eine für A gebaute Anfrage läuft auf
  demselben Server nie als B, und B wird dadurch nie zur Neuanmeldung markiert. `SentBy` entfällt.
  **Was weiterhin gilt (genau):** „für A gemacht“ heißt: über einen Client oder eine API-Instanz, die geholt wurde,
  während A angemeldet war. Code, der nach dem Wechsel noch mit A’s Server-ID arbeitet und sich die API *danach* holt
  (z. B. `ProgressSync.sync(serverId)`, Worker, ViewModels, die einen Wechsel überleben), sendet als B – heute ruft ihn
  nur das angemeldete Konto auf (der Wechsel stoppt die Arbeit des alten Kontos), in Phase 7 muss er es prüfen (siehe
  unten). Tests: `AccountBoundClientTest` (5: verschiedene Server – kein Token von B an A’s Server, B nicht markiert;
  derselbe Server – A’s Anfrage läuft nicht als B; nach Abmeldung nichts; fremde Adresse ohne Token).
- **E2** Titel und Suche werden vor dem Zerlegen zusammengesetzt (NFC), Zeichen-Marken gehören zum Wort: ein zerlegt
  gespeichertes „Käse“ (a + U+0308, z. B. Dateinamen von macOS) finden käse, kase und kaese, eine zerlegte Eingabe findet
  einen zusammengesetzten Titel.
- **E3** Die Wörter der Kapitel werden einmal je Titelliste vorbereitet (`ChapterSearch.index`, auf
  `Dispatchers.Default`), nicht mehr bei jedem Positions-Tick; ein Tastendruck vergleicht nur noch Wortanfänge. Bis der
  Index bereit ist (einen Moment nach dem Öffnen), wird nicht gefiltert.
- **E4** Buchstaben und Ziffern passen mit und ohne Leerzeichen dazwischen („cd2“ findet „CD 2“ und „CD2“, „teil1“
  „Teil 1“, „track007“ „Track 007“; „cd 2“ findet alle drei Schreibweisen). Eine Suche nur aus Satzzeichen ist keine
  Suche (keine Nummern, kein Sprung).
- **E5** Wechselt der geladene Titel bei offenem Blatt (die Warteschlange läuft weiter), beginnt der Kapitel-Tab neu:
  Suche leer, Liste am aktuellen Kapitel.
- **E6 TalkBack:** die Trefferzahl („3 Kapitel“) steht unter dem Feld und wird höflich angesagt (Live-Region), ebenso
  der leere Zustand; das Feld hat statt eines Platzhalters ein Label („Kapitel suchen“ bleibt über dem Text stehen);
  die Nummer eines Treffers liest TalkBack als „Kapitel 12“.
- **E7** `ProfileSwitch.removeProfile(serverId)` entfernt ein gespeichertes (nicht aktives) Konto wie eine Abmeldung ohne
  Behalten: Server-Logout mit dessen eigenem Refresh-Token (`savedCredentials`; nur, wenn die aktuelle Verbindung auf
  dessen Server ist – siehe Fix-Runde 2, N3), Purge seiner Zeilen und Downloads,
  `forget`. **Claudes Wahl:** die Löschschuld (pending purge) wird *vor* dem Vergessen eingetragen – bricht etwas
  dazwischen ab, beendet der Janitor den Purge beim nächsten Start; nie Daten ohne Besitzer. `AccountStore.forget`
  bleibt der letzte Schritt davon (KDoc). Die gespeicherte Liste wird Eintrag für Eintrag gelesen: ein unlesbarer Eintrag
  (beschädigt oder von einer späteren Version) wird übersprungen, die anderen bleiben; er wird unverändert
  zurückgeschrieben, und solange sich die lesbaren Einträge nicht ändern, wird gar nicht geschrieben (auch ein Wert, der
  gar keine Liste ist, bleibt erhalten).
- **E8 – geändert gegenüber der Claudes-Wahl oben:** mit zwei Bereichen schließt das erneute Tippen auf Bibliothek oder
  Suche auch die Detailseite rechts (zurück zur reinen Liste) – auf dem Tablet sieht das Tippen sonst wirkungslos aus.
  Umsetzung: ein Merker im Zustand des Host-Eintrags, den der Bereich mit `popBackStack(PaneEmpty)` beantwortet; mit
  einem Bereich wird er sofort verworfen (er schließt nie eine Seite, die später in den Bereich wandert). Hochscrollen
  bleibt weg.
- **E9** Der Test des Beiseitelegens liest Tokens und Flag des alten Kontos zurück; `PlaybackOutputTest` prüft die Regel
  von `isLocal` selbst (lokal und entfernt), nicht die Antwort eines eigenen Fakes.

#### Ergänzung zu „Was Phase 7 noch braucht“ (Fix-Runde 1)

- Profilwechsel: Clients je Konto ausdrücklich machen (`clientFor(serverId, baseUrl)` öffentlich) und alles, was mit einer
  bestimmten Server-ID arbeitet (Syncs, Worker, ViewModels, Coil-Cover einer Seite), die API seines Kontos holen lassen
  oder prüfen, dass diese Server-ID angemeldet ist – heute ist „für A gemacht“ der Zeitpunkt, zu dem Client/API geholt
  wurden. `removeProfile` an die Oberfläche („Profil entfernen“) anbinden.

### Nachtrag Task 12 – Fix-Runde 2 nach Re-Review (2026-11-05)

- **N1** `removeProfile` hielt mit `work.cancel` auch die Syncs des *angemeldeten* Kontos an (die Sync-Arbeiten gelten
  nicht je Server). Neu `AccountWork.cancelOwn(serverId)`: nur die Downloads dieses Servers und seine Arbeit außerhalb
  WorkManager, nie die Syncs; `removeProfile` nutzt sie. (Ein beiseitegelegtes Konto hat eigentlich keine laufende Arbeit
  – sie wurde beim Beiseitelegen beendet –, `cancelOwn` bleibt als Absicherung.) Test mit WorkManager: die Syncs bleiben
  eingeplant, nur die Downloads des entfernten Servers gehen.
- **N2** Kontooperationen laufen nacheinander: `SignOut` ist eine Instanz mit einer Sperre für Abmelden, das neue
  `signIn` (Einrichtung und Neuanmeldung gehen jetzt darüber), `switchTo`, Profilwechsel und `removeProfile`. `forget`
  meldet, ob es wirklich ein gespeichertes Konto entfernt hat (in derselben Schreiboperation entschieden), und
  `removeProfile` prüft vor dem Purge noch einmal, dass das Konto nicht das angemeldete ist. Deterministische Tests:
  Wechsel zu einem Profil, dann dessen Entfernen → abgelehnt, nichts gelöscht; Entfernen, dann Wechsel dorthin → nichts
  zum Wechseln; eine Anmeldung wartet auf eine laufende Abmeldung.
- **N3** Richtiggestellt (KDoc `removeProfile`, E7 oben): der Server-Logout eines entfernten Profils gelingt nur, wenn die
  aktuelle Verbindung auf dessen Server ist (der Familienfall: derselbe Server). Ein Profil auf einem anderen Server wird
  dort nicht abgemeldet; seine Sitzung läuft von selbst ab. Kein neuer Netzweg dafür jetzt.
- **N5** Die Clients eines Kontos (lokale und öffentliche Adresse) teilen sich eine Refresh-Sperre: 401er auf beiden
  Adressen refreshen nur einmal, der zweite nimmt den neuen Token („jemand anderes hat erneuert“). Vorher refreshte jede
  Adresse für sich mit demselben Refresh-Token (bestand schon vor Phase 6).
- **N6** Tests: ein Konto mit lokaler und öffentlicher Adresse bekommt sein Token auf beiden, auch beim Adresswechsel,
  ohne `AccountChangedException`; 401er auf beiden Adressen → ein Refresh (schlägt mit einer Sperre je Adresse fehl);
  eine Anfrage über `api.libraryApi` trägt das Token; der Satzzeichen-Test der Oberfläche wartet erst auf eine echte
  Suche (schlägt ohne die Regel fehl); der Test „nur dieses Konto“ unterscheidet `cancel` und `cancelOwn`.

- **Gerätedurchlauf (Emulator, lokaler Test-ABS) nach Fix-Runde 2:** ein Titel gestreamt (~20 s, dann Pause), dabei
  lief der „Beim Abspielen herunterladen“-Download mit (beides über die kontogebundenen Clients); derselbe Titel im
  Flugmodus offline gespielt, Flugmodus wieder aus, Download wieder gelöscht; Abmelden („Downloads behalten“) und neu
  angemeldet mit den Testzugangsdaten – Cover kamen nach dem Leeren der Caches neu vom Server; keine
  `AccountChangedException` im Log. TalkBack: ließ sich auf dem Emulator nicht zuverlässig steuern (beim ersten Start legt
  sich die Berechtigungsfrage der Bedienungshilfen über die App, der gesprochene Text ist nicht auslesbar) – die
  Ansage der Trefferzahl ist nur per Robolectric (Live-Region gesetzt) geprüft, ein Hörtest steht aus.

#### Ergänzung zu „Was Phase 7 noch braucht“ (Fix-Runde 2)

- Profil auf einem anderen Server beim Entfernen abmelden: dessen eigene Adressen prüfen (`AddressResolver`, begrenzt
  durch `LOGOUT_TIMEOUT_MS`) und den Logout dorthin schicken – heute nur, wenn die aktuelle Verbindung dort ist (N3).
- Langlebige Sammler (N4): `ApiProvider.retrofit` baut nach einem Kontowechsel nur deshalb neu, weil die Verbindung
  dabei kurz auf „offline“ geht. Wer die APIs dauerhaft sammelt statt je Aktion `.first()` zu nehmen, kann diesen
  Zwischenstand verpassen und behält den Client des alten Kontos (der dann nichts mehr sendet). Vor dem ersten solchen
  Sammler `retrofit` mit der Server-ID des angemeldeten Kontos kombinieren (`combine(shared, account-id)`), oder Clients
  nach einem Wechsel ausdrücklich neu holen.


---

## Umsetzung Task 10 (2026-11-05)

Paket „Modularisierung“ (Branch `p6-modules`). Neue Module `:core:model` und `:core:database`; `:core:playback` wartet
(Begründung unten). Alle Dateien sind per `git mv` umgezogen, in denselben Kotlin-Paketen (Importe bleiben), ohne
inhaltliche Änderung – so trägt Gits Umbenennungserkennung Änderungen anderer Zweige mit (Probe mit `git merge-tree`
gegen `p6-downloads`: dessen neue Abfragen in `OtherDaos.kt` landen in `core/database/…`, es entstehen keine Konflikte
außer denen, die `main` ↔ `p6-downloads` ohnehin hat).

- **10.1 `:core:model`** – reines Kotlin/JVM-Modul wie `:abs-api` (kein Android, kein Compose), mit dem Lint-Plugin
  `com.android.lint`, damit `lint` es mitprüft. Inhalt: `PlayableKey` (`playback`) und `DeviceLibrary` (`local`) samt
  `PlayableKeyTest` – die Schlüssel, die Datenbank, Wiedergabe, Sync und UI gemeinsam sprechen.
  - Kotlin smart-castet keine Property aus einem anderen Modul: die acht Stellen in `:app`, die `Parsed.episodeId` auf
    null prüften und dann benutzten, lesen sie jetzt zuerst in ein lokales `val` (gleiches Verhalten).
  - **Claudes Wahl:** `ItemSummary` bleibt in `:app`. Es ist das Kachel-Modell der UI, trägt Composes `@Immutable` und
    liegt mit zwei `CompositionLocal`s in einer Datei; aus einem Modul ohne Compose-Compiler gälte es als instabil
    (anderes Recompose-Verhalten). `TimelineMapper` rechnet über `AudioFileEntity`/`ChapterEntity` und gehört zur
    Wiedergabe (10.3). Die Enums der Entities (`DownloadState`, `LibraryKind`, `BookmarkPending`, `CollectionKind`) sind
    Spaltentypen und bleiben in ihren Entity-Dateien (`:core:database`); die übrigen Enums sind UI oder Einstellungen.
  - **Claudes Wahl:** `:abs-api` hängt nicht von `:core:model` ab – es braucht nichts daraus.
- **10.2 `:core:database`** – Android-Library mit Room-Plugin und KSP (nur der Room-Compiler). Umgezogen: Entities, DAOs,
  `LauschigDatabase`, `Converters`, `SeriesParsing`, dazu `QueueOrder` (`queue`) und `BookmarkRules` (`bookmarks`), die
  reinen Regeln, die `QueueDao` und `BookmarkDao` aufrufen. `schemas/` liegt jetzt in `core/database/schemas`
  (`git log --follow` zeigt die Historie bis zum ersten Schema); die Auto-Migrationen lesen es dort, `MigrationTest`
  liest es relativ zum Modul. Die Tests des Pakets `db` (u. a. `DaoTest`, `MigrationTest`, `QueryPlanTest`) und
  `BookmarkRulesTest` laufen in `:core:database` (66 Tests). `:app` hat kein Room-Plugin und kein Room-KSP mehr; sein
  KSP erzeugt nur noch Hilt.
  - **Claudes Wahl:** `DatabaseModule` (Hilt, stellt Datenbank und DAOs bereit) bleibt in `:app`, im selben Paket
    `io.wolftown.lauschig.db`. So braucht `:core:database` weder Hilt noch dessen KSP, und der Hilt-Graph ist
    unverändert.
  - **Claudes Wahl:** `:core:database` hängt per `api` von `:core:model` und `:abs-api` ab (`BookmarkDao.mergeFromServer`
    nimmt die `BookmarkDto`-Liste des Servers); Room und Coroutines per `api`, weil `:app` `withTransaction`, `Flow` und
    `Room.databaseBuilder` direkt nutzt.
  - **Claudes Wahl:** keine Android-Ressourcen im Modul (`androidResources.enable = false`, spart R-Klasse und
    Ressourcen-Tasks); `testOptions.targetSdk = 37` wie `:app`, damit Robolectric-Tests ohne `@Config(sdk)` auf demselben
    SDK laufen wie vorher. Keine eigene `robolectric.properties` nötig (die Library hat keine Application-Klasse).
  - Release (R8): baut; `LauschigDatabase` und `LauschigDatabase_Impl` behalten ihre Namen (die Keep-Regel in
    `app/proguard-rules.pro` gilt für Library-Klassen genauso). Der Baseline-Profile der App nennt Klassen beim Namen
    und wird auf den gesamten Dex angewandt – Namen und Pakete sind gleich geblieben.
- **10.3 `:core:playback` wartet.** **Claudes Wahl**, Begründung: Die genannten Klassen sind kein abgrenzbarer Kern.
  `PlaybackService` importiert vierzehn Klassen aus `auto` (Android Auto: `CarLibrary`, `CarButtons`, `LibraryCallback` …),
  dazu `widget`, `sync`, `connection`, `queue`, `download`, `net`, `account`, `settings`, `R` und `MainActivity`;
  `PlayerControllerImpl` braucht `sync`, `download`, `net`, `connection`; `SleepTimer` und `PlayerController` hängen an
  `settings.PlaybackSettings`, `CarSafePlayer` an `auto.CarControls`. `auto` wiederum importiert `playback` (sieben
  Dateien) – ein Paketzyklus. Ein Umzug hieße entweder die halbe App mitzunehmen oder Schnittstellen für Auto, Widget,
  Sync, Verbindung und Download-Start einzuziehen und in `:app` neu zu binden – ein Umbau mit Verhaltensrisiko, kein
  Umzug. Dazu stellt `p6-downloads` alles Logging auf `io.wolftown.lauschig.diagnostics.Log` in `:app` um, auch in
  `PlaybackService`, `PlayerControllerImpl` und `ServiceTitleStarter`; ein Playback-Modul bräuchte den Logger zuerst
  in einem eigenen Modul. Voraussetzungen für später: (1) `diagnostics.Log` in ein kleines `:core:diagnostics`, (2)
  `PlaybackSettings` (und die DataStore-Einstellungen) in ein Modul, (3) Schnittstellen für die Partner des Services
  (Auto-Bibliothek, Widget, Sync-Auslöser, Verbindungsstatus, Download-Start) in `:core:playback`, gebunden in `:app`,
  (4) Benachrichtigungs-Strings/-Symbole ins Modul, `MainActivity` über einen injizierten `PendingIntent`.
- **Generierter Code:** Room erzeugt in `:core:database` Byte für Byte dieselben Dateien wie vorher in `:app`
  (`diff -r` der KSP-Ausgaben), die Schemas sind unverändert. Tests: vorher 1671 in `:app`, jetzt 1602 in `:app` + 66
  in `:core:database` + 3 in `:core:model` = 1671 (dazu 48 in `:abs-api`), alle grün. Die Datenbanktests laufen allein
  in rund 7 s (`./gradlew :core:database:testDebugUnitTest`).
- **10.4 Messung** (16 Kerne, warmer Daemon, je 3 Läufe, Median; `--offline --no-build-cache`, Configuration Cache an
  wie im Repo; vorher = `main` 020e299 in einem temporären Worktree, nachher = `p6-modules`, direkt hintereinander):

  | Szenario | vorher | nachher |
  |---|---|---|
  | `clean` + `assembleDebug` | 22,0 s (21,9 / 22,0 / 22,5) | 18,2 s (26,7 / 18,2 / 15,7) |
  | `clean` + `assembleDebug`, Runde 2 abwechselnd, Last bis 8 | 26,1 s (26,1 / 26,0 / 26,4) | 26,7 s (26,7 / 29,1 / 24,8) |
  | `clean` + `testDebugUnitTest` (kompilieren + alle Tests) | 56,1 s | 55,7 s |
  | eine Zeile in einer UI-Datei (`PlayerScreen.kt`) + `assembleDebug` | 2,7 s | 1,8 s |
  | eine Zeile in einer DAO-Abfrage (`ItemDao.kt`) + `assembleDebug` | 5,0 s | 5,0 s |

  Ergebnis: Saubere Builds und Tests sind gleich schnell (innerhalb der Streuung). Eine UI-Änderung ist knapp 1 s
  schneller, weil das KSP von `:app` kein Room mehr verarbeitet. Eine geänderte DAO-Abfrage bringt nichts: der
  `@Query`-Text steht in der Klassendatei, die ABI von `:core:database` ändert sich, also laufen KSP, Kotlin und Hilt
  in `:app` trotzdem. Die Annahme des Plans („>95.000 Zeilen bremsen spürbar“) trifft nicht zu: `app/src/main` hat jetzt
  34.755 Zeilen Kotlin (vorher 36.578), der Build war schon schnell. Der Nutzen ist Struktur (Room-KSP getrennt, Datenbank
  mit eigenen Tests, klare Abhängigkeitsrichtung), nicht Tempo – auch deshalb lohnt der riskante Umbau für 10.3 jetzt
  nicht.
- **Merge mit `p6-downloads`:** Dessen Stand vom 5.11. mittags (c50f4fd) hat eine neue Stelle, die `Parsed.episodeId`
  smart-castet (`ui/common/ItemActions.kt`, `titleOf` um Zeile 127): nach dem Merge braucht sie dieselbe
  Ein-Zeilen-Korrektur (`val episodeId = p.episodeId`, dann `episodeId` statt `p.episodeId`); der Compiler meldet jede
  solche Stelle mit „Smart cast … declared in different module“.
- **CI:** `.github/workflows/ci.yml` ruft zusätzlich `:core:model:test`; `lint` und `testDebugUnitTest` (Wurzel)
  erreichen `:core:database` von selbst, `lint` auch `:core:model`. README: Build-Befehl und eine Zeile zu den Modulen.
- Keine Version, keine What's-new-Zeile (nichts Sichtbares). **Claudes Wahl.**
