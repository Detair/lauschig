# Server-Adressen als QR-Code

Stand: 2026-11-15

Ein angemeldetes Gerät zeigt die Adressen seines Audiobookshelf-Servers als QR-Code. Ein neues Gerät scannt ihn bei der Einrichtung, statt die Adressen zu tippen. Diese Seite beschreibt, was im Code steht, damit man ihn auch mit einem anderen Werkzeug erzeugen kann.

## Auf einen Blick

| | |
|---|---|
| Zeigen | Einstellungen → Verbindungsstatus → „Server-Adressen als QR-Code“ (nur mit angemeldetem Konto) |
| Scannen | Einrichtungsformular (erste Einrichtung und „Profil hinzufügen“) → „QR-Code scannen“. Nicht bei „PIN vergessen“: dort steht die Adresse fest. |
| Inhalt | nur die lokale und die öffentliche Adresse des Servers |
| Nie im Code | Benutzername, Passwort, Token, Profilname, WLAN-Namen |
| Wer liest ihn | nur der Scanner in Lauschig; ein Link von außen öffnet die Einrichtung nicht |

## Das Format

Der QR-Code enthält eine Zeile Text:

```
lauschig://server?v=1&local=<lokale Adresse>&public=<öffentliche Adresse>
```

| Teil | Bedeutung | Pflicht |
|---|---|---|
| `lauschig://server` | Kennung. Alles andere ist kein Server-Code. | ja |
| `v=1` | Version des Formats. Lauschig liest nur `1`. | ja |
| `local=` | Adresse im Heimnetz, zum Beispiel `http://192.168.1.20:13378` | eine von beiden |
| `public=` | Adresse von unterwegs, zum Beispiel `https://abs.example.org/audiobookshelf` | eine von beiden |

Regeln für die Adressen:

1. Eine Adresse steht so da wie im Formular: mit `http://` oder `https://`, mit Port und Pfad, falls der Server sie braucht.
2. Sie ist prozent-kodiert (`:` wird `%3A`, `/` wird `%2F`). Lauschig liest auch unkodierte Adressen, solange sie kein `&` und kein `#` enthalten.
3. Die Reihenfolge der Teile ist egal. Unbekannte Teile verwirft Lauschig, ohne sie zu speichern.
4. Der ganze Text ist höchstens 1024 Zeichen lang.

Beispiel mit beiden Adressen:

```
lauschig://server?v=1&local=http%3A%2F%2F192.168.1.20%3A13378&public=https%3A%2F%2Fabs.example.org%2Faudiobookshelf
```

## Was Lauschig ablehnt

| Inhalt | Meldung in der App |
|---|---|
| ein anderer QR-Code (Webseite, WLAN, …) oder ein Teil kommt doppelt vor | „Das ist kein Server-Code von Lauschig.“ |
| `v` fehlt oder ist nicht `1` | „Dieser Code stammt von einer anderen Lauschig-Version.“ |
| keine Adresse | „Der Code enthält keine Server-Adresse.“ |
| Adresse ohne `http://` oder `https://`, mit `?…` oder `#…`, mit Leerzeichen oder kaputter Kodierung | „Der Code enthält keine gültige Server-Adresse.“ |
| Adresse mit eingebettetem Benutzer (`https://name:passwort@…`) | „Der Code enthält Zugangsdaten in der Adresse. Er wird nicht übernommen.“ |
| mehr als 1024 Zeichen | „Der Code ist zu lang.“ |

Ist eine der beiden Adressen ungültig, übernimmt Lauschig keine.

## Was beim Scannen passiert

1. Die Kamera-Berechtigung fragt die App erst, wenn du „QR-Code scannen“ antippst. Bei Nein tippst du die Adressen von Hand.
2. Der Scan füllt nur die zwei Adressfelder. Benutzername und Passwort gibst du selbst ein.
3. Nichts wird von selbst abgeschickt. Danach läuft alles wie beim Tippen: Prüfung der Adresse, Hinweis bei `http://`, Erreichbarkeit, Erkennung von SSO.
4. Prüfe die Adressen im Formular, bevor du dich anmeldest. Ein fremder QR-Code kann auf einen fremden Server zeigen.
5. Kamerabilder werden nicht gespeichert und nicht gesendet. Der gescannte Text steht nicht im Diagnose-Log.

## Den Code selbst erzeugen

Mit `qrencode` auf dem Rechner:

```bash
qrencode -o lauschig-server.png 'lauschig://server?v=1&local=http%3A%2F%2F192.168.1.20%3A13378&public=https%3A%2F%2Fabs.example.org%2Faudiobookshelf'
```

Direkt im Terminal anzeigen:

```bash
qrencode -t ANSIUTF8 'lauschig://server?v=1&public=https%3A%2F%2Fabs.example.org%2Faudiobookshelf'
```

Eine Adresse prozent-kodieren:

```bash
python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' 'https://abs.example.org/audiobookshelf'
```

## Technik

| Teil | Ort |
|---|---|
| Format, Leser und Erzeuger | `app/src/main/java/io/wolftown/lauschig/serverqr/ServerQr.kt` |
| QR-Bild und Dekoder | `serverqr/QrCode.kt` und `serverqr/ServerQrUi.kt` (ZXing `core`, Apache-2.0). Der Code entsteht auf dem Gerät, kein Webdienst. |
| Kamera | `serverqr/ServerQrScanner.kt` (CameraX, Apache-2.0); keine Google-Play-Dienste, kein ML Kit |
| Tests | `app/src/test/java/io/wolftown/lauschig/serverqr/` |
