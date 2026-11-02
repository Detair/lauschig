# Stream + Download zusammen: Pufferanzeige, Auto-Download, Stream-Vorrang (2026-11-02)

Anlass: Fabians Alltagstest. Stream-Starts dauern teils sehr lange, ohne dass man sieht, wie lange noch. Play auf einem
neuen Buch soll sofort streamen und im Hintergrund herunterladen (der Wechsel auf die lokale Datei existiert seit
4aa8402/1686359). Ein Download darf den Stream bei schlechter Verbindung nicht abwürgen.

## Befund zur Startzeit
m4b-Dateien haben oft das `moov`-Atom (Inhaltsverzeichnis, bei 15 h Hörbuch ~10 MB Sample-Tabellen) am Dateiende.
ExoPlayer muss es komplett laden, bevor der erste Ton kommt. Das ist der lange Teil, nicht die 2,5 s Audio-Puffer.
ExoPlayer liest dafür einen eigenen HTTP-Bereich ab Position p (> 0); `Content-Length` der Antwort = erwartete Bytes.

## Entscheidungen (Fabian, 2026-11-02)
1. **Auto-Download bei Play** eines nicht (vollständig) geladenen Titels: Stream startet immer sofort.
   - Ungemessenes Netz (WLAN): Download sofort im Hintergrund.
   - Gemessenes Netz (Mobilfunk): **nachfragen** (Dialog mit Größe), Antworten „Jetzt über Mobilfunk laden“ /
     „Im WLAN laden“ (wartet auf ungemessenes Netz), Häkchen „Auswahl merken“ speichert die Antwort als Einstellung.
   - Einstellungen: „Beim Abspielen herunterladen“ (an/aus, Standard an) und „Über Mobilfunk“ (Fragen / Immer /
     Nur im WLAN, Standard Fragen).
2. **Schlechte Verbindung:** erst drosseln; **sobald gedrosselt wird, Hinweis** „Download langsam – Stream hat Vorrang“.
   Hängt der Stream trotz Drosselung, Download **anhalten und fragen** mit Schätzung der Download-Dauer bei aktueller
   Verbindung: „Weiter streamen“ (Download bleibt pausiert) / „Herunterladen“ (Wiedergabe pausiert, Download läuft
   ungebremst weiter).
3. **Pufferanzeige** solange ein Stream lädt: geladene Bytes, Rate, und wenn bekannt erwartete Bytes + Restzeit mit
   bestimmtem Fortschrittsbalken (Vollplayer) bzw. kompakter Text (Mini-Player).

## Bausteine
- `playback/StreamMonitor` (@Singleton, gleicher Prozess wie Service, Worker und UI):
  - `TransferListener` am `OkHttpDataSource` des Players: Bytes je Transfer, Startposition, erwartete Bytes
    (`Content-Length` bei Transfer ab p > 0, sonst unbekannt), gleitende Rate (Fenster ~3 s).
  - Vom `PlaybackService` alle 500 ms: Stream aktiv (playWhenReady und aktuelle Datei ist http), Phase
    STARTING (seit prepare/Seek noch nicht gespielt) / PLAYING / STALLED (buffert nach Spielen), Puffer-Vorlauf.
  - `state: StateFlow<StreamStats>`; reine Logik in einer testbaren Klasse mit injizierter Uhr.
- Größerer Audio-Puffer: `DefaultLoadControl` min 5 min / max 10 min Vorlauf (Audio ist klein), Start 2,5 s,
  nach Rebuffer 5 s. Überbrückt Funklöcher und gibt der Drosselung Spielraum.
- `PlayerUiState.streamLoad: StreamLoad?` (bytes, expectedBytes?, bytesPerSec, etaSec?) nur während
  `isLoading && isStreaming`; `downloadThrottled: Boolean` für den Hinweis.
- `download/StreamPriority` (rein, testbar): Entscheidung je Chunk FULL / THROTTLE / PAUSE.
  - Kein aktiver Stream → FULL.
  - THROTTLE wenn Phase STARTING oder STALLED oder Vorlauf < 30 s; zurück zu FULL erst ab Vorlauf ≥ 60 s in PLAYING
    (Hysterese).
  - PAUSE wenn gedrosselt und STALLED ≥ 5 s oder STARTING ≥ 20 s.
  - Hinweis „gedrosselt“ erst nach ≥ 5 s Drosselung am Stück (normale Stream-Starts flackern sonst).
- `DownloadWorker`: bei THROTTLE ~250 ms Pause je 64-KB-Chunk; bei PAUSE `.part` behalten, Zeile PAUSED
  (Code `stream_priority`), Rückfrage auslösen, Erfolg zurückgeben. Eigene ungedrosselte Rate messen.
  Benachrichtigungstext bei Drosselung: „Gedrosselt – Stream hat Vorrang“.
- Schätzung: Restbytes / Verbindungsrate (Summe Stream- + Download-Rate der letzten ~10 s; fehlt beides → keine Zahl).
- `download/DownloadPrompts` (@Singleton): `StateFlow<DownloadPrompt?>` mit `Metered(serverId, itemId, title, bytes)`
  und `Contention(serverId, itemId, title, etaSec?)`; `AppRoot` zeigt den Dialog. Bleibt bis zur Antwort stehen
  (App im Hintergrund → beim Öffnen sichtbar).
- `DownloadManager.enqueue(..., network: NetworkType? = null)`: Override der Netzbedingung; eine schon wartende
  Arbeit mit anderer Bedingung wird ersetzt (laufende nicht).
- Auslöser im `PlayerControllerImpl`: nach erfolgreichem Start eines Streams Hook `onStreamStarted(serverId, itemId)`
  → `PlayDownloadStarter` (Einstellungen, gemessenes Netz via `ConnectivityManager.isActiveNetworkMetered`,
  vorhandene Download-Zeile: COMPLETE/QUEUED/RUNNING → nichts; PAUSED durch den Nutzer → nichts).

## Nicht-Ziele
- Kein HLS/Transcoding über die ABS-Play-Session.
- Keine Bandbreitenmessung ohne laufenden Stream oder Download.
