# Lauschig – Design

Stand: 2026-10-31 · Autor: Fabian (detair), aufgeschrieben von Claude
Status: Entwurf zur Freigabe

## 1. Worum es geht und warum

Lauschig ist ein nativer Android-Client für **Audiobookshelf** (ABS). Er vereint
das Bedienkonzept von **Audible** (Store mit allem Verfügbaren, dazu die eigene
Bibliothek) mit der Offenheit von **Storii**. Statt eines Shops dient der eigene
ABS-Server als Quelle, mit allen selbst digitalisierten Hörbüchern und Hörspielen.

Warum eine eigene App:

- **Storii** gibt es nur über F-Droid, und es braucht beim Start eine Verbindung
  zum ABS-Server. Fabians ABS ist nur über NetBird erreichbar, deshalb steht er
  unterwegs ohne Verbindung da, obwohl die Bücher heruntergeladen sind.
- Die **offizielle ABS-App** gibt es zwar im Play Store, aber die lokale Bibliothek
  ist schlecht dargestellt, m4b-Kapitel werden schlecht angezeigt, und die UI
  fühlt sich „clunky“ an (Capacitor-Web-App).
- **Audible** gefällt vom Bedienkonzept her, ist aber an den Audible-Shop gebunden.
- **Play Store als Pflicht:** Die Familie soll die App einfach aus dem Play Store
  installieren können. Mit Googles Entwickler-Verifizierungspflicht für Apps außerhalb
  des Stores (ab 2026) wird Sideloading schwieriger.

Nutzer: Fabian und seine Familie. Jeder meldet sich mit seinem eigenen ABS-Konto an.
Das Projekt ist öffentlich auf GitHub (`detair/lauschig`) unter **GPL-3.0**
(dieselbe Lizenz wie ABS).

Es gibt keine Deadline, die App ist fertig, wenn sie fertig ist.

## 2. Ziele

### 2.1 Offline zuerst (das wichtigste Ziel)
- Die App startet und spielt alle heruntergeladenen Titel **ohne jede Server-Verbindung**.
  Kein Start-Bildschirm, kein Login-Check und kein Spinner darf auf den Server warten.
- Die lokale Datenbank auf dem Gerät ist die Quelle für die UI. Der Server dient
  zum Stöbern, Herunterladen und Synchronisieren, er ist keine Voraussetzung.
- Hörfortschritt und Hörsitzungen werden offline lokal gespeichert und später
  synchronisiert, ohne dass Hörzeit verloren geht (wichtig für die Stats).
- Ohne Verbindung zeigt die App einen dezenten Offline-Hinweis. Inhalte, die nur
  online verfügbar sind, werden ausgeblendet oder als nicht verfügbar markiert und
  blockieren nichts.

### 2.2 Store + eigene Bibliothek (Audible-Konzept)
Die Navigation besteht aus vier Reitern unten, jeweils **mit Icon und Text** und
großer Tippfläche: **Start · Bibliothek · Entdecken · Suche**.

- **Start:** „Weiterhören“ (das aktuelle Buch, ein Tipp startet es), „Als Nächstes in
  deinen Serien“ (der nächste Band jeder Serie, die man hört) und „Neu auf dem Server“.
- **Bibliothek (meine):** Tabs für *Am Hören*, *Gehört*, *Heruntergeladen* und *Serien*.
  Die lokale Bibliothek muss genauso gut aussehen wie die Server-Bibliothek, mit
  Covern, Fortschritt und Offline-Symbol.
- **Entdecken (Store):** alles, was auf dem ABS-Server verfügbar ist, durchstöberbar
  wie ein Shop.
- **Suche:** über Server und lokale Titel; offline nur lokal.

### 2.2a Mehrere Bibliotheken und Podcasts
- Hörbücher und Hörspiele (in ABS getrennte Bibliotheken) erscheinen in Start, Bibliothek,
  Entdecken und Suche **gemeinsam**. Ein Filter (z. B. Chips „Alle · Hörbücher ·
  Hörspiele · Podcasts“, abgeleitet aus den ABS-Bibliotheken des Nutzers) schränkt die
  Ansicht ein. Die Auswahl bleibt pro Bildschirm gespeichert.
