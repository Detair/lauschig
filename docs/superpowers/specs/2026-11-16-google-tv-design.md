# Design: Lauschig für Google TV

**Stand:** Entwurf vom 2026-11-16, von Fabian freigegeben am 2026-11-17. Umsetzungsplan: [`docs/superpowers/plans/2026-11-17-google-tv.md`](../plans/2026-11-17-google-tv.md).
Gehört zu: Design-Spec Abschnitt 2.8 „Später: TV-Modus“ ([`2026-10-31-lauschig-design.md`](2026-10-31-lauschig-design.md)).

## Ziel

Lauschig läuft auf Google TV und Android TV und lässt sich ganz mit der Fernbedienung bedienen.
Die erste Version ist zum **Hören auf dem Sofa** da: Profil wählen, weiterhören, stöbern, abspielen.
Eingerichtet wird der Fernseher **mit dem Handy**, Tippen bleibt als Notweg.

## Entscheidungen (Fabian, 2026-11-16)

1. **Umfang:** „Hören auf dem Sofa“. Downloads, Statistik, Erfolge und Jahresrückblick bleiben vorerst dem Handy.
2. **Einrichtung:** Die Übergabe vom Handy gehört schon zur ersten Version. Tippen mit der Fernbedienung bleibt als Notweg.
3. **Play Store:** Erst startet die Handy-App. Die Fernseher-Version kommt danach als eigener Schritt. Bis dahin installiert man sie als APK von GitHub.

## Eigene Entscheidungen (Claude; Fabian kann widersprechen)

| # | Entscheidung | Grund | Kosten, falls falsch |
|---|---|---|---|
| E1 | Eine App, eine APK. Der Fernseher bekommt eine eigene Startseite (`TvActivity`) in derselben App. | Ein Release, ein Schlüssel, dieselbe Datenbank und derselbe Sync. Die GitHub-APK läuft auf Handy und Fernseher. | Die APK enthält auf dem Fernseher ungenutzten Code (Kamera, Scanner). Ein eigener TV-Build ließe sich später abspalten. |
| E2 | Eigene TV-Oberfläche mit Compose for TV statt Umbau der Handy-Bildschirme. | Die Handy-Oberfläche hängt an Gesten, die es am Fernseher nicht gibt (siehe Ausgangslage). | Zwei Oberflächen zu pflegen. Deshalb bleibt die TV-Oberfläche dünn und nutzt die vorhandene Logik. |
| E3 | Der TV-Code liegt als Paket `tv/` im Modul `:app`, nicht in einem eigenen Modul. | Player, Sync und Konten liegen in `:app`. Ein eigenes Modul bräuchte erst den verschobenen Umbau `:core:playback`. | Etwas weniger saubere Grenzen. |
| E4 | Bei der Übergabe vom Handy wandert nie eine Sitzung des Handys auf den Fernseher. Der Fernseher meldet sich selbst an und bekommt eine eigene Sitzung. | Audiobookshelf erneuert Tokens je Sitzung. Zwei Geräte mit derselben Sitzung würden sich gegenseitig abmelden. | – |
| E5 | Die Übergabe läuft nur im Heimnetz, direkt zwischen Handy und Fernseher, ohne eigenen Server. | Spec, Nicht-Ziele: kein eigener Backend-Server. | In Netzen, die Geräte voneinander trennen (Gast-WLAN), bleibt nur Tippen. |
| E6 | Podcasts sind in der ersten TV-Version nicht dabei. | „Hören auf dem Sofa“ meint Hörbücher und Hörspiele. Die Folgenlisten wären ein eigener Bildschirm. | Podcast-Bibliotheken fehlen am Fernseher, bis ein späteres Paket sie bringt. |
| E7 | Die App hält den Bildschirm nie von sich aus wach. | Googles Regel TV-BA verbietet das bei reinem Ton. Es passt auch zum Wunsch „Bild aus“ aus der Spec. | – |

## Ausgangslage (geprüft am Code, 2026-11-16)

