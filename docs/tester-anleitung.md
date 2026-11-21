# Lauschig testen – Anleitung für Tester

Stand: 2026-11-21. Teil A geht mit jeder Version ab 0.6.0. Teil B braucht Version 0.7.0 oder neuer. Der Abschnitt „Einrichten mit dem Handy“ braucht eine Version, die am Handy unter Einstellungen „Fernseher einrichten“ zeigt.

Fehler und Auffälligkeiten bitte als [Issue auf GitHub](https://github.com/Detair/lauschig/issues) melden oder direkt an Fabian. Hilfreich: Gerät, Android-Version, was du getan hast, was du erwartet hast, was passiert ist. Am Handy dazu das Diagnose-Log (Einstellungen → Über → Diagnose-Log teilen).

## Teil A: Server-Adressen per QR-Code (zwei Handys oder Handy und Tablet, ab Version 0.6.0)

Du brauchst: zwei Android-Geräte (Android 9 oder neuer) und ein Konto auf einem Audiobookshelf-Server.

1. Auf beiden Geräten die aktuelle APK von <https://github.com/Detair/lauschig/releases> installieren.
2. Gerät 1: anmelden wie gewohnt (Adressen, Benutzername, Passwort).
3. Gerät 1: Einstellungen → Verbindungsstatus → „Server-Adressen als QR-Code“. Der Code bleibt offen.
4. Gerät 2: App zum ersten Mal starten, bei der Einrichtung „QR-Code scannen“ antippen und die Kamera erlauben.
5. Gerät 2 auf den Code von Gerät 1 richten.

Bitte notieren:

| Frage | Antwort |
|---|---|
| Erkennt die Kamera den Code? Wie schnell ungefähr? | |
| Stehen danach beide Adressen richtig in den Feldern? | |
| Bleiben Benutzername und Passwort leer (so soll es sein)? | |
| Klappt es auch bei wenig Licht und aus einem halben Meter Abstand? | |
| Was passiert, wenn du die Kamera nicht erlaubst? Erwartet: ein Hinweis, Tippen geht weiter. | |
| Geräte und Android-Versionen | |

## Teil B: Google TV / Android TV (ab Version 0.7.0)

Du brauchst: ein Gerät mit Google TV oder Android TV (Android 9 oder neuer), die Fernbedienung und ein Konto auf einem Audiobookshelf-Server.

### Installieren

Die App ist noch nicht im Play Store. Am einfachsten:

1. Am Fernseher die App „Downloader“ (von AFTVnews) aus dem Play Store installieren und ihr erlauben, unbekannte Apps zu installieren.
2. Im Downloader die Adresse der APK eingeben. Sie steht auf der [Release-Seite](https://github.com/Detair/lauschig/releases) unter „Assets“.
3. Installieren, dann „Lauschig“ in der App-Reihe des Fernsehers öffnen.

Mit einem Rechner geht es auch über `adb install lauschig-0.7.0.apk`.

### Was die App am Fernseher können soll

1. **Einrichten:** mit dem Handy (siehe unten) oder Adressen, Benutzername und Passwort mit der Fernbedienung tippen.
2. **Start:** Reihen „Weiterhören“, „Nächster Band“, „Neu hinzugefügt“, „Serien“.
3. **Stöbern:** Bibliothek, Serie, Titelseite, Suche.
4. **Player:** OK = Pause/Weiter, links/rechts = spulen, runter = Kapitel, Tempo, Schlaf-Timer.
5. **Einstellungen:** Profil wechseln, PIN, „Bild aus nach“, Verbindung, Abmelden.

### Prüfliste

Bitte jede Zeile kurz beantworten. Die ersten Fragen kann kein Emulator beantworten, sie sind am wichtigsten.

| # | Prüfung | Ergebnis |
|---|---|---|
| 1 | Gerät, Android-Version, Ton über Fernseher, Soundbar oder Verstärker? | |
| 2 | Erscheint Lauschig mit Banner in der App-Reihe? | |
| 3 | Einrichtung nur mit der Fernbedienung: Kommt man durch alle Felder und zum Knopf „Anmelden“? Stört die Bildschirmtastatur? | |
| 4 | Ist auf jeder Seite klar sichtbar, wo der Fokus steht? Gab es eine Stelle, an der er „verschwand“? | |
| 5 | Führt Zurück überall eine Seite zurück und von der Startseite aus der App? | |
| 6 | Player: OK, links, rechts, runter wie oben beschrieben? Wirken die Tasten Play/Pause und Spulen der Fernbedienung? | |
| 7 | **Langer Lauf:** eine Stunde am Stück hören. Startet der Bildschirmschoner? Läuft der Ton weiter? Geht der Fernseher oder das Gerät von selbst in den Ruhezustand? Nach welcher Zeit? | |
| 8 | **Bild aus:** Wird die Player-Seite nach der eingestellten Zeit schwarz? Kommt das Bild mit einer Taste zurück, ohne dass die Wiedergabe pausiert oder spult? | |
| 9 | Zur Startseite des Fernsehers wechseln, während Ton läuft: Zeigt das System eine Wiedergabe-Karte? Führt sie zurück in den Player? | |
| 10 | Schlaf-Timer auf 15 Minuten: Blendet der Ton aus? Verlängert OK während des Ausblendens? | |
| 11 | Gerät aus- und wieder einschalten: Ist man noch angemeldet? Geht „Weiterhören“ an der richtigen Stelle weiter? | |
| 12 | Mit zwei Profilen: Fragt der Fernseher beim Start „Wer hört?“ Fragt ein Profil mit PIN die PIN? | |
| 13 | Suche mit der Tastatur des Fernsehers und, falls vorhanden, mit der Sprach-Eingabe der Tastatur. | |
| 14 | Wirkt etwas zu klein, abgeschnitten oder am Rand zu knapp? Bitte ein Foto. | |
| 15 | Stockt etwas (Rollen durch die Bibliothek, Cover laden)? Stürzt die App ab? | |

### Einrichten mit dem Handy

Du brauchst: den Fernseher, ein Android-Handy mit Lauschig und einem Konto mit Passwort (nicht SSO). Beide im selben Heimnetz.

1. Am Fernseher Lauschig öffnen. Die Einrichtung zeigt einen QR-Code und eine vierstellige Zahl.
2. Am Handy: Einstellungen → „Fernseher einrichten“ → „Code scannen“.
3. Die Kamera auf den Code richten.
4. Am Handy die Zahl mit der am Fernseher vergleichen, das Passwort tippen und „Fernseher einrichten“ antippen.
5. Am Fernseher erscheint „Diesen Fernseher einrichten?“ mit den Adressen des Servers. „Einrichten“ wählen.

Wie das funktioniert, steht in [`tv-einrichtung-mit-dem-handy.md`](tv-einrichtung-mit-dem-handy.md).

| # | Prüfung | Ergebnis |
|---|---|---|
| H1 | Scan aus Sofa-Entfernung: Erkennt das Handy den Code am Fernseher aus zwei bis drei Metern? Wie schnell ungefähr? | |
| H2 | Ist die Zahl am Handy dieselbe wie am Fernseher? | |
| H3 | Falsches Passwort: Sagt das Handy „Das Passwort stimmt nicht.“ und ist das Feld danach leer? Klappt es danach mit dem richtigen Passwort, ohne neu zu scannen? | |
| H4 | Nur Mobilfunk: am Handy WLAN aus, dann senden. Erwartet: „Das Handy ist nicht per WLAN oder Kabel im Heimnetz.“ Es geht nichts weg, auch keine Anmeldung am Server. | |
| H4b | Gäste-WLAN: Handy im Gäste-WLAN, Fernseher im normalen Netz. Ein Gäste-WLAN zählt als Heimnetz, das Handy versucht es also. Was sagt es? Meist erwartet: „Das Handy erreicht den Fernseher nicht. …“ (wenn das Gäste-WLAN abgeschottet ist) oder „Die App ist gerade nicht mit dem Server verbunden. …“ | |
| H5 | Abgelaufener Code: Code scannen, dann mehr als 5 Minuten warten oder am Fernseher „Neuer Code“ wählen, dann senden. Erwartet: Der Fernseher meldet sich nicht an. Das Handy sagt, dass der Code abgelaufen ist oder es den Fernseher nicht erreicht, und rät zum neuen Scan. | |
| H6 | Erfolg: Steht am Handy beim Senden „Bestätige die Einrichtung jetzt am Fernseher.“? Nach „Einrichten“ am Fernseher: Zeigt der Fernseher die Startseite und das Handy „Der Fernseher ist eingerichtet.“? | |
| H7 | Rückfrage abbrechen: Am Fernseher „Abbrechen“ wählen. Erwartet am Handy: „Am Fernseher wurde abgebrochen oder nicht bestätigt.“ Am Fernseher: „Die Einrichtung wurde abgebrochen. Zum Server ging nichts.“ | |
| H8 | Rückfrage mit Zurück beantworten. Erwartet: wie H7. | |
| H9 | Rückfrage liegen lassen: eine Minute lang nichts drücken. Erwartet: wie H7, nach etwa 60 Sekunden. | |
| H10 | Steht der Fokus auf der Rückfrage zuerst auf „Abbrechen“? Sind `http://`-Adressen als unverschlüsselt markiert? | |

### Bekannte Grenzen der ersten TV-Version

- Anmeldung über SSO geht am Fernseher noch nicht, auch nicht über das Handy.
- Keine Downloads, keine Podcasts, keine Statistik, kein Cast am Fernseher.
