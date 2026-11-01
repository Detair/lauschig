# Lauschig: Vom Code zum internen Play-Test

Schritt-für-Schritt für Fabian. Reihenfolge einhalten: erst Handy-Test, dann Keystore/Secrets, dann Play Console.
Befehle laufen aus `~/GIT/detair/lauschig` auf Stand `main`.

---

## 1. Handy-Abnahme (Debug-Build per USB)

**Vorbereitung am Handy**
1. Einstellungen → Über das Telefon → 7× auf „Build-Nummer“ tippen → Entwickleroptionen sind aktiv.
2. Einstellungen → System → Entwickleroptionen → **USB-Debugging** an.
3. Handy per USB anschließen, Dialog „USB-Debugging zulassen?“ mit „Immer von diesem Computer zulassen“ bestätigen.

**Installieren**
```bash
adb devices
```
Das Gerät muss als `device` erscheinen (nicht `unauthorized`). Dann:
```bash
timeout 15m ./gradlew :app:installDebug
```

**Testablauf** (jeden Punkt abhaken)
- [ ] App öffnen → Einrichtung: lokale Adresse (z. B. `http://…` im Heimnetz) und öffentliche Adresse (NetBird) eintragen, optional Heim-WLAN binden, anmelden.
- [ ] Bibliothek erscheint mit Covern, Serien-Badges, Filter Hörbuch/Hörspiel funktioniert.
- [ ] Ein Buch öffnen → Kapitel sichtbar → **Herunterladen**. Benachrichtigungs-Erlaubnis zulassen, Fortschritt läuft, Download endet.
- [ ] Abspielen, Mini-Player + Vollbild-Player, vor/zurück springen, Kapitel wechseln, Sperrbildschirm-/Benachrichtigungs-Steuerung.
- [ ] **Flugmodus an**, App komplett schließen (aus „Zuletzt verwendet“ wischen), neu öffnen → Bibliothek ist da, heruntergeladenes Buch spielt ab der richtigen Stelle.
- [ ] Offline ein paar Minuten weiterhören, pausieren.
- [ ] Flugmodus aus → wenige Sekunden warten → in der ABS-Weboberfläche prüfen, dass der Fortschritt dort angekommen ist.
- [ ] Umgekehrt: in der ABS-Weboberfläche oder auf einem anderen Gerät weiterhören, dann in Lauschig das (pausierte) Buch fortsetzen → es springt auf den neueren Stand.
- [ ] Bluetooth-Kopfhörer: Play/Pause-Taste funktioniert.

Fehler bitte notieren (was getan, was erwartet, was passiert). Logs dazu:
```bash
adb logcat -d --pid=$(adb shell pidof io.wolftown.lauschig) > ~/lauschig-log.txt
```

## 2. Baseline Profile (optional, macht den Start schneller)

Handy bleibt angeschlossen und entsperrt. Der Lauf installiert eine eigene Variante der App und bedient sie automatisch (Start, Bibliothek scrollen):
```bash
timeout 30m ./gradlew :app:generateBaselineProfile
```
Danach liegt ein neues Profil unter `app/src/release/generated/baselineProfiles/` (oder `app/src/main/…`). `git status` zeigt es – sag mir Bescheid, dann committe ich es.
Falls es scheitert: Log schicken, das ist kein Blocker für den internen Test.

## 3. Upload-Keystore anlegen

Der Upload-Key signiert nur das Hochladen zu Google. Google signiert die App für die Nutzer mit einem eigenen Schlüssel (Play App Signing). Geht der Upload-Key verloren, kann man ihn bei Google zurücksetzen lassen – trotzdem sichern.

```bash
mkdir -p ~/keys && chmod 700 ~/keys
```
```bash
keytool -genkeypair -v -keystore ~/keys/lauschig-upload.jks -storetype PKCS12 -keyalg RSA -keysize 4096 -validity 10000 -alias upload
```
- Passwort zweimal eingeben (lang, aus dem Passwortmanager generieren).
- Namensfragen: Vor-/Nachname reicht, Rest leer lassen oder `DE` bei Ländercode.
- **Wichtig:** Bei PKCS12 ist das Schlüssel-Passwort automatisch gleich dem Keystore-Passwort.

**Sichern:** Datei `~/keys/lauschig-upload.jks` + Passwort in den Passwortmanager oder nach OpenBao unter einen eigenen Pfad wie `personal/lauschig/upload-key` (nicht unter `agents/`). Niemals ins Repo.

Fingerprint für später notieren:
```bash
keytool -list -v -keystore ~/keys/lauschig-upload.jks -alias upload | grep SHA256
```

## 4. GitHub-Secrets setzen

