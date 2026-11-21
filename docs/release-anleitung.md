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

## 3. Signaturschlüssel anlegen

Dieser eine Schlüssel signiert die APKs auf GitHub und das `.aab` für Play. In Play gibst du ihn als eigenen App-Signaturschlüssel an (Schritt 6.5), statt Google einen erzeugen zu lassen. So tragen beide Wege dieselbe Signatur, und eine GitHub-Installation soll sich später mit der Play-Version aktualisieren lassen.

**Geht der Schlüssel verloren, bekommen GitHub-Installationen keine Updates mehr**, Play signiert mit seiner Kopie weiter. Wird er bekannt, kann jemand Updates fälschen, die sich über GitHub-Installationen legen. Deshalb an zwei Orten sichern und nirgends sonst ablegen.

```bash
mkdir -p ~/keys && chmod 700 ~/keys
```
```bash
keytool -genkeypair -v -keystore ~/keys/lauschig-release.jks -storetype PKCS12 -keyalg RSA -keysize 4096 -validity 10000 -alias lauschig
```
- Passwort zweimal eingeben (lang, aus dem Passwortmanager generieren).
- Namensfragen: Vor-/Nachname reicht, Rest leer lassen oder `DE` bei Ländercode.
- **Wichtig:** Bei PKCS12 ist das Schlüssel-Passwort automatisch gleich dem Keystore-Passwort.

**Sichern:** Datei `~/keys/lauschig-release.jks` + Passwort in den Passwortmanager und nach OpenBao unter einen eigenen Pfad wie `personal/lauschig/release-key` (nicht unter `agents/`). Niemals ins Repo.

Fingerprint für später notieren (jedes GitHub-Release nennt ihn unter „Signatur prüfen“, ohne Doppelpunkte):
```bash
keytool -list -v -keystore ~/keys/lauschig-release.jks -alias lauschig | grep SHA256
```

## 4. GitHub-Secrets setzen

Die vier Werte liegen in der Umgebung `release` des Repos, nicht bei den allgemeinen Repo-Secrets. Nur ein Lauf für einen Tag `v*` darf diese Umgebung benutzen. Ein Lauf von einem beliebigen Branch kommt an die Werte nicht heran.

`gh secret set` fragt den Wert interaktiv ab – so landet das Passwort nicht in der Shell-History.

```bash
base64 -w0 ~/keys/lauschig-release.jks | gh secret set LAUSCHIG_KEYSTORE_BASE64 --repo Detair/lauschig --env release
```
```bash
gh secret set LAUSCHIG_KEYSTORE_PASSWORD --repo Detair/lauschig --env release
```
```bash
gh secret set LAUSCHIG_KEY_PASSWORD --repo Detair/lauschig --env release
```
(beide Male dasselbe Passwort aus Schritt 3)
```bash
printf lauschig | gh secret set LAUSCHIG_KEY_ALIAS --repo Detair/lauschig --env release
```
Prüfen:
```bash
gh secret list --repo Detair/lauschig --env release
```
Es müssen genau die vier `LAUSCHIG_*`-Einträge erscheinen.

Liegen die vier Werte noch zusätzlich bei den allgemeinen Repo-Secrets (Stand vor dem 2026-11-16), dort löschen, sobald sie in der Umgebung stehen:
```bash
gh secret list --repo Detair/lauschig
```
```bash
for s in LAUSCHIG_KEYSTORE_BASE64 LAUSCHIG_KEYSTORE_PASSWORD LAUSCHIG_KEY_ALIAS LAUSCHIG_KEY_PASSWORD; do gh secret delete "$s" --repo Detair/lauschig; done
```

## 5. Release bauen (per Tag)

Der Workflow „Release“ läuft in vier Schritten:

1. **Vorprüfung** (Sekunden): Der Tag passt zu `versionName`, der Tag liegt auf `main`, `docs/release-notes/<tag>.md` ist da.
2. **Prüfen:** dieselben Tests, derselbe Lint und dieselben Builds wie bei jedem PR, auf genau dem Commit des Tags.
3. **Signiert bauen:** `.aab` und APK mit den Werten aus der Umgebung `release`. Der Lauf bricht ab, wenn die APK debug-signiert oder unsigniert ist, wenn die Version nicht zum Tag passt oder wenn sie mit einem anderen Zertifikat signiert ist als alle Releases davor.
4. **Veröffentlichen:** GitHub-Release mit APK, Prüfsumme und Zertifikats-Fingerabdruck. Tags `v0.*` werden Vorabversionen.