| Thema | Befund | Stelle |
|---|---|---|
| Start am Fernseher | Kein Eintrag für den TV-Startbildschirm, kein Banner. Touchscreen, WLAN und Standort gelten als Pflicht-Merkmale. | `app/src/main/AndroidManifest.xml` |
| Oberfläche | Ein `NavHost`, vier Tabs, rund 20 Seiten. Alles Material 3 fürs Handy, kein `androidx.tv`. | `ui/nav/`, `ui/AppScaffold.kt` |
| Fokus | Fast keine Fokus-Steuerung: drei Stellen mit `FocusRequester`, keine Tastenbehandlung in der Activity. | `ui/search/SearchScreen.kt`, `ui/profiles/PinEntry.kt` |
| Menüs | Das Titel-Menü öffnet nur langes Drücken. | `ui/common/CoverTile.kt:65` |
| Player | Kapitel und Tempo sind Schieberegler. Schlaf-Timer, Klang, Kapitel und Warteschlange liegen in Bottom-Sheets. | `ui/player/PlayerScreen.kt:335, 584`, `ComfortSheets.kt`, `ContentsSheet.kt` |
| Wiedergabe | Der Dienst ist eine Media3-Bibliothek mit Mediensitzung. Medientasten laufen schon darüber. | `playback/PlaybackService.kt` |
| Einrichtung | Zwei Adressen, Benutzer und Passwort werden getippt. SSO öffnet einen Browser. Der QR-Scan braucht eine Kamera. | `ui/setup/SetupScreen.kt:89, 193–229` |
| Geräte-Funktionen | Schütteln (Schlaf-Timer) und Biometrie prüfen schon, ob das Gerät sie hat. | `playback/SleepTimer.kt:41`, `ui/profiles/ProfileGate.kt:42` |
| Build | minSdk 28, eine Variante, keine eigenen nativen Bibliotheken. | `app/build.gradle.kts` |
| Tests | Oberflächen-Tests laufen mit Robolectric und Bildschirm-Vorgaben. Ein Fernseher lässt sich so nachstellen. | `app/src/test` |


## Was Google verlangt

