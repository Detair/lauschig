# Fernseher mit dem Handy einrichten

Stand: 2026-11-21

Der Fernseher zeigt einen QR-Code und eine vierstellige Zahl. Das Handy scannt den Code, du tippst dein Passwort am Handy, und der Fernseher ist angemeldet. Mit der Fernbedienung tippst du dann nichts mehr. Diese Seite beschreibt den Ablauf, das Format und die Grenzen des Verfahrens.

## Auf einen Blick

| | |
|---|---|
| Fernseher | erste Seite der Einrichtung, „Mit dem Handy einrichten“: QR-Code und Zahl, zum Beispiel „48 21“ |
| Handy | Einstellungen → „Fernseher einrichten“ (nur mit angemeldetem Konto) |
| Was zum Fernseher geht | die Adressen des Servers und eine **neue Anmeldung nur für den Fernseher** (ein Refresh-Token, siehe unten) |
| Was nie zum Fernseher geht | dein Passwort, die Anmeldung des Handys, WLAN-Namen |
| Weg | verschlüsselt und direkt im Heimnetz vom Handy zum Fernseher; kein Server dazwischen, nie über das Internet |
| Wer liest den Code | nur der Scanner in „Fernseher einrichten“; ein Link von außen öffnet nichts |
| Wie lange gilt ein Code | höchstens 5 Minuten, und nur für ein einziges angenommenes Paket |
| SSO-Konten | können den Fernseher noch nicht einrichten. Am Fernseher tippen geht weiter, aber auch dort ohne SSO. |

Begriffe:

- **Refresh-Token:** ein langes Zufallswort, mit dem sich ein Gerät beim Server neue Zugangsschlüssel holt. Es ersetzt das Passwort für genau eine Anmeldung. Der Server kann diese Anmeldung beenden, ohne dass sich am Passwort etwas ändert.
- **Heimnetz:** das Netz zu Hause, erreichbar per WLAN oder Kabel. Mobilfunk und VPN zählen nicht.

## Ablauf für Menschen

1. Am Fernseher: Lauschig öffnen. Die Einrichtung beginnt mit „Mit dem Handy einrichten“. Der Fernseher zeigt einen QR-Code und eine Zahl.
2. Am Handy: Lauschig öffnen, Einstellungen → „Fernseher einrichten“.
3. „Code scannen“ antippen. Beim ersten Mal fragt das Handy nach der Kamera.
4. Die Kamera auf den Code am Fernseher halten.
5. Das Handy fragt: „Diesen Fernseher (Zahl 48 21) mit dem Server … und dem Konto … einrichten?“
6. **Die Zahl vergleichen.** Steht am Fernseher eine andere Zahl, hier abbrechen.
7. Das Passwort des Kontos eintippen und „Fernseher einrichten“ antippen.
8. Der Fernseher meldet sich an und zeigt die Startseite. Das Handy sagt „Der Fernseher ist eingerichtet.“
9. Das Handy vergisst den Fernseher wieder. Es speichert nichts davon.

Klappt etwas nicht, sagt das Handy warum. Mit „Noch einmal scannen“ geht es von vorn los. Der Fernseher zeigt dann einen neuen Code.

## Was dabei im Hintergrund passiert

1. Das Handy liest den Code. Es merkt sich Adresse, Port und Schlüssel des Fernsehers nur im Speicher.
2. Das Handy meldet sich mit dem Benutzernamen des Kontos und dem getippten Passwort **selbst** bei seinem Server an. Es fragt zuerst die lokale Adresse, dann die öffentliche. Eine `http://`-Adresse nutzt es nur, wenn sie für das Konto schon bestätigt ist.
3. Der Server legt dabei eine neue, eigene Sitzung an. Ihr Refresh-Token ist für den Fernseher bestimmt.
4. Das Handy verschlüsselt die Server-Adressen und dieses Token mit dem Schlüssel aus dem Code und schickt genau ein Paket an den Fernseher.
5. Der Fernseher entschlüsselt das Paket und tauscht das Token beim Server sofort gegen eigene Zugangsschlüssel. Dann antwortet er dem Handy mit einem Byte.
6. Sagt die Antwort nicht „angemeldet“, beendet das Handy die neue Sitzung am Server wieder.

