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