- **Podcasts werden unterstützt** (ABS-Podcast-Bibliotheken): Podcast-Seite mit
  Episodenliste, neue Episoden auf dem Start-Bildschirm, Episoden-Fortschritt, Download
  einzelner Episoden. Optional werden die neuesten N Episoden automatisch geladen
  (Claudes Vorschlag). Offline gelten dieselben Regeln wie bei Büchern.

### 2.3 Serien
- Egal ob in der eigenen Bibliothek oder im ganzen Katalog: Bei einem Buch, das zu
  einer Serie gehört, lässt sich die **ganze Serie** anzeigen, sortiert nach Reihenfolge.
- Die **Bandnummer** steht sichtbar auf dem Cover-Thumbnail (Badge) bzw. im Titel.
- **Fehlende Bände** (in der Serie, aber nicht auf dem Server) werden ebenfalls angezeigt,
  ausgegraut mit dem Hinweis „nicht in der Bibliothek“. Datenquelle sind externe
  Metadaten, vermutlich Audible-Katalog (Region DE) bzw. Audnexus, also dasselbe, was
  ABS für Metadaten nutzt. Dafür braucht der Nutzer kein Konto. Nur online verfügbar.
- Fehlender Band → Knopf **„Anfragen“** schickt eine Anfrage an **ReadMeABook (RMAB)**.
  Das ist optional: Ohne konfiguriertes RMAB wird der Knopf nicht angezeigt.

### 2.4 Detailansicht eines Buchs
- Cover, Titel, Bandnummer, Beschreibung, Dauer, Download-Status und -Größe.
- **Autor, Sprecher, Serie und Genre** sind antippbar und führen jeweils auf eine eigene
  Seite mit den ABS-Daten (Autor: Bild, Beschreibung, alle Bücher; Sprecher: alle Bücher;
  Genre: alle Bücher). Diese Seiten verlinken wiederum weiter.
- **Kapitel** werden sauber angezeigt, besonders bei m4b (eingebettete Kapitel): Liste mit
  Titel und Dauer, das aktuelle Kapitel ist hervorgehoben, Antippen springt dorthin.

### 2.5 Player
Fabian hört oft 2–5 h am Stück und abends vor dem Einschlafen.
- **Pause/Weiter muss jederzeit mit einem Griff gehen**: im Sperrbildschirm und in der
  Benachrichtigung, im Mini-Player auf jedem App-Bildschirm, über ein
  Homescreen-**Widget** (Cover, Play/Pause, ±30 s), mit den Kopfhörertasten und über
  Android Auto.
- **Sleep-Timer**: feste Zeiten und „Ende des Kapitels“. Claudes Vorschläge:
  Ausblenden am Ende und Schütteln zum Verlängern.
- Geschwindigkeit, Sprung vor/zurück und Kapitelsprung.
- Claudes Vorschlag: beim Fortsetzen nach einer Pause ein paar Sekunden zurückspringen.

### 2.6 Stats, Achievements, Jahresrückblick
- Statistiken aus den ABS-Hörsitzungen: Hörzeit pro Tag/Woche/Jahr, Streaks,
  beendete Bücher und Serien.
- **Achievements**, die darauf aufbauen (z. B. „100 Stunden“, „Serie komplett“,
  „7 Tage am Stück“). Sie werden in der App berechnet, ein eigener Server ist nicht
  nötig (Claudes Annahme).
- **Jahresrückblick** im Stil von Spotify Wrapped als Story-Ansicht.

### 2.7 Android, so gut wie möglich
Die App läuft nur auf Android und soll alle relevanten aktuellen und kommenden
Android-Features nutzen:
- **Android Auto** (Pflicht).
- **Sprachsteuerung über Google Assistant/Gemini**: Media-Session mit
  „Play from search“ („Spiel X in Lauschig“, Pause, Weiter, „Was läuft gerade?“).
  Später kommen **AppFunctions** (Android 16+, noch Early Beta) dazu, z. B. für
  Sleep-Timer, Stats-Fragen und „nächsten Band herunterladen“, sobald es stabil ist.