## Das Format

### Code am Fernseher

Der QR-Code enthält eine Zeile Text, höchstens 256 Zeichen:

```
lauschig://tv?v=1&a=192.168.1.50&p=41234&k=<öffentlicher Schlüssel>
```

| Teil | Bedeutung | Regel |
|---|---|---|
| `lauschig://tv` | Kennung. Alles andere ist kein Code eines Fernsehers. | Pflicht |
| `v=1` | Version des Formats. Lauschig liest nur `1`. | Pflicht |
| `a=` | IPv4-Adresse des Fernsehers | vier Zahlen 0–255 mit Punkten, ohne führende Nullen, nur aus 10.0.0.0/8, 172.16.0.0/12 oder 192.168.0.0/16 |
| `p=` | Port, auf dem der Fernseher wartet | 1024–65535 |
| `k=` | öffentlicher X25519-Schlüssel des Fernsehers, nur für diesen einen Code | genau 32 Bytes, Base64-URL ohne Füllzeichen (43 Zeichen) |

- `v`, `a`, `p` und `k` kommen genau einmal vor. Ein doppelter Teil lehnt den Code ab.
- Unbekannte Teile verwirft Lauschig, ohne sie zu speichern.
- Leerzeichen davor und dahinter zählen nicht. Alles ab einem `#` verwirft Lauschig.

### Die Zahl

Beide Geräte rechnen sie aus dem Code aus. Niemand tippt sie.

1. Text bilden: `"lauschig-tv-number-v1\n" + a + "\n" + p + "\n" + k`. Dabei steht `k` so da wie im Code, `p` als Dezimalzahl.
2. SHA-256 über diesen Text (UTF-8).
3. Die ersten vier Bytes als vorzeichenlose Zahl lesen (Big-Endian).
4. Modulo 10000, mit führenden Nullen auf vier Stellen.

Beide Geräte zeigen sie in zwei Gruppen, zum Beispiel „48 21“. Tauscht jemand den Code gegen seinen eigenen, ergibt sich eine andere Zahl.

### Das Paket vom Handy zum Fernseher

| Schritt | Inhalt |
|---|---|
| Klartext (JSON) | `{"v":1,"local":"http://192.168.1.20:13378","public":"https://abs.example.org","refresh":"<Refresh-Token>"}` |
| Klartextblock | genau 2048 Bytes: 2 Bytes Länge `n` (Big-Endian), dann `n` Bytes JSON (UTF-8), dann Nullen |
| Verschlüsselung | HPKE (RFC 9180), Basis-Modus: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305. Aus der Bibliothek Tink, Variante ohne Präfix. |
| Kontext (`info`) | UTF-8 von `"lauschig-tv-setup-v1\n" + a + "\n" + p` |
| Paket | genau 2100 Bytes: die vier ASCII-Bytes `LTV1`, dann 32 Bytes gekapselter Schlüssel, 2048 Bytes Geheimtext, 16 Bytes Prüfsumme |

HPKE heißt „Hybrid Public Key Encryption“: Das Handy verschlüsselt mit dem öffentlichen Schlüssel aus dem Code. Nur der Fernseher hat den passenden privaten Schlüssel. Er liegt nur in seinem Speicher und nur, solange der Code gilt.

Regeln für den Klartext:

1. `local` und `public` dürfen fehlen. Mindestens eine Adresse muss da sein.
2. Eine Adresse steht so da wie im Einrichtungsformular: mit `http://` oder `https://`, mit Port und Pfad, ohne `/` am Ende. Es gelten dieselben Regeln wie beim [Server-QR-Code](server-qr-code.md): kein Benutzer in der Adresse, kein `?…`, kein `#…`.
3. `refresh` ist nicht leer.
4. Unbekannte Felder verwirft der Fernseher.
5. Weil der Block immer 2048 Bytes hat, verrät die Länge des Pakets nichts über Token oder Adressen.

### Die Antwort des Fernsehers

Ein Byte, danach schließt der Fernseher die Verbindung.

