# Phase 7: Profilwechsel und Chromecast – Plan

> **Für Agenten:** Umsetzung mit superpowers:subagent-driven-development, Task für Task. Abhaken nicht nötig; je Task kommt
> am Ende dieser Datei ein Abschnitt „Umsetzung Task N“ dazu.

**Ziel:** Mehrere Konten auf einem Gerät (Familien-Tablet): schnell wechseln ohne Abmelden, optional geschützt per
Gerätesperre oder App-PIN. Danach Hörbücher auf Chromecast und Smart-Speaker schicken.

**Architektur:** Der Unterbau aus Phase 6 bleibt (`AccountStore.saved`, `ProfileSwitch`, `PlaybackOutput`, Clients je
Konto). Task 1 macht den Wechsel unter der Haube vollständig, Task 2 baut die Oberfläche, Task 3 den Schutz. Das ist Paket
7A (Branch `p7-profiles`). Task 4 (Cast) ist Paket 7B und wird nach dem Merge von 7A an dessen Stand präzisiert.

**Technik:** Kotlin, Compose M3, Hilt, Room, DataStore, Media3 1.11.1, WorkManager, Robolectric `@Config(sdk=[35])`.

**Spec:** `docs/superpowers/specs/2026-10-31-lauschig-design.md` §2.7d. Herkunft der Punkte: Abschnitte „Was Phase 7 noch
braucht“ im Plan `2026-11-05-phase6-quality-and-comfort.md` (Task 12, Fix-Runden 1 und 2).

## Fabians Entscheidungen (2026-11-05)

- Reihenfolge: erst Profile, dann Cast. `:core:playback` bleibt draußen (kaum Build-Gewinn, Verhaltensrisiko).
- Schutz vor dem Wechsel in ein Profil ist eine **Einstellung**, keine Pflicht. Möglich: Gerätesperre **oder App-PIN**.
- Cast darf das proprietäre Cast-Framework mitbringen, mit Lizenzvermerk im Repo.
- Claude testet, reviewt und merkt selbst nach grüner CI. Echte Cast-Tests spielen hörbar im Raum: dafür vorher fragen.

## Global Constraints

- Version 0.5.0 / versionCode 5, `Release(5, "0.5.0", R.array.whatsnew_0_5_0)`, DE und EN.
- Wer nur ein Konto hat, sieht nichts Neues außer einer Zeile in den Einstellungen; Verhalten und Daten bleiben gleich
  (Upgrade von 0.4.0 getestet, auch `DataStore`-Migrationen).
- Tokens und PIN-Daten nur verschlüsselt, nie im Log, nie im Diagnose-Log, nicht im Backup.
- Keine Anfrage von Konto A mit dem Token von Konto B, kein Schreiben von A's Daten in B's Zeilen – auch nicht in den
  Sekunden rund um einen Wechsel.
- Im Zweifel behalten: Ein Wechsel löscht keine Zeilen und keine Downloads.
- Room-Schema nur ändern, wenn es nicht anders geht (dann v12 mit Migrationstest).
- TalkBack: alles beschriftet, Fehler als Live-Region. Texte als String-Ressourcen DE und EN.

## Review Focus

1. Wechsel bei laufender Wiedergabe, laufendem Download, geöffnetem Player oder verbundenem Android Auto: Von A läuft danach
   nichts mehr, B startet still, ohne Mini-Player von A. Test mit echtem `SignOut`-Ablauf.
2. Ein später Worker oder Sync von A läuft erst nach dem Wechsel an: Er sendet nicht als B und schreibt nicht in B's Zeilen.
   Test je Sync-Klasse gegen einen Server mit zwei Benutzern.
3. Schutz-Umgehung: Entfernen eines geschützten Profils, Anmeldeseite, Profilauswahl nach Abmeldung, Prozess-Tod während der
   PIN-Abfrage, Uhr zurückstellen während der Sperre. Test je Weg.
4. Profil hinzufügen über SSO, mit Drehen und Prozess-Tod mitten im Browser-Rücksprung: Das bisherige Konto bleibt erhalten,
   der Modus geht nicht verloren. Test.
5. Abbruch: Profil hinzufügen und zurück, falsches Passwort, Server nicht erreichbar, Token eines gespeicherten Kontos
   abgelaufen (Rückkehr fragt nach Neuanmeldung genau dieses Kontos). Test.

---

## Task 1: Der Wechsel unter der Haube (ohne Oberfläche)

**Dateien:** `account/SignOut.kt`, `account/AccountStateCleaner.kt`, `net/ApiProvider.kt`, `rmab/RmabRepository.kt`,
`ui/search/RecentSearches.kt`, `playback/LastPlayedStore.kt`, `account/AccountData.kt`, Syncs in `sync/`, Tests daneben.

- **1.1 Zustand je Konto.** `AccountStateCleaner` bekommt zwei Wege: `clear(serverId)` (Abmelden, wie heute, nur für dieses
  Konto) und `setAside(serverId)` (Wechsel). Beim Wechsel bleiben RMAB-Verbindung und letzte Suchen, gespeichert je
  `serverId`. Weiter gelöscht werden offener SSO-Versuch, offene Download-Frage, Aufräum-Benachrichtigung, Diagnose-Log und der
  Cover-Speicher im RAM. **Claudes Wahl:** Der Cover-Cache auf der Platte bleibt beim Wechsel, sofern die Schlüssel keine
  Tokens enthalten (prüfen, Test) – Konto B fragt nur Titel seiner eigenen Zeilen ab, und beim Zurückwechseln sind die Cover
  sofort da. Migration: Die alten Einzelwerte gehören dem beim Upgrade aktiven Konto.
- **1.2 Zuletzt gespielt je Konto.** `LastPlayedStore` hält den Server-Titel je Konto. Beim Zurückwechseln stehen Mini-Player
  (pausiert) und Wiedergabe-Fortsetzung für Auto und Medientaste wieder bereit. Abspielen startet nie von selbst.
- **1.3 Vor dem Wechsel sichern.** `switchProfile`: Wiedergabe pausieren (die Position ist damit endgültig), Änderungen
  hochladen (höchstens 5 s, **Claudes Wahl**), dann verlassen. Offline oder bei Zeitablauf wird trotzdem gewechselt; die
  Änderungen bleiben in Room und gehen hoch, wenn das Konto wieder aktiv ist. `addProfile` genauso.
- **1.4 Nach dem Wechsel.** `sync.syncNow()`. Nur die Downloads, die der Wechsel pausiert hat, laufen weiter – selbst pausierte
  bleiben pausiert. Merker je Konto im DataStore, keine Schemaänderung. Die WLAN-Regel gilt weiter.
- **1.5 Clients und Syncs an ihr Konto binden.** `clientFor(serverId, baseUrl)` öffentlich. Alles, was mit einer `serverId`
  arbeitet (`ProgressSync.sync(serverId)`, Sitzungs-Sync, Lesezeichen, Bibliothek, Worker), holt die API dieses Kontos oder
  bricht ab, wenn es nicht das aktive ist – heute würde eine API, die nach dem Wechsel geholt wird, als B senden. `retrofit`
  kombiniert die Verbindung mit der Konto-ID, damit lange Sammler keinen alten Client behalten.
