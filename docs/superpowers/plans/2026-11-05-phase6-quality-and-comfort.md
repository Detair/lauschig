# Phase 6 – Qualität, Performance & Komfort (Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Implement the post-Phase-5 quality, performance, and user-comfort improvements identified during the project review: configurable asymmetric seek intervals, search facet caching, sleep timer audio warning, external storage (SD card) support, family support diagnostic log sharing, and optional auto-cleanup of completed downloads.

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
  - `PlayerScreen`: Buttons für Replay/Forward zeigen die Sekundenzahl dynamisch (oder passende Icons `Replay10`, `Replay15`, `Forward30`, etc.) mit Barrierefreiheits-Texten („X Sekunden zurückspringen“ / „X Sekunden vorspringen“).
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

## 3. Reihenfolge & Vorgehensweise

1. **Zweig anlegen:** `git checkout -b phase-6 main`.
2. **Commit pro Task** nach strengem TDD-Muster (Test schreiben -> Implementieren -> Verifizieren).
3. **Abschluss:**
   - `./gradlew testDebugUnitTest :abs-api:test lintDebug assembleDebug -q`
   - Überprüfung auf Emulator/Gerät.
   - PR erstellen und mergen.