| Byte | Bedeutung | Was das Handy sagt |
|---|---|---|
| `1` | angemeldet | „Der Fernseher ist eingerichtet.“ |
| `2` | Paket nicht angenommen (alter Code, falscher Schlüssel, kaputtes Paket) | Der Code ist vielleicht abgelaufen. Neuen Code scannen. |
| `3` | Der Fernseher erreicht den Server nicht. | „Der Fernseher erreicht den Server nicht.“ |
| `4` | Der Server lehnt die Anmeldung ab. | „Der Server lehnt die Anmeldung des Fernsehers ab.“ |
| `5` | anderer Fehler | „Das hat nicht geklappt.“ |
| keine | Verbindung bricht ab oder 30 s Stille | Am Fernseher nachsehen, ob er angemeldet ist. |

Bei allem außer `1` beendet das Handy die neue Sitzung am Server.

### Feste Werte

| Wert | Grenze |
|---|---|
| Code gilt | höchstens 5 Minuten |
| angenommene Pakete je Code | genau eins |
| abgelehnte Verbindungen je Code | höchstens 20, danach gilt der Code nicht mehr |
| Lesefrist je Verbindung am Fernseher | 5 s insgesamt |
| Verbindungsaufbau vom Handy | 5 s |
| Warten auf die Antwort | 30 s |
| Anmeldung des Handys am Server | 5 s je Adresse |

## Was Lauschig ablehnt

Beim Scannen am Handy:

| Inhalt | Meldung in der App |
|---|---|
| der Server-QR-Code (`lauschig://server?…`) | „Das ist der Code eines Servers, nicht der eines Fernsehers.“ |
| `v` fehlt oder ist nicht `1` | „Dieser Code stammt von einer anderen Version von Lauschig. …“ |
| eine gültige IPv4-Adresse außerhalb der drei privaten Netze | „Die Adresse des Fernsehers liegt nicht in einem Heimnetz. Lauschig sendet nur im Heimnetz.“ |
| ein anderer QR-Code, ein doppelter Teil, ein Port oder Schlüssel, der nicht passt, ein Name statt einer Adresse, mehr als 256 Zeichen | „Das ist kein Code eines Fernsehers.“ |

Beim Senden:

| Lage | Meldung in der App |
|---|---|
| falsches Passwort (der Server antwortet 401 oder 403) | „Das Passwort stimmt nicht.“ Das Feld leert sich, der Code gilt weiter. |
| Server unter keiner Adresse erreichbar | „Der Server ist nicht erreichbar. …“ Nichts geht zum Fernseher. |
| der Server meldet einen anderen Benutzer als den des Kontos, oder kein Refresh-Token | „Das hat nicht geklappt.“ Die Sitzung wird beendet, nichts geht zum Fernseher. |
| das Handy ist nicht per WLAN oder Kabel im Heimnetz (nur Mobilfunk oder VPN) | „Das Handy ist nicht per WLAN oder Kabel im Heimnetz. …“ Kein Paket verlässt das Handy. |
| der Fernseher ist nicht erreichbar, auch weil sein Code schon ersetzt ist und der Port zu | „Das Handy erreicht den Fernseher nicht. …“ mit dem Rat, einen neuen Code zu scannen. Die Sitzung wird beendet. |

Der Fernseher liest je Verbindung genau 2100 Bytes. Er lehnt ab, was in der Lesefrist weniger liefert, nicht mit `LTV1` beginnt, sich nicht entschlüsseln lässt oder im Klartext gegen eine Regel oben verstößt. Darauf antwortet er mit `2`.

## Sicherheitsregeln und bekannte Grenzen

Entschieden beim Bau am 2026-11-20 (Spec `docs/superpowers/specs/2026-11-16-google-tv-design.md`, E13 bis E19):