Probelauf: Er macht die Schritte 1 und 2 und baut das Release unsigniert. Er signiert nichts und veröffentlicht nichts.
```bash
gh workflow run Release --repo Detair/lauschig --ref main
```

Das echte Release:
```bash
git tag -a v0.8.0 -m "Lauschig 0.8.0" && git push origin v0.8.0
```
```bash
gh run list --repo Detair/lauschig --workflow Release --limit 1
```
Scheitert Schritt 3 an einem falschen Secret: den Wert in der Umgebung `release` korrigieren (Schritt 4 dieser Anleitung) und nur die fehlgeschlagenen Jobs neu starten. Der Tag bleibt, wo er ist.
```bash
gh run rerun <Lauf-Nummer> --repo Detair/lauschig --failed
```
Das `.aab` für Play herunterladen, wenn grün:
```bash
gh run download --repo Detair/lauschig --name lauschig-release-aab --dir ~/lauschig-release/v0.8.0
```

**Für jede weitere Version** muss `versionCode` in `app/build.gradle.kts` steigen und `versionName` angepasst werden, dazu kommt `docs/release-notes/v<version>.md`. Das mache ich beim nächsten Release mit.

Alternative ohne GitHub (lokal signieren):
```bash
LAUSCHIG_KEYSTORE=~/keys/lauschig-release.jks LAUSCHIG_KEY_ALIAS=lauschig LAUSCHIG_KEYSTORE_PASSWORD='…' LAUSCHIG_KEY_PASSWORD='…' timeout 20m ./gradlew :app:bundleRelease
```
(Passwörter landen dabei in der History – Variante über Tag bevorzugen.)

### CI und Schutzregeln (Stand 2026-11-16)

| Was | Wie |
|---|---|
| Prüfung je PR | Workflow „CI“ mit drei Jobs nebeneinander: `test` (Unit-Tests), `lint`, `build` (Debug-Build und unsignierter Release-Build mit R8). Ein neuer Push bricht den alten Lauf desselben PRs ab. |
| Nach dem Merge | Dieselben drei Jobs auf `main`, dazu `test-rmab` (alle Tests mit `-Plauschig.rmab=true`) und der Workflow „Dependency graph“. |
| Berichte | Scheitert ein Job, hängen Test- und Lint-Berichte 14 Tage am Lauf (`gh run download`). |
| `main` | Nur über einen PR, und nur wenn `test`, `lint` und `build` grün sind. Kein Force-Push, kein Löschen. |
| Tags `v*` | Lassen sich nicht löschen und nicht verschieben. |
| Actions | Nur mit festem Commit-Hash. Dependabot schlägt neue Versionen vor. |
| Abhängigkeiten | Dependabot warnt bei bekannten Lücken und öffnet Sicherheits-Updates sofort; normale Gradle-Updates einmal im Monat gebündelt. |
| Code-Scanning | CodeQL für Kotlin und die Workflow-Dateien, Funde im Reiter „Security“. |

Die Regeln für `main` und die Tags stehen unter Settings → Rules → Rulesets. Für einen Notfall lässt sich ein Regelsatz dort abschalten.

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
| App-Zugriff | „Alle oder einige Funktionen sind eingeschränkt“ → Anleitung mit dem Demo-Server `https://lauschig-demo.athene.pmind.de` (gemeinfreie Hörbücher, Prüfer-Konto `play-review`). Felder und englischen Anweisungstext für das Formular enthält das Runbook `docs/runbooks/lauschig-demo.md` im Repo `kdorf/athene`. Das Passwort steht in OpenBao, nie in diesem Repo. |
| Werbung | Nein |
| Einstufung des Inhalts | Fragebogen ausfüllen: Kategorie „Referenz, Nachrichten oder Bildung“ bzw. „Sonstige“, alle Inhaltsfragen „Nein“ (Nutzer laden eigene Inhalte vom eigenen Server; keine Nutzerinteraktion untereinander). |
| Zielgruppe | 18+ (bzw. „nicht für Kinder“); App spricht Kinder nicht gezielt an. |
| Datensicherheit | „Keine Daten erhoben oder geteilt“, Verschlüsselung bei Übertragung: Ja, Löschung: Ja (Deinstallation) – Details siehe Datei. |
| Nachrichten-App / Behörden / Finanzen / Gesundheit | Nein |
| Kamera-Berechtigung | Stand 2026-11-15, in der Console ungeprüft: Für `CAMERA` gibt es kein eigenes Erklärformular. Begründung, falls Google fragt: „Scannt bei der Einrichtung den QR-Code eines anderen Geräts mit den Server-Adressen. Die Bilder werden nur auf dem Gerät ausgewertet, nicht gespeichert und nicht gesendet. Die App fragt erst beim Tippen auf ‚QR-Code scannen‘.“ In der Datensicherheit bleibt „Fotos und Videos“ auf „nicht erhoben“. Die Kamera ist kein Pflicht-Merkmal, Geräte ohne Kamera bekommen die App. Format des Codes: `docs/server-qr-code.md`. |
| Dienste im Vordergrund | Medienwiedergabe (Abspielen von Hörbüchern) und Datensynchronisierung (Downloads auf Nutzerwunsch). Falls ein Video verlangt wird: kurze Bildschirmaufnahme von Abspielen + Download. |