- **1.6 Abmelden auf anderem Server.** `removeProfile` erreicht den Server des Profils über dessen eigene Adressen
  (`AddressResolver`, begrenzt durch `LOGOUT_TIMEOUT_MS`). Scheitert das, läuft die Entfernung trotzdem durch.
- **1.7 Durchsicht aller Singletons.** Alles, was eine `serverId` oder ein Konto zwischenspeichert (`PlaybackService`,
  `CarLibrary`, Widget, `SpeedMemory`, `AutoResume`, `CoverStore`, `NextVolumePrefetcher`, Erfolge und Statistik), wird geprüft;
  der Bericht führt jede Klasse mit Befund. Android Auto bekommt nach dem Wechsel `notifyChildrenChanged`, das Widget ein Update.
- **Tests:** Ein Integrationstest `ProfileSwitchFlowTest` mit zwei Benutzern auf einem `MockWebServer`: A spielt und lädt, Wechsel
  zu B, später Worker von A, zurück zu A. Prüft Tokens je Anfrage, Zeilen je Konto, Downloads, zuletzt gespielt, RMAB, Suchen.

## Task 2: Die Oberfläche

**Dateien:** neu `ui/profiles/` (`ProfilesSheet`, `ProfilesViewModel`, `ProfilePickerScreen`), `ui/AppRoot.kt`,
`ui/nav/*`, `ui/setup/*`, Einstellungen, `ui/downloads/*`, `strings.xml` DE und EN.

- **2.1 Profil-Blatt.** Erste Zeile der Einstellungen: „Profil: Name · Server“, öffnet das Blatt. Liste mit Initialen-Avatar
  (Farbe stabil aus der `serverId`), Name, Server nur wenn sich die Server unterscheiden, Haken am aktiven, Hinweis „Neu anmelden
  nötig“. Tippen wechselt, Überlaufmenü „Entfernen“ (nur gespeicherte), unten „Profil hinzufügen“. **Claudes Wahl:** Ab zwei
  Profilen steht der Avatar auch in der Kopfzeile der Startseite neben dem Zahnrad; mit einem Profil bleibt die Startseite
  unverändert.
- **2.2 Während des Wechsels.** Kurze Anzeige „Wechsle zu Name …“, danach ist der Navigationsstapel leer bis auf den Start-Tab,
  der Player zu, die Snackbar nennt das neue Profil. Kein Rest von A sichtbar (Test über `AppViewModel`).
- **2.3 Profil hinzufügen.** `Setup(mode = AddProfile)`: Adressen des aktiven Kontos vorbelegt und änderbar (Familienfall: gleicher
  Server), Name und Passwort leer. `complete` ruft `addProfile`. Zurück kehrt ins aktive Konto zurück. Der Modus überlebt Drehen,
  Prozess-Tod und den SSO-Rücksprung (Route und `SavedStateHandle`).
- **2.4 Entfernen.** Dialog mit Größe der Downloads auf diesem Gerät, dann `removeProfile`.
- **2.5 Abmelden mit weiteren Profilen.** Kein Konto aktiv, aber gespeicherte da: `ProfilePickerScreen` („Wer hört zu?“) statt
  Anmeldeseite, mit „Profil hinzufügen“. **Claudes Wahl:** kein automatischer Wechsel, denn das nächste Profil kann geschützt sein.
- **2.6 Downloads-Seite.** Eine Zeile „Andere Profile auf diesem Gerät: 2,1 GB“ in der Speicheranzeige, ohne Löschen von hier.
- **2.7 Mitnahme.** „… die nur auf diesem Handy liegen“ wird „… auf diesem Gerät“ (auch auf dem Tablet richtig).
- **2.8 What's new** 0.5.0, Versionssprung. Tablet quer und Handy hoch geprüft.
- **2.9 Wizard beim ersten Start (Fabian, 2026-11-05).** Beim allerersten Start erklärt ein kurzer Wizard in wenigen Seiten die App
  und bietet am Ende die Anmeldung an. **Claudes Wahl:** Er erscheint nur, wenn kein Konto und kein gespeichertes Profil da sind und
  er noch nicht gesehen wurde; wer von einer älteren Version kommt, sieht ihn nie. Jede Seite lässt sich überspringen, danach kommt
  die Anmeldeseite wie bisher. Er sagt klar, dass ein eigener Audiobookshelf-Server nötig ist. Prozess-Tod und Drehen verlieren die
  Seite nicht, TalkBack liest alles vor, Handy und Tablet quer. „Gesehen“ steht erst nach der letzten Seite oder dem Überspringen fest.

## Task 3: Der Schutz

**Dateien:** neu `account/ProfileLocks.kt` (Speicher), `ui/profiles/ProfileGate.kt`, `ui/profiles/PinEntry.kt`,
`MainActivity.kt` (`FragmentActivity`), `gradle/libs.versions.toml` (`androidx.biometric`), `res/xml` Backup-Regeln.

- **3.1 Speicher.** Je `serverId`: `NONE`, `DEVICE` oder `PIN` (Salt, PBKDF2-HMAC-SHA256, mindestens 120 000 Runden, auf dem
  Tablet A7 Lite messen, unter 500 ms halten), verschlüsselt mit dem vorhandenen `TokenCipher`, konstante Vergleichszeit. Vom
  Backup ausgeschlossen wie die Tokens (Test gegen die Regeln).
- **3.2 Einstellung.** Im Profil-Blatt für das aktive Profil: „Profil schützen“ mit Aus / Gerätesperre / App-PIN (4–8 Ziffern,
  zweimal eingeben). Standard Aus. Ein kurzer Satz sagt, wofür es gedacht ist: Es hält Kinder vom Wechsel ab, es ist kein
  Schutz gegen jemanden mit vollem Zugriff aufs Gerät. Gerätesperre ist ausgegraut mit Hinweis, wenn das Gerät keine hat.
- **3.3 Wo der Schutz gilt (eine Stelle: `ProfileGate`).** Wechsel in ein geschütztes Profil, Auswahl nach Abmelden, Entfernen
  eines geschützten Profils, Ändern des Schutzes. Nicht bei: Verlassen eines Profils, Abmelden des aktiven Profils, Anmeldung
  mit Passwort oder SSO (das ist der stärkere Nachweis). Auto und Widget wechseln nie das Konto.
- **3.4 Gerätesperre.** `androidx.biometric`, `BiometricPrompt`. Auf Android 9 und 10 das unterstützte Vorgehen wählen und testen.
  Ist die Sperre später entfernt: nicht offen lassen, sondern wie „PIN vergessen“ weiter.
- **3.5 Falsche Eingaben.** Nach 5 Fehlern 30 s warten, dann verdoppeln bis 15 min; Zähler überlebt Neustart. Uhr zurückstellen
  verkürzt die Wartezeit nicht (Test). Ansage per Live-Region.
- **3.6 PIN vergessen.** Führt auf `Setup(mode = Unlock(serverId))`: Eine erfolgreiche Anmeldung genau dieses Kontos entfernt den
  Schutz und wechselt hinein; ein anderes Konto wird abgelehnt und ändert nichts.
- **3.7 Aufräumen.** Abmelden, Entfernen und Purge löschen den Schutz dieses Kontos. Eine spätere neue Anmeldung beginnt ohne.

## Task 4: Chromecast (Paket 7B, nach dem Merge von 7A)

