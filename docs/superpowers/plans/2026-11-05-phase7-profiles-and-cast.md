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
  - Widget: Ein Wechsel entfernt auch einen gespeicherten Gerätetitel (`clearUnlessOf`), `WidgetPublisher.forgetTitle` verhindert, dass der alte Titel zurückgeschrieben wird. Abmelden und Anmelden behalten ihn wie bisher.
- **M1** Der Merker wird vor `work.cancel` geschrieben und vor dem Pausieren noch einmal (**Claudes Wahl:** `cancel` kann Sekunden dauern, was in der Zeit dazukommt, soll nicht verloren gehen). `resume` reiht auch vermerkte Zeilen in `QUEUED` und `RUNNING` ein; `KEEP` lässt laufende Arbeit in Ruhe.
- **M2** Siehe oben (Download-Worker). `setStateIf` liefert dafür die Zahl der geänderten Zeilen, keine Schemaänderung.
- **M3** `ProfileSwitchFlowTest` baut `SignOut` über den `@Inject`-Konstruktor. `FakeFacade.onPause` speichert die Position 150 ms nach der Pause, wie der Tracker. Mutationen geprüft: ohne die 300 ms Wartezeit wird er rot, ebenso ohne `stopAgain` und ohne den eigenen letzten Titel im Mini-Player.
- **M4** Testname und Kommentare korrigiert, die Frage von A bleibt nun geprüft für A's Rückkehr stehen.
- **M5** `forget` leert auch die Cover-Caches im RAM und auf der Platte; das aktive Konto lädt seine Cover neu.
- **M6** Jeder Stopp in der Kontosperre ist auf `STOP_TIMEOUT_MS` (5 s) begrenzt.

**Tests:** App 1886, abs-api 48, core:model 3, core:database 66, keine Fehler; Lint ohne neue Meldung in diesen Klassen.

**Emulator 5554 (Build 4d1e649, angemeldet als root):** „Die Mitte“ nach Löschen des Downloads gestreamt, ohne Fehler (`PLAYING`, Download beim Abspielen lief mit). Ein Gerätetitel steht unter `item_id:device:<id>`. Medientaste mit sichtbarer App spielt, der Guard griff nirgends fälschlich ein.

**Neuer Befund, nicht behoben:** Die Medientaste bei kalt gestartetem Dienst im Hintergrund lädt den Titel, spielt aber nicht: Android 15 lehnt die Audio-Fokus-Anfrage ab (`AS.HardeningEnforcer: Focus request DENIED`), weil der Dienst noch nicht im Vordergrund ist; danach `playWhenReady=false reason=2`. Der Stand vor dieser Runde (942b4cf) zeigt das genauso (je 3 von 3), die Ursache liegt also nicht in dieser Runde.