`gh secret set` fragt den Wert interaktiv ab – so landet das Passwort nicht in der Shell-History.

```bash
base64 -w0 ~/keys/lauschig-upload.jks | gh secret set LAUSCHIG_KEYSTORE_BASE64 --repo Detair/lauschig
```
```bash
gh secret set LAUSCHIG_KEYSTORE_PASSWORD --repo Detair/lauschig
```
```bash
gh secret set LAUSCHIG_KEY_PASSWORD --repo Detair/lauschig
```
(beide Male dasselbe Passwort aus Schritt 3)
```bash
printf upload | gh secret set LAUSCHIG_KEY_ALIAS --repo Detair/lauschig
```
Prüfen:
```bash
gh secret list --repo Detair/lauschig
```
Es müssen genau die vier `LAUSCHIG_*`-Einträge erscheinen.

## 5. Signiertes Release-Bundle bauen (per Tag)

```bash
git tag -a v0.1.0 -m "Lauschig 0.1.0 – interner Test" && git push origin v0.1.0
```
Der Workflow „Release“ baut das signierte `.aab`. Fortschritt:
```bash
gh run list --repo Detair/lauschig --workflow Release --limit 1
```
Wenn grün, herunterladen:
```bash
gh run download --repo Detair/lauschig --name lauschig-release-aab --dir ~/lauschig-release/v0.1.0
```

**Für jede weitere Version** muss `versionCode` in `app/build.gradle.kts` steigen (1 → 2 → …) und `versionName` angepasst werden – das mache ich beim nächsten Release mit.

Alternative ohne GitHub (lokal signieren):
```bash
LAUSCHIG_KEYSTORE=~/keys/lauschig-upload.jks LAUSCHIG_KEY_ALIAS=upload LAUSCHIG_KEYSTORE_PASSWORD='…' LAUSCHIG_KEY_PASSWORD='…' timeout 20m ./gradlew :app:bundleRelease
```
(Passwörter landen dabei in der History – Variante über Tag bevorzugen.)

## 6. Play Console einrichten

https://play.google.com/console

### 6.1 App anlegen
„App erstellen“:
- App-Name: **Lauschig**
- Standardsprache: **Deutsch – de-DE**
- App oder Spiel: **App**
- Kostenlos oder kostenpflichtig: **Kostenlos** (lässt sich später nicht mehr auf kostenpflichtig ändern – passt zur Entscheidung)
- Erklärungen (Richtlinien, US-Exportgesetze) bestätigen.

### 6.2 „App-Inhalte“ (Dashboard → „App einrichten“)
Antworten stehen in `docs/play-store/data-safety.md`. Kurzfassung:

| Punkt | Eintrag |
|---|---|
| Datenschutzerklärung | `https://github.com/Detair/lauschig/blob/main/docs/privacy-policy.md` |
| App-Zugriff | „Alle oder einige Funktionen sind eingeschränkt“ → Hinweis: *Benötigt einen eigenen Audiobookshelf-Server; Adresse, Benutzername und Passwort vergibt der Server-Betreiber.* Für den internen Test reicht das. Für eine spätere Veröffentlichung braucht Google Zugangsdaten zu einem Testserver. |
| Werbung | Nein |
| Einstufung des Inhalts | Fragebogen ausfüllen: Kategorie „Referenz, Nachrichten oder Bildung“ bzw. „Sonstige“, alle Inhaltsfragen „Nein“ (Nutzer laden eigene Inhalte vom eigenen Server; keine Nutzerinteraktion untereinander). |
| Zielgruppe | 18+ (bzw. „nicht für Kinder“); App spricht Kinder nicht gezielt an. |
| Datensicherheit | „Keine Daten erhoben oder geteilt“, Verschlüsselung bei Übertragung: Ja, Löschung: Ja (Deinstallation) – Details siehe Datei. |
| Nachrichten-App / Behörden / Finanzen / Gesundheit | Nein |
| Dienste im Vordergrund | Medienwiedergabe (Abspielen von Hörbüchern) und Datensynchronisierung (Downloads auf Nutzerwunsch). Falls ein Video verlangt wird: kurze Bildschirmaufnahme von Abspielen + Download. |

### 6.3 Store-Eintrag (Wachstum → Store-Präsenz → Haupt-Store-Eintrag)
- Kurz- und Langbeschreibung aus `docs/play-store/listing-de.md` (Englisch: `listing-en.md` als Übersetzung hinzufügen).
- App-Symbol 512×512 PNG, Funktionsgrafik 1024×500, mind. 2 Screenshots vom Handy (aus Schritt 1). Die Grafiken fehlen noch – sag Bescheid, dann erstelle ich Entwürfe.
- Kontakt-E-Mail (wird öffentlich angezeigt) – am besten eine eigene Adresse für die App.
- Kategorie: **Bücher & Nachschlagewerke** (oder Musik & Audio).