Festgelegt ist bisher:

- `media3-cast` (Version wie Media3) und das Cast-Framework. Der Default Media Receiver reicht, kein eigener Empfänger.
- **Lizenz:** Vermerk nach GPLv3 §7 (zusätzliche Erlaubnis zum Linken mit Google Play Services) in `LICENSE`/`README`, als Text von
  Fabian als Rechteinhaber freigegeben. Ohne Play Services läuft die App wie bisher, nur ohne Cast-Knopf.
- **Kein langlebiges Token in einer URL.** Bevorzugt die sitzungsgebundene ABS-Adresse `/public/session/<id>/track/<n>` (gegen ABS
  2.37 prüfen). Funktioniert das nicht zuverlässig, wird das im Plan festgehalten und entschieden, bevor weitergebaut wird.
- Gecastet werden nur Server-Titel über eine vom Empfänger erreichbare Adresse; `file://` und Gerätetitel nicht, der Knopf erklärt das.
- `CastPlayer.Builder(context).setLocalPlayer(exo)` als `PlaybackOutput.player`; `PlaybackComfort` teilt sich in Titel-Teile
  (`output.player`) und Effekte (`output.device`); Sleep-Fade an die Lautstärke des Empfängers; der Player zeigt die Ausgabe.
- Test mit dem Cast-Gerät im Netz nur nach Fabians OK (hörbar im Raum), mit dem Testkonto.

## Reihenfolge und Abschluss

1. Task 1 → Review → Task 2 → Review → Task 3 → Review, alles auf `p7-profiles`, Emulator 5554.
2. Gesamtreview von 7A, Gerätedurchlauf mit zwei Benutzern auf dem lokalen Test-Server, PR, CI, Merge.
3. Task 4 präzisieren, Branch `p7-cast`, Umsetzung, Review, Test nach OK, PR, CI, Merge.
4. Prüfung: `timeout 1500 ./gradlew testDebugUnitTest :abs-api:test :core:model:test lintDebug assembleDebug -q`.

---

## Umsetzung Task 1 (2026-11-05)

Branch `p7-profiles`, ohne Oberfläche: `ProfileSwitch` bleibt nur aus Tests erreichbar, keine neuen Texte, kein Versionssprung.
Keine Schemaänderung (eine neue DAO-Abfrage `activeKeys`, Room bleibt v11).

- **1.1 Zustand je Konto.** `AccountStateCleaner` hat drei Wege: `clear(serverId)` (Abmelden, nur dieses Konto), `setAside(serverId)`
  (Wechsel: RMAB, letzte Suchen und Cover auf der Platte bleiben; SSO-Versuch, Download-Frage, Aufräum-Benachrichtigung,
  Diagnose-Log und Cover im RAM gehen) und `forget(serverId)` (gespeichertes Profil wird entfernt: seine Verbindung und Suchen, dazu wie beim Abmelden die Cover-Caches; siehe Fix-Runde 1).
  `RmabRepository` und `RecentSearches` speichern unter `<schlüssel>:<serverId>` und folgen dem aktiven Konto. Was parallel läuft,
  endet für das Konto, das es begonnen hat (ein `connect` über den Wechsel hinweg speichert beim alten Konto; der Status-Cache gilt je Konto).
  Migration wie bei `migrateAutoDelete`: `migrate(aktives Konto)` bei jedem Start; abgemeldet haben die alten Werte keinen Besitzer und
  gehen. `clear` und `setAside` rufen sie vorher selbst auf: Das verlassene Konto ist in dem Moment das rechtmäßige.
  **Geprüft:** Der Schlüssel im Cover-Cache ist die URL aus `coverModel` (`…/api/items/<id>/cover?ts=<n>`), das Token steht nur im Header. Test.
- **1.2 Zuletzt gespielt je Konto.** `LastPlayedStore`: `get(serverId)`, `set`, `forget(serverId)`, dazu `forAccount(serverId)`. Die
  alten drei Schlüssel wandern per `DataMigration` zum Eintrag ihrer eigenen Server-ID, vor dem ersten Lesen. `PlayerController.showPaused`
  zeigt den Titel pausiert an seiner Position, ohne den Player anzufassen.
  - **Geändert in Fix-Runde 1 – Gerätetitel:** Jedes Konto hat seinen eigenen zuletzt gespielten Gerätetitel. `forAccount` nimmt den neueren
    von eigenem Server-Titel und eigenem Gerätetitel; mit einem Konto ist das das Verhalten von heute. Details unten.
  - **Geändert in Fix-Runde 1 – Mini-Player:** Er zeigt, was das Konto selbst zuletzt gespielt hat, auch einen Gerätetitel. Der gemeinsame
    Eintrag aus der Zeit davor erscheint dort nicht („B startet still“); Medientaste und Auto finden ihn weiterhin.
  - **Claudes Wahl – kein Laden in den Player:** `showPaused` ist der Zustand wie nach einem beendeten Dienst. Laden hieße puffern
    und eine Benachrichtigung. Nach einem Kaltstart gibt es wie heute keinen Mini-Player.
- **1.3 Vor dem Wechsel sichern.** `saveBeforeLeaving`: pausieren, dann Änderungen hochladen, höchstens `SWITCH_PUSH_TIMEOUT_MS` (5 s), auch der
  Verbindungs-Check zählt mit. Offline, Zeitablauf oder Fehler: Es wird gewechselt, die Änderungen bleiben in Room.
- **1.4 Nach dem Wechsel.** `arrive`: Downloads, Mini-Player, `syncNow`. `SwitchedDownloads` merkt je Konto im DataStore, was der Wechsel
  pausiert hat; weiter läuft nur, was noch `PAUSED` ist.
  - **Claudes Wahl – WLAN:** `enqueue` ohne Netz-Vorgabe, die Einstellung gilt. Ein früheres „jetzt über Mobilfunk“ überlebt den Wechsel nicht.
  - **Claudes Wahl – Fehlschläge:** Lässt sich das Ziel nicht aktivieren, bleibt das bisherige Konto und läuft weiter wie nach einem
    Zurückwechseln (`switchProfile` = false, `addProfile` wirft). Prozess-Tod mitten im Wechsel: `LauschigApp` setzt beim Start fort; das gilt seit Fix-Runde 1 auch für Zeilen, die noch wartend oder laufend stehen.
    Ein gespeichertes Konto, das sich neu anmeldet (`signIn`, `switchTo`), bekommt seine Downloads auch zurück. Abmelden und Entfernen vergessen den Merker.