Quelle: [TV app quality](https://developer.android.com/docs/quality-guidelines/tv-app-quality), Stand Mai 2026. Seitdem gibt es drei Stufen. Für den Play Store reicht Stufe 3 „TV Ready“.

| Regel | Forderung | Für Lauschig heißt das |
|---|---|---|
| TV-ML, TV-LM, TV-LB, TV-BN | Start-Eintrag für den Fernseher, Banner 320×180 mit dem App-Namen, Icon | Manifest ergänzen, Banner gestalten |
| TV-MT | Touchscreen und andere Hardware sind keine Pflicht | Touchscreen, WLAN, Standort, Kamera als „nicht nötig“ erklären |
| TV-DP, TV-DM, TV-DB | Alles geht mit Steuerkreuz und OK. Kein Menü-Knopf nötig. Zurück führt bis zum TV-Startbildschirm. | Fokus auf jeder Seite, kein langes Drücken als einziger Weg |
| TV-LO, TV-OV, TV-TR | Querformat, nichts am Rand abgeschnitten, deckender Hintergrund | Ränder von 5 % einhalten |
| TV-WB | Kein Browser wird gestartet | SSO am Fernseher nur über das Handy |
| TV-NP, TV-PA | Läuft Ton im Hintergrund weiter, zeigt das System eine Steuerung, die zum Pausieren führt | Mediensitzung ist da; ihr Ziel muss am Fernseher der TV-Player sein |
| TV-PC, TV-PP | OK pausiert und setzt fort, links und rechts spulen, Play/Pause-Taste wirkt | Tastenbelegung im TV-Player |
| TV-BA | Bei reinem Ton darf die App den Bildschirmschoner nicht verhindern | E7 |
| TV-PS | minSdk höchstens 31 | erfüllt (28) |
| TV-G6 | Seit 2026-08-01: 32 und 64 Bit, 16-KB-Seiten | An der APK 0.6.0 geprüft (2026-11-16): alle vier Architekturen sind drin, die nativen Teile sind auf 16 KB ausgerichtet. Ungeprüft: der innere Aufbau der vier fremden Bibliotheken. |
| TV-G1, TV-G4, TV-G5 | App-Bundle, echte TV-Screenshots, Test-Zugang für die Prüfer | Demo-Server gibt es schon |
| TV-ME | Speichergrenzen auf Geräten mit wenig RAM | Cover-Cache messen |

Stufe 2 nennt unter anderem die Anmeldung über das Handy (TV-LI), gespeicherte Anmeldung (TV-LC) und Sprachsuche (TV-VS). Die ersten beiden bringt dieser Plan mit.
Compose for TV: `androidx.tv:tv-material` 1.1.0, stabil seit 2026-05-06.

## Die TV-Oberfläche

| Seite | Inhalt | Bedienung |
|---|---|---|
| Wer hört? | Profile als große Kacheln, PIN über ein Ziffernfeld | Erste Seite nach dem Start, wenn es mehr als ein Profil gibt |
| Start | Reihen: Weiterhören, Zuletzt hinzugefügt, Serien, je Bibliothek | Links/rechts in der Reihe, hoch/runter zwischen Reihen; der Fokus merkt sich die Stelle |
| Bibliothek | Raster mit großen Covern, Sortierung | Seitenleiste links für Start, Bibliothek, Suche, Einstellungen |
| Serie | Bände in Reihenfolge, gehörte markiert | – |
| Titel | Cover, Beschreibung, Sprecher, Dauer; Knöpfe „Weiterhören“, „Von vorn“, „Kapitel“ | Ersetzt das Menü hinter dem langen Drücken |
| Suche | Textfeld mit der Tastatur des Fernsehers, Ergebnisse als Reihen | Die Sprach-Eingabe der Tastatur funktioniert ohne Zutun |
| Player | Großes Cover, Titel, Kapitel, Fortschritt | OK = Pause/Weiter, links/rechts = spulen, runter = Leiste mit Kapitel, Tempo, Schlaf-Timer |
| Kapitel | Liste in einer Seitenleiste | statt Bottom-Sheet |
| Tempo | Schritte mit − und + | statt Schieberegler |
| Schlaf-Timer | Feste Zeiten und „Ende des Kapitels“; beim Gong verlängert OK | ersetzt das Schütteln |
| Einstellungen | Profil wechseln, abmelden, Verbindungsstatus, Bild-aus-Zeit, Über und Lizenzen | bewusst kurz |

**Bild aus** (Spec 2.8): Nach einer einstellbaren Zeit ohne Tastendruck wird der Player schwarz, ohne Bewegung. Jede Taste holt ihn zurück. Der Bildschirmschoner und der Ruhezustand des Geräts bleiben erlaubt (E7). Was das Gerät bei langem Ton wirklich tut, klärt Paket T0.

**Nicht in der ersten Version:** Downloads und Offline-Hören, Statistik, Erfolge, Jahresrückblick, Podcasts (E6), Lesezeichen, Warteschlange, Klang-Einstellungen, Geräteordner, RMAB, Cast.

## Einrichtung mit dem Handy

**Ablauf für den Menschen:**

1. Der Fernseher zeigt „Mit dem Handy einrichten“: einen QR-Code und eine vierstellige Zahl.
2. Am Handy: Lauschig → Einstellungen → „Fernseher einrichten“. Die Kamera scannt den Code.
3. Das Handy zeigt, was passiert: „Diesen Fernseher (Zahl 4821) mit dem Server … und dem Konto … einrichten?“
4. Bei einem Konto mit Passwort tippt man das Passwort am Handy. Bei SSO öffnet das Handy den Browser.
5. Der Fernseher meldet sich an und zeigt die Startseite. Das Handy vergisst den Fernseher wieder.

**Wie es gebaut ist:**

| Teil | Lösung |
|---|---|
| QR-Code des Fernsehers | Adresse und Port des Fernsehers im Heimnetz, ein öffentlicher Einmal-Schlüssel, eine Kennung. Kein Konto, kein Server, kein Passwort. |
| Verschlüsselung | Hybride Verschlüsselung (HPKE) aus der Bibliothek Tink, die die App schon für Tokens nutzt. Kein selbst gebautes Verfahren. Geprüft am 2026-11-16: Die eingebaute Version 1.24.0 enthält HPKE. |
| Weg | Das Handy verbindet sich im Heimnetz direkt mit dem Fernseher und schickt genau ein verschlüsseltes Paket. |
| Inhalt bei Passwort | Server-Adressen, Benutzer, Passwort. Der Fernseher meldet sich damit selbst an und verwirft das Passwort. |
| Inhalt bei SSO | Der Fernseher beginnt die Anmeldung und schickt dem Handy die Login-Adresse. Das Handy macht den Browser-Teil und reicht den Einmal-Code zurück. Der Fernseher schließt ab. Ungeprüft: ob Audiobookshelf diese Aufteilung auf zwei Geräte mitmacht (Paket T0). |
| Notweg | Formular zum Tippen mit der Fernbedienung, ohne SSO und ohne Scan-Knopf. |

**Sicherheitsregeln:**

- Der Fernseher lauscht nur, solange die Seite offen ist, höchstens fünf Minuten, und nimmt genau ein Paket an.
- Das Handy sendet nur an eine Adresse im eigenen Heimnetz, nie ins Internet.
- Die Übergabe startet nur aus „Fernseher einrichten“ in der App. Kein Link von außen löst sie aus.
- Vor dem Senden zeigt das Handy Server, Konto und die Zahl vom Fernseher. Ist das Profil geschützt, fragt es zuerst die PIN.
- Größtes Risiko: Ein fremder QR-Code, der sich als Fernseher ausgibt, bekäme das Passwort. Dagegen stehen die Heimnetz-Grenze, die Bestätigung und die Zahl. Ganz ausschließen lässt es sich nicht.
- Weil es um Anmeldedaten geht, liest ein unabhängiger Prüfer (Fable) das Paket vor dem Merge.
- Ungeprüft: Android 17 verlangt für Verbindungen im Heimnetz eine eigene Berechtigung. Das betrifft das Handy schon heute beim lokalen Server.

## Pakete

Jedes Paket ist ein PR mit Tests. Reihenfolge wie nummeriert.

| # | Paket | Inhalt | Größe |
|---|---|---|---|
| T0 | Vorabklärung | Testgerät einrichten. Messen: Was tut das Gerät bei langem Ton (Bildschirmschoner, Ruhezustand, schaltet es den Fernseher ab)? Laufen Klang-Effekte? Geht SSO über zwei Geräte gegen den Test-Server? | klein, kein Merge |
| T1 | Grundgerüst | Manifest (TV-Start, Banner, Hardware nicht nötig), `TvActivity`, TV-Design, leere Startseite, Zurück verlässt die App. Die Mediensitzung führt am Fernseher zum TV-Player. Handy unverändert. | klein |
| T2 | Einrichtung zum Tippen | TV-Formular für Adressen, Benutzer, Passwort. Damit ist die App am Gerät testbar. | klein |
| T3 | Profile und Start | „Wer hört?“ mit PIN, Startseite mit Reihen, Fokus merkt sich die Stelle. | mittel |
| T4 | Stöbern | Bibliothek, Serie, Titel, Suche. | mittel |
| T5 | Player | Tastenbelegung, Kapitel, Tempo, Schlaf-Timer, Bild aus. | mittel |
| T6 | Einrichtung mit dem Handy | TV-Seite mit QR-Code, „Fernseher einrichten“ am Handy, Verschlüsselung, Passwort-Weg; danach der SSO-Weg, wenn T0 ihn bestätigt. Prüfung durch Fable. | groß |
| T7 | Einstellungen und Feinschliff | Kurze Einstellungen, Ränder, Texte DE/EN, Vorlesefunktion, Speicher auf schwachen Geräten. | mittel |
| T8 | Abnahme | Jede Regel der Stufe „TV Ready“ mit Nachweis abhaken, TV-Screenshots, Banner, Datenschutzerklärung (Übergabe im Heimnetz), Release-Notes. | klein |

Nach T5 ist die App am Fernseher benutzbar (mit Tippen). Nach T8 ist sie fertig für eine GitHub-Version. Der Play Store folgt nach dem Handy-Start in einem eigenen TV-Track.

## Tests

| Ebene | Wie |
|---|---|
| Logik | Unit-Tests wie bisher. Für T6: Paket-Format, falsche Schlüssel, zweites Paket, abgelaufene Zeit, Adresse außerhalb des Heimnetzes. |
| Oberfläche | Robolectric mit Fernseher-Vorgabe und Tastendrücken: Jede Seite ist nur mit Steuerkreuz und OK bedienbar, der Fokus geht nie verloren. |
| Gerät | Google-TV-Emulator (Android 14) während der Entwicklung. Echte Geräte prüft ein Tester mit einer Vorabversion von GitHub: Start, Now-Playing-Karte, Tasten der echten Fernbedienung, langer Lauf. |
| Handy | Bestehende Tests bleiben grün. Die Handy-Oberfläche ändert sich nur um den Eintrag „Fernseher einrichten“. |

## Risiken

| Risiko | Folge | Gegenmittel |
|---|---|---|
| Bei „Bild aus“ am Fernseher verstummt der Ton | Der Wunsch aus der Spec ist nur teilweise erfüllbar | T0 misst es am echten Gerät. Die App kann das Panel nicht selbst abschalten. |
| SSO über zwei Geräte geht nicht | SSO-Konten können den Fernseher in der ersten Version nicht nutzen | T0 klärt es vor dem Bau. Dann entscheidet Fabian neu. |
| Play lehnt die TV-Version ab | Kann Updates der ganzen App aufhalten | Eigener TV-Track, Start erst nach der Handy-App (Entscheidung 3) |
| Zwei Oberflächen laufen auseinander | Fehler nur auf einem Gerät | TV-Oberfläche dünn halten, Logik nicht kopieren |
| Schwache Geräte (1,5 GB RAM) | Abstürze, Ablehnung nach TV-ME | Speicher in T7 messen, Cover-Cache begrenzen |

## Testweg und Reihenfolge (2026-11-17)

- Entwickelt und geprüft wird im Google-TV-Emulator. Ein echtes Gerät steht dabei nicht zur Verfügung.
- Die Abnahme am echten Gerät macht ein Tester mit einer Vorabversion von GitHub. Was nur ein echtes Gerät zeigt
  (Verhalten bei langem Ton, Bildschirmschoner, „Bild aus“, Klang-Effekte), steht als Prüfliste in den Release-Notes.
- **Erste Testversion:** Pakete T1 bis T5 und die Einstellungen aus T7. Eingerichtet wird der Fernseher darin durch Tippen.
- **Danach:** T6 (Einrichtung mit dem Handy) und der Rest von T7 und T8.

## Entschieden beim Start der Umsetzung (Claude, 2026-11-17; Fabian kann widersprechen)

| # | Entscheidung | Grund |
|---|---|---|
| E8 | Gibt es mehr als ein Profil, fragt der Fernseher bei jedem Start „Wer hört?“. Ein Profil mit PIN fragt die PIN auch dann, wenn es zuletzt aktiv war. | Ein Fernseher steht im Wohnzimmer und gehört allen. Sonst wäre der Schutz am Fernseher wirkungslos. |
| E9 | Die Startseite zeigt die Reihen „Weiterhören“, „Nächster Band“, „Neu hinzugefügt“ und „Serien“. Reihen je Bibliothek gibt es in der ersten Version nicht. | Für Reihen je Bibliothek fehlt die Datenquelle; die vier Reihen gibt es schon. |
| E10 | Das Banner zeigt das App-Icon auf Nachtblau und den Namen „Lauschig“. | Gut lesbar in der Reihe der Apps, passt zum dunklen TV-Design. |
| E11 | Die TV-Oberfläche ist immer dunkel. | Helle Flächen blenden am Fernseher. |
| E12 | Schutz am Fernseher gibt es nur per PIN. Die Gerätesperre wird nicht angeboten. | Fernseher haben in der Regel keine Gerätesperre. |

## Einrichtung mit dem Handy – entschieden beim Bau (Claude, 2026-11-20; Fabian kann widersprechen)

Diese Entscheidungen ersetzen die Zeilen „Inhalt bei Passwort“ und „Inhalt bei SSO“ der Tabelle oben. Der Umsetzungsplan steht in
[`../plans/2026-11-20-tv-phone-setup.md`](../plans/2026-11-20-tv-phone-setup.md), das Format in `docs/tv-einrichtung-mit-dem-handy.md`.

| # | Entscheidung | Grund | Kosten, falls falsch |
|---|---|---|---|
| E13 | Das Passwort geht nur an den eigenen Server, nie zum Fernseher. Das Handy meldet sich mit dem getippten Passwort selbst am Server an und bekommt eine **neue, eigene Sitzung für den Fernseher**. Zum Fernseher wandern nur die Server-Adressen und das Refresh-Token dieser Sitzung. Der Fernseher tauscht es sofort gegen eigene Tokens. | Ein falscher QR-Code bekäme so höchstens eine Sitzung, nie das Passwort. Die Sitzung hat alle Rechte des Kontos, auch die eines Admins; das Handy versucht, sie zu beenden (E19). Ein falsches Passwort fällt schon am Handy auf. E4 bleibt erfüllt: Die Sitzung des Handys selbst wandert nie. Geprüft am 2026-11-20 am Quelltext von Audiobookshelf (`Auth.js`, `TokenManager.js`): Ein Refresh-Token ist an kein Gerät gebunden; beim Tausch wird es erneuert, das alte gilt noch 10 Minuten weiter. | Ändert Audiobookshelf das, bleibt der Weg mit dem Passwort aus der ersten Fassung. |
| E14 | SSO-Konten können den Fernseher in dieser Version nicht einrichten. | Der Weg über zwei Geräte ist ungeprüft (T0). Mit E13 gibt es später einen einfacheren Weg: Das Handy macht eine zweite SSO-Anmeldung und übergibt deren Sitzung. | SSO-Konten warten auf ein späteres Paket. |
| E15 | Vor der Übergabe fragt das Handy keine Profil-PIN. | Es fragt das Passwort des Kontos, das ist die stärkere Frage. Kommt der SSO-Weg (ohne Passwort), muss die PIN-Frage dazu. | – |
| E16 | Ein Code gilt für genau ein angenommenes Paket und höchstens fünf Minuten. Schlägt die Anmeldung danach fehl, zeigt der Fernseher einen neuen Code. | Einmal-Schlüssel, kein zweiter Versuch mit demselben Schlüssel. | Bei einem Fehler scannt man noch einmal. |
| E17 | Der Fernseher fragt nach, bevor er etwas tut: Er zeigt die Server-Adressen aus dem Paket (unverschlüsselte markiert) und wartet höchstens 60 Sekunden auf OK. Der Fokus steht auf „Abbrechen“. Erst nach dem OK spricht er mit dem Server. | Der Absender eines Pakets ist nicht geprüft. Ohne Rückfrage könnte jeder, der den Code sieht und im Netz ist, den Fernseher an einem fremden Server anmelden und später über das Formular „neu anmelden“ ein Passwort abgreifen (Fund F3 der Sicherheitsprüfung vom 2026-11-21). Mit der Rückfrage braucht es dafür die Fernbedienung, wie beim Tippen. Sie bestätigt auch unverschlüsselte Adressen am Gerät selbst. | Ein Tastendruck mehr. Wer die Rückfrage blind bestätigt, ist nicht geschützt. Die Rückfrage unterscheidet nicht zwischen dem eigenen Paket und einem fremden, das zuerst ankommt, während man am Handy noch tippt (Nachprüfung 2026-11-21, N-D). |
| E18 | Der Code nennt eine IPv4-Adresse aus einem privaten Netz (10.x, 172.16–31.x, 192.168.x). Das Handy sendet nur über WLAN oder Kabel, nie über Mobilfunk oder ein VPN, und löst keinen Namen auf. | „Nur im Heimnetz“ soll eine Regel im Code sein, keine Absicht. | Reine IPv6-Netze bleiben beim Tippen. |
| E19 | Die Antwort des Fernsehers an das Handy ist ein ungeschützter Hinweis. Was gilt, zeigt der Fernseher. Bei allem außer „angemeldet“ versucht das Handy, die neue Sitzung am Server zu beenden: Es tauscht das Token selbst noch einmal und meldet die Sitzung mit dem aktuellen Token ab. Das gelingt nicht, wenn ein fremder Empfänger das Token schon zweimal getauscht hat oder der Server nicht erreichbar ist. Dann sagt das Handy, dass die Sitzung offen sein kann. | Audiobookshelf meldet nur mit dem aktuellen Token ab; das übergebene Token gilt nach einem Tausch noch 10 Minuten als Ersatz (geprüft am Quelltext, 2026-11-20). Ohne den zweiten Tausch wäre das Abmelden wirkungslos, sobald der Empfänger das Token benutzt hat (Fund F1). | Antwortet der Fernseher nicht, obwohl er angemeldet ist, verliert er die Anmeldung wieder und man scannt noch einmal. |

| E20 | Das Handy schickt das Passwort nur an die Server-Adresse, über die die App gerade verbunden ist, und nur, wenn es in einem WLAN oder Kabelnetz ist. Ist die App offline, gibt es keine Übergabe. | Die lokale Adresse ist oft unverschlüsselt. In einem fremden Netz könnte ein anderes Gerät unter dieser Adresse antworten (Fund F2). Die Verbindung der App beachtet die WLAN-Bindung des Kontos. | – |
| E21 | „Heimnetz“ heißt im Code: eine private IPv4-Adresse, erreichbar aus dem WLAN oder Kabelnetz des Handys. Ob das Netz das eigene ist und ob der Fernseher im selben Teilnetz liegt, wird nicht geprüft. | Netze mit mehreren Teilnetzen (VLANs) sollen funktionieren. | Auch ein Hotel- oder Gäste-WLAN zählt. Der Schutz gegen einen falschen Code ist dort schwächer. |
| E22 | Die Grenze von 20 abgelehnten Verbindungen bleibt. Sie schützt nicht, sie begrenzt nur die Arbeit des Fernsehers. | Ohne den Schlüssel aus dem Code lässt sich nichts erraten. Ein Gerät im Netz kann die Einrichtung mit dem Handy so oder so stören; dann bleibt das Tippen. | – |

## Offene Punkte

- Wann kommen Podcasts, Lesezeichen und die Warteschlange auf den Fernseher?
- Prüfzahl je Paket (Vorschlag der Nachprüfung 2026-11-21): vier Ziffern aus einem Hash des Pakets, am Handy nach dem Senden und in der Rückfrage am Fernseher gezeigt. Ein fremdes Paket, das zuerst ankommt, hätte eine Zahl, die das Handy nicht zeigt. Nicht gebaut: zwei Zahlenvergleiche in einem Ablauf sind für die Familie viel; erst entscheiden, ob die erste Zahl dann entfällt.
- Im ungeklärten Fall („Sitzung kann noch offen sein“) könnte das Handy anbieten, alle Geräte des Kontos abzumelden. Erst am Quelltext von Audiobookshelf prüfen (`/logout?allDevices=1`).
- Ungeprüft (Sicherheitsprüfung 2026-11-21): Kann ein Gerät im Netz per Cast eine nachgemachte Code-Seite über die echte legen? Das braucht ein echtes Gerät.
- Profile erkennt die App an Adresse und Benutzer-Kennung, die der antwortende Server nennt. Ein fremder Server mit bekannter Kennung könnte so ein vorhandenes Profil ersetzen (schon beim Tippen möglich). Eigenes Paket.
- Cast vom Handy auf die TV-App (Googles Stufe 2) hängt am offenen Cast-PR #28.