### 6.4 Interner Test für die Familie
1. Testen → **Interner Test** → Tab „Tester“ → „E-Mail-Liste erstellen“, Name „Familie“, die Google-Konten der Familie eintragen (die im Play Store des jeweiligen Handys angemeldet sind) → speichern, Liste anhaken.
2. „Feedback-URL / E-Mail“: `https://github.com/Detair/lauschig/issues` oder deine Mail.
3. Tab „Releases“ → „Neuen Release erstellen“.
   - Beim ersten Mal: **Play App Signing** akzeptieren („Google generiert den App-Signaturschlüssel“).
   - `.aab` aus `~/lauschig-release/v0.1.0/` hochladen.
   - Release-Name: `0.1.0`, Versionshinweise (de-DE): *Erste Testversion: Bibliothek, Downloads, Offline-Wiedergabe, Fortschritts-Sync.*
   - Speichern → Überprüfen → **Einführung starten**.
4. Unter „Tester“ den **Link zur Teilnahme** kopieren und der Familie schicken. Jeder öffnet ihn am Handy (mit dem eingetragenen Google-Konto), tippt „Tester werden“ und installiert dann über den Play-Store-Link.

Interne Tests sind meist nach Minuten verfügbar. Fehlende Pflichtangaben zeigt die Console oben als Warnung – alle abarbeiten, bis „Einführung starten“ möglich ist.

**Dein eigenes Handy:** Der Debug-Build aus Schritt 1 hat eine andere Signatur als die Play-Version. Vor der Installation aus dem Play Store den Debug-Build deinstallieren (Downloads und Anmeldung gehen dabei verloren):
```bash
adb uninstall io.wolftown.lauschig
```

### 6.5 Später: öffentliche Veröffentlichung
Neue private Entwicklerkonten müssen vor der Produktion einen **geschlossenen Test** mit mindestens 12 Testern über 14 Tage durchführen (Stand meines Wissens – in der Console unter „Produktion“ steht die aktuelle Anforderung). Für die Familie ist das nicht nötig; der interne Test hat keine solche Hürde.

## SSO aktivieren (Audiobookshelf mit OpenID)

Nutzt dein Audiobookshelf OpenID (z. B. Authentik), zeigt Lauschig unter dem Passwort-Login den Knopf „Mit SSO anmelden“.
Damit der Server Lauschig zulässt, muss ein Admin die Rückkehr-Adresse freigeben:

1. In Audiobookshelf: Einstellungen → Authentifizierung → OpenID Connect.
2. Bei „Allowed Mobile Redirect URIs“ den Eintrag `lauschig://oauth` hinzufügen (der Standard enthält nur `audiobookshelf://oauth`).
3. Speichern. Ohne diesen Eintrag zeigt Lauschig: „Dein Server erlaubt Lauschig noch nicht für SSO.“

## RMAB-Hinweis (Bände anfragen)

Beim Verbinden mit ReadMeABook erzeugt Lauschig einen dauerhaften API-Token namens „Lauschig (<Gerät>)“; dein RMAB-Passwort wird nicht gespeichert. Der Token ist in RMAB unter den API-Tokens des Nutzers sichtbar und lässt sich dort jederzeit widerrufen.

## 7. Markenprüfung „Lauschig“

Kostenlos, ca. 15 Minuten:
1. **DPMA:** https://register.dpma.de → Marken → Einsteigerrecherche → „Lauschig“ (auch ähnliche Schreibweisen: „Lauschich“, „Lauschik“). Relevant sind die Nizza-Klassen **9** (Software/Apps) und **41** (Unterhaltung).
2. **EUIPO / TMview:** https://www.tmdn.org/tmview → „Lauschig“, Gebiete EU + DE.
3. Zusätzlich im Play Store nach „Lauschig“ suchen.

Treffer in Klasse 9 oder 41 mit gleichem oder sehr ähnlichem Namen → mir Bescheid geben, dann überlegen wir einen Plan B, **bevor** die App öffentlich wird. Für den internen Familien-Test ist das unkritisch.

---

## Checkliste

- [ ] 1 Handy-Abnahme bestanden
- [ ] 2 Baseline Profile erzeugt (optional)
- [ ] 3 Upload-Keystore angelegt und gesichert
- [ ] 4 Vier GitHub-Secrets gesetzt
- [ ] 5 Tag `v0.1.0` → signiertes `.aab` heruntergeladen
- [ ] 6 Play Console: App, App-Inhalte, Store-Eintrag, interner Test, Familie eingeladen
- [ ] 7 Markenprüfung ohne Konflikt