- **1.5 Clients und Syncs.** `ApiProvider.meApi(serverId)`, `libraryApi(serverId)`, `adminApi(serverId)`: `null`, solange dieses Konto nicht
  angemeldet ist; die Felder ohne Konto sind weg (außer `authApi`). `retrofit` kombiniert Verbindung und Konto-ID. `clientFor(serverId, baseUrl)`
  ist öffentlich. Umgestellt: `ProgressSync`, `BookmarkSync`, `LibrarySync`, `CollectionSync`, `ListeningSessionSync`, `HistoryRepository`,
  `ProgressActions`, `AuthorInfoCache`, `AdminRepository`, `DownloadWorker`, Stream (`PlaybackService`), Auto-Cover.
  **Der Fehler war echt:** Vor der Änderung schickte `ProgressSync.sync(A)` nach dem Wechsel `PATCH /api/me/progress/a-book` mit B's Token.
  - **Claudes Wahl – später Download-Worker:** Er sendet nichts, setzt die Zeile auf `PAUSED`, trägt sie in den Merker ein und endet
    erfolgreich (kein Retry, kein Fehlversuch). Genauso, wenn der Wechsel mitten im Download kommt. `AccountChangedException` bei weiter
    angemeldetem Konto zählt seit Fix-Runde 1 wie ein Netzfehler; eingetragen wird nur, was wirklich auf `PAUSED` ging.
  - **Claudes Wahl – `clientFor(baseUrl)` bleibt für Cover der sichtbaren Seite.** Geprüft gegen den lokalen ABS: Die Cover-Route antwortet
    ohne Token mit 200, `/api/items/<id>` mit 401. Es geht dabei also nichts Vertrauliches über das Konto hinaus; Task 2 baut die Seiten beim Wechsel ab.
- **1.6 Abmelden auf anderem Server.** `ServerLogout.logoutProfile`: aktuelle Verbindung, wenn sie auf dem Server des Profils ist, sonst
  `AddressResolver.resolveElsewhere` (schreibt nicht ins Protokoll des Verbindungsfensters), alles innerhalb des Zeitlimits. `logout` fürs Abmelden bleibt
  unverändert, ohne Probe. Scheitert das, läuft die Entfernung durch.
  - **Claudes Wahl – eigener Client:** `unauthenticatedClient(account)` erlaubt `http://` nur zu den für dieses Konto bestätigten Servern. Der Wächter
    des angemeldeten Kontos bleibt, wie er war. Der erste Versuch (Wächter um gespeicherte Konten erweitern) scheiterte im `ProfileSwitchFlowTest`:
    `forget` läuft vor dem Logout, das Konto steht dann nicht mehr in der Liste.

### 1.7 Durchsicht der Singletons

| Klasse | Befund |
|---|---|
| `PlaybackService` | **Geändert:** Stream über den Client des Kontos, das die Verbindung gesehen hat. `lastOnline` wird beim Kontowechsel schon heute verworfen. |
| `watchLibrary` / Android Auto | **Geändert:** nach `carLibraryChanges` ausgelagert, Test. Das Verhalten war schon richtig: `notifyChildrenChanged` kommt nach dem Wechsel, auch bei gleich aussehenden Listen. |
| `CarLibrary`, `CarSearch`, `CarStarts`, `LibraryCallback` | Nichts zu tun: kein Cache, der Kontext wird je Aufruf gelesen; fremde Ordner-IDs liefern für B keine Zeilen. |
| Widget (`WidgetAccountWatcher`) | **Geändert:** zeichnet bei jedem Kontowechsel neu, auch wenn nichts gespeichert ist; sonst fehlte der letzte Titel nach dem Zurückwechseln. |
| `WidgetSource`, `WidgetStore`, `WidgetPublisher`, `WidgetCoverCache` | Nichts zu tun: Schlüssel je Server; ein Titel, den der Publisher spät schreibt, wird beim fremden Konto ausgeblendet (`forDisplay`). |
| `SpeedMemory`, `PlaybackComfort` | Nichts zu tun: Schlüssel je Titel mit Server-ID, späte Speicherungen gehen auf den eigenen Schlüssel. |
| `AutoResume` (`LastTitleStarter`, `AutoResumer`) | **Geändert:** liest `forAccount`. Der Rest fragt je Aufruf das aktive Konto. |
| `CoverStore` / `AutoModule` | **Geändert:** Abruf über `clientFor(serverId, …)`, kein Rennen mehr zwischen Prüfung und Client. Cache und Fehlversuche je Server. |
| `NextVolumePrefetcher` | **Geändert:** Prüft nach dem Lesen des Kontos, ob der Titel noch im Player ist; vorher bekam B den nächsten Band (Test). Ein angezeigter Titel über 80 % kann den nächsten Band auslösen, das ist die normale Regel. |
| Erfolge, Statistik (`AchievementNoticeViewModel`, `AchievementSeenDao`, `ListeningDataSource`, `ListeningSessionSync`) | Nichts zu tun außer der API je Konto: alles nach Server-ID, `flatMapLatest` und Stempel je Konto. |
| `PlayerControllerImpl` | **Geändert:** `showPaused`, und `play` startet keinen Server-Titel eines anderen Kontos (Seite, die den Wechsel überlebt hat). |
| `DownloadPrompts` / `AppViewModel` | **Geändert:** Die Frage wird nur ihrem Konto gestellt, auch wenn sie nach dem Wechsel eintrifft. |
| `FinishedQuestions` | **Geändert** (ViewModel): Die Frage „Download löschen?“ von A erscheint nicht bei B; sie wartet auf A. |
| `PositionJumpBus` | **Geändert:** Sprünge eines Syncs, der nach dem Wechsel endet, werden verworfen; offene gehen mit dem Konto. |
| `ConnectionLog` | **Geändert:** wird beim Verlassen geleert, sonst stünden A's Adressen in B's Fenster (auch beim Abmelden, kleine Verbesserung). |
| `DiagnosticSecrets` | **Geändert:** Hosts und Namen gespeicherter Konten bleiben maskiert. RMAB nur des aktiven Kontos; das Log wird beim Wechsel ohnehin geleert. |
| `RmabRepository`, `RecentSearches`, `LastPlayedStore` | **Geändert:** siehe 1.1, 1.2. `RmabViewModel` fragt bei einer anderen Verbindung neu. |
| `AuthorInfoCache` | **Geändert:** API je Konto. Schlüssel hatten die Server-ID schon. |
| `ProgressActions`, `ListeningSessionSync` | **Geändert:** API je Konto. |
| `SleepTimer`, `StreamMonitor`, `TitleHandover` | Nichts zu tun: Stop beendet den Timer, der Monitor geht auf inaktiv; `TitleHandover` schützt nur (ein Schlüssel zu viel löscht nichts). |
| `FinishedDownloadCleaner`, `FinishedSeenStore`, `AutoDeleteAccountState`, `KeptAccounts`, `KeptDownloadSizes`, `AccountDataJanitor`, `VolumePurges`, `SyncStamps`, `ExternalSeriesRepository` | Nichts zu tun: alles je Server; gespeicherte Konten gelten als angemeldet. |
| `DataStoreAccountStore`, `AuthInterceptor`, `TokenAuthenticator`, `OidcLogin`, `MediaControllerFacade`, `SyncScheduler`, `VoicePlayer`, `DeviceInfoProvider`, `OkHttpReachabilityProbe`, `AudibleCatalog`, `LocalFolders`, `SafDocumentTree`, `DownloadRoots`, `StorageVolumes`, `AndroidNetworkInfo`, `DefaultDownloadManager`, `PlayDownloadStarter` | Nichts zu tun: bereits gebunden, je Aufruf oder ohne Konto. |
| `AppSettings`, `DownloadSettings`, `PlaybackSettings`, `UiSettings`, `FilterPreferences`, `DiscoverPreferences` | **Claudes Wahl: bleiben Geräte-Einstellungen**, außer dem, was schon je Server ist (Aufräumen, Merker). Ändern wäre eine eigene Entscheidung. |