### 6.3 Store-Eintrag (Wachstum → Store-Präsenz → Haupt-Store-Eintrag)
- Kurz- und Langbeschreibung aus `docs/play-store/listing-de.md` (Englisch: `listing-en.md` als Übersetzung hinzufügen).
- App-Symbol 512×512 PNG, Funktionsgrafik 1024×500, mind. 2 Screenshots vom Handy (aus Schritt 1). Die Grafiken fehlen noch – sag Bescheid, dann erstelle ich Entwürfe.
- Kontakt-E-Mail (wird öffentlich angezeigt) – am besten eine eigene Adresse für die App.
- Kategorie: **Bücher & Nachschlagewerke** (oder Musik & Audio).

### 6.4 Interner Test für die Familie
1. Testen → **Interner Test** → Tab „Tester“ → „E-Mail-Liste erstellen“, Name „Familie“, die Google-Konten der Familie eintragen (die im Play Store des jeweiligen Handys angemeldet sind) → speichern, Liste anhaken.
2. „Feedback-URL / E-Mail“: `https://github.com/Detair/lauschig/issues` oder deine Mail.
3. Tab „Releases“ → „Neuen Release erstellen“. Bietet die Console den Schlüsselwechsel aus 6.5 schon jetzt an, mach ihn vorher.
   - `.aab` aus `~/lauschig-release/v0.8.0/` hochladen.
   - Release-Name: `0.8.0`, Versionshinweise (de-DE): *Testversion: Bibliothek, Downloads, Offline-Wiedergabe, Fortschritts-Sync, mehrere Profile.*
   - Speichern → Überprüfen → **Einführung starten**.
4. Unter „Tester“ den **Link zur Teilnahme** kopieren und der Familie schicken. Jeder öffnet ihn am Handy (mit dem eingetragenen Google-Konto), tippt „Tester werden“ und installiert dann über den Play-Store-Link.

Interne Tests sind meist nach Minuten verfügbar. Fehlende Pflichtangaben zeigt die Console oben als Warnung – alle abarbeiten, bis „Einführung starten“ möglich ist.

**Dein eigenes Handy:** Der Debug-Build aus Schritt 1 hat eine andere Signatur als die Play-Version. Vor der Installation aus dem Play Store den Debug-Build deinstallieren (Downloads und Anmeldung gehen dabei verloren):
```bash
adb uninstall io.wolftown.lauschig
```

### 6.5 Eigenen App-Signaturschlüssel hinterlegen

Ohne dein Zutun signiert Play neue Apps mit Schlüsseln, die Google erzeugt. Dann passt die Play-Version nicht zu den GitHub-APKs. Google erlaubt den Wechsel auf den eigenen Schlüssel nur, **bevor** ein Release im offenen Test oder in der Produktion ausgerollt ist. Am besten direkt nach 6.1, spätestens nach dem ersten internen Test:

1. In der Console: **Geschützt durch Play → Play Store-Schutz → Play App Signing verwalten** (englisch „Protected with Play → Play Store protection → Manage Play app signing“). Die deutschen Bezeichnungen können leicht abweichen. Du brauchst dafür Admin-Rechte.
2. **Schlüssel ändern** („Change key“) → **eine Kopie deines App-Signaturschlüssels bereitstellen** („Provide a copy of your app signing key“) und den Anweisungen folgen, mit `~/keys/lauschig-release.jks` und dem Alias `lauschig` aus Schritt 3.
3. **Fragt die Console nach einem eigenen Upload-Schlüssel**, sag mir Bescheid. Google empfiehlt, Upload- und App-Signaturschlüssel zu trennen. Dann bekommt die CI einen zweiten Schlüssel nur fürs Hochladen.
4. **Hybrid-Signatur prüfen:** Neue Apps nimmt Play automatisch in eine „quantensichere“ Hybrid-Signatur auf. Google dokumentiert nicht, wie sie mit einem eigenen Schlüssel zusammenspielt. Schau, was die Seite nach dem Wechsel anzeigt, und schick mir einen Screenshot.
5. **Der eigentliche Beweis:** Auf einem Handy die Play-Version aus dem internen Test installieren, dann die GitHub-APK derselben oder einer höheren Version darüber (`adb install -r lauschig-0.8.0.apk`). Meldet Android „App nicht installiert“ oder adb `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, passen die Signaturen nicht. Am besten einmal auf Android 17 (dort prüft Android die neue Signatur v3.2) und einmal auf einem älteren Android. Erst danach sagen wir den Testern, dass sie ohne Neuinstallation wechseln können.

### 6.6 Später: öffentliche Veröffentlichung
Neue private Entwicklerkonten müssen vor der Produktion einen **geschlossenen Test** mit mindestens 12 Testern über 14 Tage durchführen (Stand meines Wissens – in der Console unter „Produktion“ steht die aktuelle Anforderung). Für die Familie ist das nicht nötig; der interne Test hat keine solche Hürde.

## SSO aktivieren (Audiobookshelf mit OpenID)

Nutzt dein Audiobookshelf OpenID (z. B. Authentik), zeigt Lauschig unter dem Passwort-Login den Knopf „Mit SSO anmelden“.
Damit der Server Lauschig zulässt, muss ein Admin die Rückkehr-Adresse freigeben:

1. In Audiobookshelf: Einstellungen → Authentifizierung → OpenID Connect.
2. Bei „Allowed Mobile Redirect URIs“ den Eintrag `lauschig://oauth` hinzufügen (der Standard enthält nur `audiobookshelf://oauth`).
3. Speichern. Ohne diesen Eintrag zeigt Lauschig: „Dein Server erlaubt Lauschig noch nicht für SSO.“

Hinweis: Audiobookshelf erwartet `code` und `code_verifier` als URL-Parameter; ein Reverse-Proxy vor Audiobookshelf sollte daher für `/auth/openid/callback` keine Query-Strings loggen.

## RMAB (Bände anfragen): abgeschaltet, nur mit Build-Schalter

Stand 2026-11-15: ReadMeABook (RMAB) ist für den Start abgeschaltet. Der Code bleibt im Repo (`app/src/main/java/io/wolftown/lauschig/rmab/`). Der normale Build lässt die Funktion weg, auch der Release-Workflow.

| | Normaler Build (Schalter aus) | Build mit `-Plauschig.rmab=true` |
|---|---|---|
| Einstellungen | kein Abschnitt „ReadMeABook“ | Abschnitt wie bisher |
| Fehlender Band einer Serie | sichtbar, ohne „Anfragen“ und ohne Statussymbol | mit „Anfragen“ und Statussymbol |
| Netzwerk | kein Aufruf an einen RMAB-Server | wie bisher |
| Gespeicherte RMAB-Verbindungen | werden beim nächsten App-Start gelöscht, für alle Profile | bleiben |

So schaltest du RMAB ein:

```bash
./gradlew assembleDebug -Plauschig.rmab=true
```

Dauerhaft geht es mit der Zeile `lauschig.rmab=true` in `~/.gradle/gradle.properties`. Erlaubt sind nur `true` und `false`.

Wer RMAB mit einer älteren Version verbunden hatte: Der API-Token „Lauschig (<Gerät>)“ bleibt auf dem RMAB-Server gültig. Lösche ihn dort unter den API-Tokens des Nutzers.

**Nur mit Schalter:** Beim Verbinden mit ReadMeABook erzeugt Lauschig einen dauerhaften API-Token namens „Lauschig (<Gerät>)“; dein RMAB-Passwort wird nicht gespeichert. Der Token ist in RMAB unter den API-Tokens des Nutzers sichtbar und lässt sich dort jederzeit widerrufen.

Kommt RMAB zurück, gehört dazu: den Standard des Schalters in `app/build.gradle.kts` umdrehen, RMAB wieder in die Datenschutzerklärung und in `docs/play-store/data-safety.md` aufnehmen und es in „Was ist neu“ nennen.

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
- [ ] 3 Signaturschlüssel angelegt und an zwei Orten gesichert
- [ ] 4 Vier GitHub-Secrets gesetzt
- [ ] 5 Probelauf grün, Tag `v0.8.0` → GitHub-Release mit APK, signiertes `.aab` heruntergeladen
- [ ] 6 Play Console: App, App-Inhalte, Store-Eintrag, interner Test, Familie eingeladen
- [ ] 6.5 Eigener App-Signaturschlüssel in Play hinterlegt, Wechsel GitHub → Play auf einem Handy geprüft
- [ ] 7 Markenprüfung ohne Konflikt