- Material You (dynamische Farben), Widgets, Predictive Back, zuverlässige
  Hintergrund-Downloads.

### 2.7a Komfort (aus dem Abgleich mit Storii 0.13.0 und eigenen Ideen, von Fabian übernommen)
- **Lesezeichen**, optional mit Notiz, pro Buch bzw. Episode. Sie werden mit ABS
  synchronisiert und funktionieren offline.
- **Hörverlauf pro Buch**: wann wie lange gehört wurde, Antippen springt an die Stelle.
- **Sammlungen und Playlists** aus ABS, dazu eine **Warteschlange** („danach spielen“).
- **Optik**: System, Hell oder Dunkel, **reiner Schwarzmodus** (OLED, gut fürs Bett),
  Schriftgröße bzw. Skalierung, dynamische Farben.
- **Navigation anpassbar**: Reiter umsortieren und Startbildschirm wählen.
- **Optional beim Start automatisch weiterspielen** (das zuletzt gehörte).
- **Links öffnen**: Links zu Büchern in der ABS-Weboberfläche öffnen den Titel in
  Lauschig (App Links bzw. Intent-Filter für die eingetragenen Server-Adressen).
- **Verbindungsstatus**: Ein Fenster zeigt lokal, öffentlich oder offline, die genutzte
  Adresse, den Netztyp und den Socket-Status, dazu einen Knopf „Neu verbinden“
  (zur Fehlersuche in der Familie).
- **Download-Verwaltung**: Downloads pausieren und fortsetzen, sortieren, durchsuchen
  und den belegten Speicher anzeigen.
- **„Was ist neu“** nach jedem Update.
- **Tags** zusätzlich in Suche und Filter.
- **Kleines Admin-Menü** nur für ABS-Admins: Bibliothek scannen, Titel neu scannen bzw.
  neu zuordnen, sehen, wer online ist. Mehr Verwaltung gibt es nicht (siehe Nicht-Ziele).
- **Geschwindigkeit pro Buch merken.**
- **Stille überspringen** und **Lautstärke-Boost** (bei digitalisierten Kassetten oft
  leise).
- **Optional automatisch weiterspielen, sobald Auto oder Bluetooth-Kopfhörer verbunden
  werden.**

### 2.8 Später: TV-Modus (Android TV / Google TV)
- Eigenes, an den Fernseher angepasstes Design (Compose for TV, Bedienung mit
  Fernbedienung/D-Pad, große Cover, 10-Fuß-Abstand). Bibliothek und Player wie auf dem
  Handy, dieselbe lokale Datenbank und derselbe Sync.
- **Bildschirm-aus-Modus**, damit der Fernseher beim reinen Zuhören möglichst wenig Strom
  verbraucht. Eine App kann das Panel eines Fernsehers nicht zuverlässig selbst abschalten.
  Geplant sind deshalb (Claudes Vorschlag): ein komplett schwarzer Bildschirm ohne
  Bewegung (spart bei OLED fast den gesamten Panelstrom), Bildschirmschoner und
  Ruhezustand werden nicht blockiert, solange Audio läuft. Falls das Gerät es anbietet,
  gibt es einen Hinweis bzw. Shortcut auf die geräteeigene Funktion „Bild aus“.
- Die Phase kommt nach den Handy-Zielen aus Abschnitt 2.

## 3. Nicht-Ziele
- Kein iOS, kein Desktop, keine Web-App.
- Kein eigener Backend-Server für Lauschig. Die App spricht nur mit ABS, optional mit
  RMAB und externen Metadatenquellen.
- Kein Ersatz für die ABS-Weboberfläche bei der Verwaltung (Metadaten bearbeiten,
  Benutzer verwalten, Einstellungen). Ausnahme ist das kleine Admin-Menü aus 2.7a.
- Kein Kauf und keine Anbindung an Audible-Konten.