| # | Regel | Grund |
|---|---|---|
| E13 | Das Passwort verlässt das Handy nur in Richtung des eigenen Servers. Zum Fernseher geht eine neue, eigene Sitzung. | Ein falscher Code bekommt so höchstens eine Sitzung, die sich beenden lässt, nie das Passwort. Die Sitzung des Handys selbst wandert nie. |
| E14 | SSO-Konten können den Fernseher noch nicht einrichten. | Der Weg über zwei Geräte ist noch nicht geprüft. |
| E15 | Vor dem Senden fragt das Handy keine Profil-PIN. | Es fragt das Passwort des Kontos. Das ist die stärkere Frage. |
| E16 | Ein Code gilt für ein angenommenes Paket und höchstens 5 Minuten. | Einmal-Schlüssel. Bei einem Fehler scannt man neu. |
| E17 | Der Fernseher fragt nach der Übergabe nicht noch einmal nach. | Wer den Code sieht und im Heimnetz ist, kann auch mit der Fernbedienung tippen. |
| E18 | Nur private IPv4-Adressen, nur über WLAN oder Kabel, nie über Mobilfunk oder VPN, kein Auflösen von Namen. | „Nur im Heimnetz“ ist eine Regel im Code, keine Absicht. |
| E19 | Die Antwort des Fernsehers ist nur ein Hinweis. Was gilt, zeigt der Fernseher. | Eine gefälschte Antwort ändert nichts an dem, was der Fernseher tut. |

Weitere Regeln:

1. Nur der Scanner in „Fernseher einrichten“ liest einen Code. Die App hat keinen Link-Filter für `lauschig://tv`.
2. Der Code, das Passwort und das Token stehen in keinem Log, keiner Fehlermeldung und keiner Datei. Nach dem Senden steht das Passwort nirgends mehr.
3. Das Handy benutzt das Zugriffstoken der neuen Sitzung nie. Es reicht nur das Refresh-Token weiter.
4. Wird die Seite am Handy während des Sendens verlassen, läuft die Übergabe zu Ende. Eine fehlgeschlagene Übergabe beendet die Sitzung trotzdem.
5. Kein selbst gebautes Krypto-Verfahren: nur die öffentliche HPKE-Schnittstelle von Tink.

**Größtes Risiko:** ein fremder QR-Code, der sich als Fernseher ausgibt. Wer ihn scannt und das Passwort tippt, gibt einem fremden Gerät im Heimnetz eine Sitzung des Kontos. Dagegen stehen:

1. die Grenze des Heimnetzes (der Angreifer muss im selben Netz sein),
2. die Frage vor dem Senden mit Server und Konto,
3. die Zahl, die mit der am Fernseher übereinstimmen muss.

Ganz ausschließen lässt es sich nicht. Die Sitzung lässt sich am Server beenden, das Passwort bleibt geheim.

Bekannte Grenzen:

| Grenze | Folge |
|---|---|
| Reine IPv6-Netze | Der Fernseher zeigt keinen Code. Einrichten geht nur durch Tippen. |
| SSO-Konten | Einrichten nur durch Tippen, und dort noch ohne SSO. |
| Keine Antwort vom Fernseher | Das Handy beendet die Sitzung. Hat sich der Fernseher doch angemeldet, kann er sie verlieren. Ungeprüft, ob das am Server so passiert: Am Fernseher nachsehen und notfalls neu einrichten. |
| Android 17 | verlangt für Verbindungen im Heimnetz vielleicht eine eigene Berechtigung. Ungeprüft. |

## Technik

| Teil | Ort |
|---|---|
| Code, Zahl, Prüfung der Adresse | `app/src/main/java/io/wolftown/lauschig/tvsetup/TvSetupCode.kt` |
| Klartext, Paket, Antwort | `tvsetup/Handover.kt` |
| HPKE (Tink) | `tvsetup/HandoverCrypto.kt` |
| Fernseher: Port, Fristen, Grenzen | `tvsetup/HandoverListener.kt` |
| Handy: Senden nur über WLAN oder Kabel | `tvsetup/HandoverSender.kt`, `tvsetup/HomeNetwork.kt` |
| Seite am Fernseher | `tv/setup/TvPhoneSetupScreen.kt`, `tv/setup/TvPhoneSetupViewModel.kt` |
| Seite am Handy | `ui/tvsetup/TvHandoverScreen.kt`, `ui/tvsetup/TvHandoverViewModel.kt` |
| Kamera | `serverqr/ServerQrScanner.kt` (CameraX und ZXing, wie beim Server-QR-Code) |
| Tests | `app/src/test/java/io/wolftown/lauschig/tvsetup/`, `ui/tvsetup/`, `tv/` |