**Tests:** 106 neue. `ProfileSwitchFlowTest` (8), `AccountBoundSyncTest` (17, je Sync-Klasse und Worker), `SignOutTest` (+13), `RmabRepositoryTest` (+9),
`RecentSearchesTest` (7), `ServerLogoutTest` (+7), `SwitchedDownloadsTest` (6), `PlayerControllerTest` (+7), `AccountStateCleanerTest` (+7), `LastPlayedStoreTest` (5), weitere
in den Tests der jeweiligen Klassen. Alle Verhaltenstests sind vorher rot gelaufen; Ausnahme ist `CarLibraryChangesTest`, der vorhandenes Verhalten festhält. Der Flow-Test
selbst lässt sich auf dem alten Stand nicht kompilieren, darum mit vier Mutationen geprüft (API nicht gebunden, Wechsel löscht wie Abmelden, kein Sichern, kein Fortsetzen): jede macht ihn rot.

**Emulator 5554:** Update über die installierte 0.4.0: installiert, startet, Konto bleibt. Suche „Der“ steht weiter unter „Letzte Suchen“ (Schlüssel `queries:<id>`), der letzte Titel ist
umgezogen (`item_id:<id>`), und die Medientaste startet ihn nach `force-stop`. Das RMAB-Update nur per Test, weil dort keine Verbindung eingerichtet war. Streamen war zunächst offen,
weil die Sitzung abgelaufen war; es ist in Fix-Runde 1 geprüft (siehe dort).

### Was Task 2 wissen muss

- `AppNavHost` speichert Tab-Stapel (`saveState`/`restoreState`, Zeile 244–246): Beim Wechsel dürfen sie nicht wiederhergestellt werden, sonst leben A's ViewModels bei B weiter. Am einfachsten: `NavHost` nach `serverId` schlüsseln.
- Liest das Konto nur einmal und überlebt den Wechsel also nicht richtig: `YearReviewViewModel.builds()`, `SetupViewModel` (Zeile 85).
- `showPaused`: Der Player hat nichts geladen. Spulen und Kapitelsprung tun nichts, bis Play gedrückt wird; das gilt heute schon nach einem beendeten Dienst. Der Mini-Player erscheint kurz nach dem Wechsel (Room-Abfrage).
- Ein Wechsel kann im schlimmsten Fall rund 20 s dauern: je bis zu 5 s Sichern, `work.cancel` und zweimal Stoppen. Die Anzeige „Wechsle zu …“ muss das aushalten.
- `addProfile` wirft, wenn das neue Konto nicht gespeichert werden kann; `switchProfile` liefert `false`.

### Fix-Runde 1 nach Review (2026-11-05)

Alle Befunde des Reviews übernommen. Neue Tests liefen vorher rot; `anOpenDownloadQuestion_goesWithItsAccount_…` hält vorhandenes Verhalten fest.

- **I1 – Ein Titel rund um den Wechsel überlebt ihn nicht.** Bisher konnte zwischen dem Stopp und dem Kontowechsel ein Titel von A starten (Medientaste,
  Auto, Widget, Tipp auf die alte Seite). Er lief bei B weiter, und sein Stream ging mit B's Token hinaus. Jetzt:
  - `leave` stoppt nach dem Kontowechsel noch einmal (`stopAgain`), bevor das nächste Konto ankommt. Das schließt auch dieselbe Lücke beim Abmelden und bricht einen laufenden `play`-Job ab.
  - `AccountPlaybackGuard` im `PlaybackService`: Beim Kontowechsel stoppt und leert er den Player, wenn der Titel nicht dem neuen Konto gehört, und
    zwar bevor der Client des nächsten Kontos gilt. Die erste Antwort beim Dienststart und eine Anmeldung stoppen nichts.
  - **Claudes Wahl:** Auch ein Gerätetitel aus der Lücke geht, B startet still.
  - **Claudes Wahl – zusätzlich:** `titleChanged` stoppt bei jedem Titelwechsel einen Server-Titel, der nicht dem angemeldeten Konto gehört. Das fängt
    die übrigen Wege ab (nächster Titel der Warteschlange, ein Auto-Start, der knapp vor dem Wechsel fertig wurde). Ein Gerätetitel auf diesem späten Weg bleibt, dabei geht kein Token hinaus.
  - `CarStarts.start` prüft das Konto nach `resolveTitle`: Hat es gewechselt, startet nichts, auch kein Gerätetitel. `PlayerControllerImpl.adopt` übernimmt keinen Server-Titel eines anderen Kontos.
- **I2 – Der zuletzt gespielte Gerätetitel gilt je Konto.** Bisher nahm ein Gerätetitel von A den letzten Titel von B ein: kein Mini-Player für B, und B's Medientaste startete A's Titel.
  - `set(last, account)` schreibt unter `item_id:device:<konto>`; der Dienst gibt das Konto des Moments mit, ein später Schreibvorgang bleibt also bei A.
  - `forAccount` = neuester eigener Titel, sonst der gemeinsame Eintrag `item_id:device`. Der stammt aus der Zeit vor dem ersten neuen Schreiben, aus der Zeit ohne Anmeldung oder von einem abgemeldeten Konto. Das nächste Spielen beendet ihn.
  - **Claudes Wahl – Abmelden wie in 0.4.0:** `forget` gibt den Gerätetitel nur dann ans Gerät zurück, wenn er der letzte Titel des Kontos war; war es ein Server-Titel, bleibt nichts zum Fortsetzen. Ein einzelnes Konto merkt also nichts.
  - Widget: Ein Wechsel entfernt auch einen gespeicherten Gerätetitel (`clearUnlessOf`), `WidgetPublisher.forgetTitle` verhindert, dass der alte Titel zurückgeschrieben wird. Abmelden und Anmelden derselben Person behalten ihn wie bisher (seit Fix-Runde 2: bei einer anderen Person geht er auch mit einer Abmeldung dazwischen).
- **M1** Der Merker wird vor `work.cancel` geschrieben und vor dem Pausieren noch einmal (**Claudes Wahl:** `cancel` kann Sekunden dauern, was in der Zeit dazukommt, soll nicht verloren gehen). `resume` reiht auch vermerkte Zeilen in `QUEUED` und `RUNNING` ein; `KEEP` lässt laufende Arbeit in Ruhe.
- **M2** Siehe oben (Download-Worker). `setStateIf` liefert dafür die Zahl der geänderten Zeilen, keine Schemaänderung.
- **M3** `ProfileSwitchFlowTest` baut `SignOut` über den `@Inject`-Konstruktor. `FakeFacade.onPause` speichert die Position 150 ms nach der Pause, wie der Tracker. Mutationen geprüft: ohne die 300 ms Wartezeit wird er rot, ebenso ohne `stopAgain` und ohne den eigenen letzten Titel im Mini-Player.
- **M4** Testname und Kommentare korrigiert, die Frage von A bleibt nun geprüft für A's Rückkehr stehen.
- **M5** `forget` leert auch die Cover-Caches im RAM und auf der Platte; das aktive Konto lädt seine Cover neu.
- **M6** Jeder Stopp in der Kontosperre ist auf `STOP_TIMEOUT_MS` (5 s) begrenzt.

