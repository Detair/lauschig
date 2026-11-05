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
