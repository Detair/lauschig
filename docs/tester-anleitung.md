# Lauschig testen – Anleitung für Tester

Stand: 2026-11-17. Teil A geht mit jeder Version ab 0.6.0. Teil B braucht Version 0.7.0 oder neuer.

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

1. **Einrichten:** Adressen, Benutzername und Passwort mit der Fernbedienung tippen.
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

### Bekannte Grenzen der ersten TV-Version

- Die Einrichtung mit dem Handy (QR-Code am Fernseher) kommt in der nächsten Version. Jetzt wird getippt.
- Anmeldung über SSO geht am Fernseher noch nicht.
- Keine Downloads, keine Podcasts, keine Statistik, kein Cast am Fernseher.