**Tests:** App 1886, abs-api 48, core:model 3, core:database 66, keine Fehler; Lint ohne neue Meldung in diesen Klassen.

**Emulator 5554 (Build 4d1e649, angemeldet als root):** „Die Mitte“ nach Löschen des Downloads gestreamt, ohne Fehler (`PLAYING`, Download beim Abspielen lief mit). Ein Gerätetitel steht unter `item_id:device:<id>`. Medientaste mit sichtbarer App spielt, der Guard griff nirgends fälschlich ein.

**Neuer Befund, nicht behoben:** Die Medientaste bei kalt gestartetem Dienst im Hintergrund lädt den Titel, spielt aber nicht: Android 15 lehnt die Audio-Fokus-Anfrage ab (`AS.HardeningEnforcer: Focus request DENIED`), weil der Dienst noch nicht im Vordergrund ist; danach `playWhenReady=false reason=2`. Der Stand vor dieser Runde (942b4cf) zeigt das genauso (je 3 von 3), die Ursache liegt also nicht in dieser Runde.

### Fix-Runde 2 nach Re-Review (2026-11-05)

Fünf kleine Befunde, alle übernommen, weil die Vorgabe wörtlich gilt: „keine Anfrage von Konto A mit dem Token von Konto B“. Neue Tests liefen vorher rot, bis auf Gegenproben (siehe Bericht).

- **N1 – Der Stream gehört dem Konto des Titels.** Bisher stoppte der Guard einen spät gelandeten Titel erst danach; seine erste Anfrage konnte B's Token tragen.
  - Die Stream-URL nennt den Besitzer im Fragment (`…/download#<serverId>`, `MediaItemFactory`). `StreamCalls` (aus dem `PlaybackService` herausgelöst) schickt über den Client dieses Kontos; `AuthInterceptor` lehnt ab, solange ein anderes angemeldet ist. Ohne Besitzer oder auf fremder Adresse: Client ohne Token.
  - **Vorher geprüft:** Media3s `OkHttpDataSource` behält das Fragment, auf der Leitung erscheint es nicht (`StreamCallsTest`, durch die echte Datenquelle bis zum Server). URIs zweier Aufbauten sind gleich, `syncSources` lädt also nichts neu (`MediaItemFactoryTest`). Der Gang durch den `MediaController` zum Dienst: Emulator; ohne Fragment käme ein 401.
  - `ServiceTitleStarter.start` fragt nach `resolve` noch einmal, ob das Konto noch da ist. Das schließt auch den Gerätetitel als nächsten Eintrag der Warteschlange und den Download beim Abspielen ein.
  - `QueueAdvancer`: Ein Eintrag geht erst, wenn über seinen Start entschieden ist. **Claudes Wahl:** Scheitert der Start, wird er wie bisher verworfen, außer das Konto ist inzwischen weg; dann bleibt er für die Rückkehr, und nichts weiter wird versucht. Nebenwirkung: Die Warteschlange schützt den Titel während des Starts vor dem automatischen Aufräumen.
  - `CarStarts` liest das Konto einmal beim Eintritt und vergleicht nach `resolveTitle` damit.
- **N2** `PlayerControllerImpl.stop` setzt den leeren Zustand in `finally`: Ein hängender oder scheiternder Stopp lässt keinen Titel im Mini-Player.
- **N3 – Der Geräte-Eintrag gibt nichts an andere weiter.**
  - `removeProfile` nutzt `LastPlayedStore.drop`: alle Einträge des Profils gehen, nichts wird übergeben.
  - `adoptDeviceEntry`: Der gemeinsame Eintrag wird beim App-Start und beim Beiseitelegen zum Gerätetitel des aktiven Kontos, wenn es keinen eigenen hat. Der Weg läuft über `AccountStateCleaner.migrate`. `clear` tut das nicht: Dort hat die Abmeldung den Titel gerade für die nächste Person hinterlegt.
  - `WidgetAccountWatcher` und `AccountPlaybackGuard` vergleichen mit dem zuletzt angemeldeten Konto: A → abgemeldet → A behält den Gerätetitel, A → abgemeldet → B verliert ihn. Der Vergleich gilt je Prozess; nach einem Neustart im abgemeldeten Zustand ist die vorige Person unbekannt.
  - Abmelden ohne weiteres Konto bleibt, wie es war.
- **N4** Die Prüfung im Flow-Test sagt jetzt, was sie zeigt (Details des Titels, nicht der Stream); den Stream prüft `StreamCallsTest`. **Der Gerätetest mit einem echten Wechsel zieht in den Durchlauf von Task 2:** Wechsel, während ein Titel von A startet, dann im Server-Log B's Token auf A's Pfaden suchen.
- **N5** Nach dem letzten Stopp löscht `PURGE` die Zeilen noch einmal: Die letzte Speicherung eines Titels, den erst dieser Stopp beendet hat, bleibt nicht zurück.

**Tests:** App 1916, abs-api 48, core:model 3, core:database 66, keine Fehler; 31 neue Testfälle in dieser Runde.

**Emulator 5554 (Build 70858bc, root):** „Der Anfang“ nach Löschen des Downloads gestreamt: `PLAYING`, Server-Log „User root requested download“ ohne Fehler, keine `AccountChangedException`.

**Noch offen:** die Medientaste im Hintergrund unter Android 15 (Fix-Runde 1, neuer Befund); sie braucht eine eigene Entscheidung.

## Umsetzung Task 2 (2026-11-05)

Branch `p7-profiles`, Version 0.5.0 / versionCode 5, keine Schemaänderung (Room bleibt v11). Nur eine neue Vorgabe am Store: `AccountStore.savedNeedingReauth`.

- **Abbau der Seiten (2.2, Review Focus 1).** `AccountPages` (`ui/nav`): Je Konto ein eigener `NavController` unter `key(owner)`, und die ViewModels seiner Seiten in einem eigenen `ViewModelStore` (`AccountPagesScope`, lebt wie die Activity-ViewModels über das Drehen hinweg). Kommt ein anderes Konto, sind in einem Schritt weg: die Seite auf dem Schirm, ihr gemerkter Zustand, die weggespeicherten Tabs und die ViewModels aller Seiten (die der Player-Ebene sind aktivitätsweit, siehe Fix-Runde 1, M3). Nur nach dem Konto zu schlüsseln hätte nicht gereicht: Die Navigation hält die Stores ihrer Einträge in dem ihr gegebenen Store, A's ViewModels lebten bis zum Ende der Activity weiter (Mutation im Test: rot). Der alte Controller wird vom Lebenszyklus der Activity gelöst.
  - `MainViewModel.pages` sagt, wessen Seiten es sind und wo sie beginnen (`AppPages`), vom ersten lokalen Lesen an; ein Wechsel, ein Abmelden und eine erste Anmeldung bedeuten neue Seiten. `MainActivity` sammelt das ohne Lebenszyklus, damit das erste Bild nach einer Pause schon dem aktiven Konto gehört. `showSignIn` ist damit weg.
  - **`YearReviewViewModel.builds()` und die Vorbelegung in `SetupViewModel` lesen das Konto weiter nur einmal.** Beide sind Seiten-ViewModels und enden mit dem Konto; der erste Wert ist also immer der richtige (`AccountPagesTest`, `reauth_afterASwitch_…`). Ein Formular, das beim Kontowechsel verschwindet, startet seinen Sync trotzdem (`NonCancellable`, Test).
  - **Claudes Wahl – kein Animieren:** Die neuen Seiten sind einfach da; eine Überblendung hieße, A's Seite noch 700 ms laufen zu lassen.