## 4. Anti-Ziele (wäre ein Fehlschlag, selbst wenn es technisch funktioniert)
- **Langsam.** Die App muss sich flüssig und nativ anfühlen, beim Start, beim
  Scrollen durch große Bibliotheken und beim Wechseln von Bildschirmen.
- **Offline keine Funktion.** Jede Situation, in der ein heruntergeladener Titel ohne
  Verbindung nicht startet oder die App wartet, ist ein Fehlschlag.
- Eine „clunky“ Web-App-Anmutung wie bei der offiziellen App.

## 5. Rahmenbedingungen
- Fabians ABS-Server ist nur über **NetBird** erreichbar. Das ist typisch für
  Selbsthoster, die App muss damit umgehen, dass der Server oft nicht erreichbar ist.
- Fabians ABS: v2.37.1 (Stand 2026-10-21), mehrere Bibliotheken (u. a. Hörbücher und
  Hörspiele), RMAB mit Region DE.
- Die Familie ist teils nicht technisch versiert. Die Einrichtung soll nur aus
  Server-Adresse und Login bestehen.
- Der **Play Store** ist der Vertriebsweg. Das Google-Play-Entwicklerkonto muss noch
  angelegt werden. Ein neues privates Konto braucht einen geschlossenen Test (12 Tester,
  14 Tage), bevor die App in Produktion gehen kann. **Entscheidung:** Die App läuft
  zunächst nur im geschlossenen Test für die Familie. Erst wenn sie sich dort bewährt hat,
  wird der Test mit 12 Testern über 14 Tage angegangen, um sie öffentlich zu machen.
  Für den Play-Eintrag braucht es trotzdem schon früh eine Datenschutzerklärung und das
  Data-Safety-Formular.
- Lizenz GPL-3.0, öffentliches Repo `github.com/detair/lauschig`.
- Name: **Lauschig** (frei laut Play-Store- und GitHub-Suche vom 2026-10-31; eine
  Markenrecherche bei DPMA/EUIPO steht noch aus).

## 6. Technische Entscheidungen (Claudes Wahl, von Fabian an Claude übergeben)
- Kotlin, Jetpack Compose, Material 3.
- **Media3** (ExoPlayer, `MediaLibraryService`, MediaSession) für Wiedergabe, Benachrichtigung,
  Sperrbildschirm, Widget, Android Auto und Sprachsteuerung.
- Lokale Datenbank (Room) als Quelle für die UI. Netzwerkdaten werden dort hinein
  synchronisiert.
- Downloads über WorkManager/Media3-Downloads, im App-eigenen Speicher.
- Paket-ID-Vorschlag: `io.wolftown.lauschig`.
- Mockups aus dem Brainstorming: `.superpowers/brainstorm/` in `~/Projects/abs-client`
  (Start, Bibliothek, Detail, Serie; das Feedback lautete „gefällt mir“, plus Icons in
  der Navigation).

## 6a. Entscheidungen aus dem Builder-Check (Claudes Wahl, bitte prüfen)
- **Anmeldung und Downloads sind voneinander entkoppelt.** Ein abgelaufener Token oder
  ein nicht erreichbarer Server verhindert nie die Wiedergabe heruntergeladener Titel.
  Der Token wird im Hintergrund erneuert, sobald der Server erreichbar ist. Beim
  Abmelden wird gefragt, ob die Downloads behalten werden sollen.
- **Ein ABS-Konto pro Gerät** (jedes Familienmitglied hat sein eigenes Handy). Das Schema
  wird trotzdem mit `serverId`/`userId` angelegt, damit später mehrere Konten pro Gerät
  möglich sind.
- **ABS-Version:** Unterstützt wird die aktuelle 2.x-Reihe ab der Version mit dem neuen
  Token-Schema (Access- und Refresh-Token). Ältere Server werden nicht unterstützt.
  Offline-Sitzungen werden über die ABS-Endpunkte für lokale Sessions hochgeladen
  (wie bei der offiziellen App).
- **Login:** Benutzername/Passwort und OpenID Connect (SSO), weil ABS beides kann und
  Authentik später kommen könnte.