- **„Wechsle zu …“ (2.2).** `ProfileOperations` (Singleton, eigener Scope, nicht der Main-Thread): Wechsel und Entfernen laufen für den ganzen Prozess, eins nach dem anderen; `running` und `outcome` sind für jeden Bildschirm da, wie oft er auch neu entsteht.
  - `ProfileBusyDialog` liegt außerhalb der Seiten, nimmt weder Eingabe noch „Zurück“ an und bleibt über den Moment des Wechsels stehen. Nach 5 s sagt eine zweite Zeile, dass es länger dauert (Live-Region).
  - Die Snackbar nennt das neue Profil. **Claudes Wahl:** Als erledigt gilt sie erst, wenn sie zu Ende gezeigt ist; beim Drehen in diesem Moment erscheint sie lieber zweimal, als dass sie verloren geht.
  - Auf einen laufenden Wechsel setzt nichts auf: `switchTo`/`remove` lehnen ab, `AccountSectionViewModel.startSignOut` auch; `add` wartet, weil die Anmeldung schon geschehen ist.
- **2.1 Blatt, Zeile, Avatar.** `ProfilesViewModel`, `ProfilesSheet`, gehostet in `AppRoot`, damit Einstellungen und Startseite dasselbe öffnen. Der Offen-Zustand überlebt das Drehen.
  - **Claudes Wahl – die Kontozeile wird zur Profilzeile:** „Profil: Name · Server“, darunter „Admin · Wechseln oder hinzufügen“, und sie öffnet das Blatt. Eine neue Zeile neben der alten hätte dasselbe zweimal gesagt.
  - **Claudes Wahl – feste Reihenfolge:** alphabetisch, nicht nach aktivem Profil; sonst springen die Zeilen bei jedem Wechsel. Tippen auf das aktive Profil schließt nur das Blatt.
  - Avatar: `placeholderToneFor(serverId)`, Initialen für TalkBack ausgeblendet. Der Hinweis „Neu anmelden nötig“ gilt je Profil (`savedNeedingReauth`).
  - **Stelle für Task 3:** `ProfilesViewModel.throughGate`. Wechsel, Picker und Entfernen gehen alle dort durch; sie tut noch nichts.
- **2.3 Profil hinzufügen (Review Focus 4, 5).** `Setup` ist jetzt `data class Setup(mode: SetupMode)`, das `SetupViewModel` liest den Modus aus der Route (`setupMode`). Adressen des aktiven Kontos stehen im Formular, eine schon bestätigte `http://`-Adresse wird nicht erneut erfragt.
  - **SSO:** Der Modus steht zusätzlich im gespeicherten Versuch (`OidcFlowContext.addProfile`) und entscheidet beim Rücksprung, egal wie das Formular zurückkam. Kommt die Antwort, und kein Formular ist da (Prozess-Tod ohne gespeicherte Seite), öffnet `SsoReturnViewModel` eines; ein fremder Link öffnet nichts.
  - **Claudes Wahl:** `complete` geht über `ProfileOperations.add`: gleiche Anzeige wie ein Wechsel, und das Formular darf dabei verschwinden. Schlägt das Speichern fehl, zeigt das Formular `SAVE_FAILED`, das aktive Konto bleibt.
  - **Claudes Wahl:** Aus dem Picker (kein aktives Konto) kommen die Adressen vom ersten gespeicherten Profil; die WLAN-Bindung kommt mit den Adressen mit.
  - **Claudes Wahl – abgelaufenes Konto:** Der Hinweis im Blatt und das vorhandene Band „Erneut anmelden“ nach der Rückkehr, das Formular ist mit genau diesem Konto vorbelegt. Es öffnet sich nicht von selbst: Offline hörbare Titel bleiben nutzbar, wie überall in der App.
- **2.4 Entfernen.** Dialog mit Größe der Downloads. **Claudes Wahl – zusätzlich:** Er nennt auch, was verloren geht und noch nicht auf dem Server ist, und die fehlende SD-Karte, wie der Abmelde-Dialog. Vor dem Lesen dieser Angaben lässt er sich nicht bestätigen.
- **2.5 Picker.** `ProfilePickerScreen`, von `MainViewModel` als Start der Seiten ohne aktives Konto mit gespeicherten Profilen. **Claudes Wahl:** Entfernen geht auch dort; nach dem letzten kommt die Anmeldeseite.
- **2.6 Downloads.** Eine Zeile „Andere Profile auf diesem Gerät: …“. **Claudes Wahl:** Die Zahl steckt nicht in „belegt“; sie kommt dazu. Ein nicht lesbares Verzeichnis zählt als nichts.
- **2.7** `local_add_folder_hint` sagt „auf diesem Gerät“ (DE und EN). Die übrigen „Handy“-Texte meinen das Schütteln, das Auto oder stehen in alten Versionshinweisen und bleiben.
- **2.8** 0.5.0, `Release(5, …)`. Task 3 ergänzt seine Zeile in `whatsnew_0_5_0`.
- **2.9 Wizard.** `ui/welcome`, drei Seiten; die mittlere sagt klar, dass ein eigener Audiobookshelf-Server nötig ist. Die Seite steht im `SavedStateHandle` der Route, „gesehen“ in `UiSettings` erst am Ende oder beim Überspringen.
  - `welcomeIsDue`: Nicht gesehen, und kein Zeichen einer älteren Version: weder meldet Android ein Update, noch ist die gespeicherte „Was ist neu“-Nummer älter als die laufende. Die Nummer der laufenden Version zählt nicht, sie steht schon nach dem ersten Start.
  - **Claudes Wahl:** Nur beim Start des Prozesses gefragt, nur ohne Konto und ohne gespeichertes Profil. Kein Wischen: Knöpfe und die Zurück-Taste, das sind weniger Zustände und für TalkBack klarer. Ein Fehler beim Merken hält nicht von der Anmeldung ab.

**Tests:** App 2035 (119 mehr als zuvor; `SignOutNavigationTest` mit seinen 3 ging in `AccountPagesTest` auf), abs-api 48, core:model 3, core:database 66, keine Fehler. Neu: `AccountPagesTest` (12), `ProfileOperationsTest` (13), `ProfilesViewModelTest` (17), `ProfilesScreensTest` (19), `SetupRouteModeTest` (3), `SetupScreenTest` (3), `SsoReturnViewModelTest` (3), `WelcomeViewModelTest` (6), `WelcomeScreenTest` (5); dazu in `SetupViewModelTest` (+16), `MainViewModelTest` (+13), `ProfileSwitchFlowTest` (+1: was die Bildschirme durch einen echten Wechsel sehen) und weiteren. Wo Test und Code im selben Schritt entstanden, zeigen Mutationen, dass die Tests greifen (Bericht).