- **Server-Adressen (wie bei Storii, von Fabian gewünscht):** In der Einrichtung gibt es zwei
  klar benannte Felder, **„Lokale Adresse“** (z. B. `http://192.168.1.10:13378` im
  Heimnetz) und **„Öffentliche Adresse“** (z. B. `https://abs.example.com` oder die
  NetBird-/VPN-Adresse). Das ist gedacht für alle, die kein VPN nutzen. Optional lässt sich
  die lokale Adresse an WLAN-Namen (SSID) binden. Im Heimnetz wird die lokale Adresse
  bevorzugt, sonst die öffentliche. Weitere Adressen sind als Erweiterung möglich. Ist
  keine erreichbar, schaltet sie still in den Offline-Modus, ohne Fehlermeldung oder
  Aufforderung, NetBird zu starten. Vertraut wird den System- und vom Nutzer
  installierten CA-Zertifikaten, HTTP ist nur bei ausdrücklicher Bestätigung erlaubt.
- **Fehlende Bände:** Grundlage ist die Serie aus ABS. Externe Daten (Audible/Audnexus)
  ergänzen nur die Lücken, werden gecacht und dürfen ausfallen, ohne dass etwas kaputtgeht
  (es sind inoffizielle Endpunkte). Die RMAB-Anfrage darf später kommen, falls die API
  zunächst nicht passt.
- **Downloads:** Es werden die Originaldateien geladen (m4b bzw. mehrteilige mp3), nicht
  transkodiert, und zwar pro Buch. Optional gibt es: „nächsten Band automatisch laden“,
  „nur über WLAN“, „nach dem Hören löschen“ und eine Anzeige des belegten Speichers.
- **Fortschritt zwischen Geräten:** Der neueste Zeitstempel gewinnt. Springt die Position
  dadurch merklich (> 1 min), zeigt die App einen Hinweis mit „Zurück zu
  hh:mm:ss“, damit nie eine Position verloren geht.
- **Sprache:** Deutsch und Englisch von Anfang an (alle Texte als String-Ressourcen,
  auch Achievements und Jahresrückblick).
- **Geräte:** minSdk 28 (Android 9), targetSdk ist immer das aktuelle Release. Neue
  Features (dynamische Farben, AppFunctions) werden je nach Android-Version zugeschaltet.
  Layouts für Handy, Tablet und Foldable. Chromecast und Wear OS kommen später, nicht
  in die erste Version.
- **Reihenfolge:** zuerst Offline-Wiedergabe und Bibliothek, dann Store, Serien und
  Player-Komfort, danach Android Auto, Widget und Sprachsteuerung, zum Schluss Stats,
  Achievements und Jahresrückblick. Am Ende ist alles aus Abschnitt 2 drin.
- **Paket-ID** `io.wolftown.lauschig` ist endgültig, sobald die App im Play Store
  veröffentlicht ist. Vorher kurz bei DPMA/EUIPO nach „Lauschig“ suchen.

## 7. Dem Builder überlassen
- Modulstruktur, Architektur (z. B. MVVM/MVI) und DI-Framework.
- Wie die Sync-Logik im Detail aussieht (Konfliktregeln für den Fortschritt bei mehreren
  Geräten, Wiederholungen, Hörsitzungen nachträglich hochladen über die ABS-API für
  lokale Sessions).
- Wo genau die Daten für fehlende Serienbände herkommen und wie sie gecacht werden.
- Wie die Anmeldung bei RMAB funktioniert (zuerst recherchieren, ob die RMAB-API mit
  ABS-Login bzw. Token nutzbar ist).
- Welche Achievements es konkret gibt und wie die Jahresrückblick-Story gestaltet ist.
- Optik im Detail (Farben, Typografie, Animationen) im Rahmen von Material You.
- Reihenfolge der Umsetzung. Ziel ist, dass die Familie möglichst früh etwas
  Brauchbares hat (Offline-Wiedergabe und Bibliothek zuerst).

## 8. Offene Punkte
- Google-Play-Entwicklerkonto anlegen (macht Fabian).
- Markenrecherche „Lauschig“ bei DPMA/EUIPO, bevor die Paket-ID im Store festgelegt wird.