**Emulator 5554:** Update über die installierte 0.4.0, angemeldet als root: kein Wizard, „Was ist neu“ 0.5.0, Startseite unverändert, Profilzeile. Blatt und Formular Handy hoch, quer und breit (1600×1000), deutsch und englisch. Das Formular überlebt das Drehen und einen echten Prozess-Tod im Modus, „Zurück“ führt in die Einstellungen. Saubere Installation: Wizard deutsch und englisch, Handy hoch und quer, breit, Seite überlebt Drehen und Prozess-Tod, unbeendet erscheint er wieder, nach dem Überspringen und nach der letzten Seite nicht mehr.

**Noch offen, im Gerätedurchlauf mit zwei Benutzern:** alles, was ein zweites angemeldetes Konto braucht. Liste im Bericht.

### Was Task 3 wissen muss

- Das Tor gehört in `ProfilesViewModel.throughGate`; es ist heute synchron und muss für eine Abfrage asynchron werden. Blatt und Picker benutzen dasselbe ViewModel.
- `SetupMode` bekommt `UNLOCK`, `Setup` dazu eine `serverId`, und `OidcFlowContext` muss den Modus für den SSO-Rücksprung mitführen wie heute `addProfile`.
- `ProfileOperations` startet nichts, solange etwas läuft: Die Abfrage kommt also vor `switchTo`/`remove`, nicht darin.
- `whatsnew_0_5_0` braucht noch die Zeile zum Schutz.

### Fix-Runde 1 nach Review und Gerätedurchlauf (2026-11-06)

Alle Befunde übernommen. Der Gerätedurchlauf mit zwei Benutzern lief vor dieser Runde sauber durch (Wechsel, Spielen, Downloads, Picker, Entfernen, breit und deutsch).

- **I2 – Covers ohne Token.** Vorher ging eine Kachel von A's Seite nach dem Wechsel mit B's Token hinaus (im Test belegt: `Bearer token-of-b`). `GuardedCallFactory` nimmt jetzt den bewachten Client ohne Zugangsdaten; `http://` geht weiter nur zu bestätigten Servern, und `runBlocking` ist vom Coil-Weg weg.
  - **Vorher geprüft, an der Untergrenze:** Die App braucht Refresh-Tokens, also Audiobookshelf 2.26. In Tag `v2.26.0` (Commit `264ae928`) steht in `server/Auth.js` Zeile 21 `ignorePatterns` mit `/items/<id>/cover` und `/authors/<id>/image`, nur für GET. `Server.js` Zeile 313 lässt diese Wege vor dem Auth-Middleware durch, und `LibraryItemController.getCover` liest keinen Benutzer. Dazu 2.37.1 lokal: 200 ohne Token.
  - **Was Coil lädt:** Dateien, `/api/items/<id>/cover?ts=…` (`coverModel`, auch die Farbmuster) und Audibles Bilder (`volume.coverUrl`). Autorenbilder lädt die App heute nicht; die Route wäre ebenso offen. Auto-Cover und Download-Cover laufen nicht über Coil und bleiben beim Client ihres Kontos.
- **I1 – Live-Regionen auf Knoten, die schon da sind.** Compose sendet das Inhalts-Ereignis mit den neuen Worten nur für einen Knoten, der schon im Accessibility-Baum war.
  - Wizard: ein Knoten für alle Seiten um den `Crossfade` herum (`clearAndSetSemantics`, Text und Überschrift).
  - Anmeldefehler und Hinweis „dauert länger“: `AnnouncedLine`, immer da, ohne Text ein 1-dp-Streifen ohne Worte. **Geprüft:** Ohne Größe ist er nicht im Baum und bekommt das Ereignis nicht (Mutation rot).
  - `LiveRegionEventsTest` liest die echten Ereignisse mit eingeschaltetem Screenreader-Zustand; die Tests halten die Knoten-ID über die Änderung fest.
  - **Grenze:** Wie jeder Knoten ist die Zeile nur im Baum, solange sie auf dem Schirm liegt. Der Anmeldefehler steht direkt über dem Knopf, also dort, wo die Person gerade ist.
- **M1** `OidcSignIn.pendingAddsProfile`: `SsoReturnViewModel` öffnet das Formular im Modus des Versuchs, und scheitert der Versuch, addiert auch ein schlichtes Formular beim zweiten Anlauf (Test: A bleibt angemeldet). Ein Formular, das addiert, bleibt dabei.
- **M2** `AccountPages` hält den Zustand je Konto in einem `SaveableStateHolder` und entfernt den eines Kontos, das nicht mehr aktiv ist. Der Test macht die Schritte von Hand, denn der `StateRestorationTester` kann das Konto nicht zwischen Sichern und Wiederherstellen ändern.
- **M3** KDoc und Bericht korrigiert: `PlayerViewModel`, `ItemActionsViewModel`, `ComfortViewModel`, `TitleExtrasViewModel` und die von `AppRoot` sind aktivitätsweit; jedes folgt dem Konto selbst.
- **M4** Der Entfernen-Dialog scrollt. **M5** Die Wizard-Knöpfe umbrechen als Ganzes (`FlowRow`); der Test braucht `GraphicsMode.NATIVE`, sonst stimmen die Textbreiten nicht und er bleibt grün. **M6** Die Entfernen-Frage geht, wenn das aktive Konto wechselt. **M7** Ein gescheiterter Wechsel sagt nichts mehr darüber, wer aktiv bleibt. **M8** `distinctUntilChanged` in `otherProfilesBytes`.
- **M9** `AppNavHost` nimmt eine `AccountNavigation` und hostet über `AccountNavHost`, der den Store stellt: `AppRoot` kann ihn nicht weglassen, und ohne ihn wird `AccountPagesTest` rot, der jetzt durch denselben Host läuft. `aProcessDeath_…` prüft die neuen ViewModels. `addProfile_back_…` gibt es jetzt zweimal, auch während einer laufenden Anmeldung. `AppRoot` selbst hat weiter keinen Test (Lage von `ProfileBusyDialog`, `profilesOpen`).
- **Aus dem Gerätedurchlauf:** Das Formular aus „Wer hört zu?“ sagt nicht mehr, das bisherige Konto bleibe angemeldet (`setup_add_intro_nobody`, DE und EN).
- **F1 (Task 1)** Ein Start, der wirft, gilt als nicht gestartet, unter derselben `stillOwner`-Regel: Der Eintrag geht und der nächste wird versucht, oder er bleibt, wenn das Konto inzwischen weg ist. Eine Abbruch-Ausnahme bleibt eine.
- **F2 (Task 1)** `adoptDeviceEntry` übernimmt nur, wenn das Konto gar keinen eigenen Titel hat. Die Tests spielen jede Geschichte mit und ohne Neustart und vergleichen das Ergebnis.

**Tests:** App 2070 (35 mehr), abs-api 48, core:model 3, core:database 66, keine Fehler; Lint ohne neue Meldung. Der bekannte Lint-Absturz an `ApiStubs.kt` kam zweimal hintereinander, danach lief es durch.

**Emulator 5554 (root und kind angemeldet, kind aktiv):** Cover-Cache geleert, die Cover kommen ohne Token neu vom lokalen Server. Formular-Fehler sichtbar, Entfernen-Dialog hoch und quer mit Abbrechen. **Nur per Test, nicht auf dem Gerät:** Wizard (bräuchte eine saubere Installation), Text im Formular aus dem Picker (bräuchte ein Abmelden), echte TalkBack-Ansage (der Versuch brachte keine Beobachtung, siehe Bericht).
