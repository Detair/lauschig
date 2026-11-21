# Fernseher mit dem Handy einrichten

Stand: 2026-11-21

Der Fernseher zeigt einen QR-Code und eine vierstellige Zahl. Das Handy scannt den Code, du tippst dein Passwort am Handy und bestätigst am Fernseher. Dann ist der Fernseher angemeldet. Mit der Fernbedienung tippst du nur noch „Einrichten“. Diese Seite beschreibt den Ablauf, das Format und die Grenzen des Verfahrens.

## Auf einen Blick

| | |
|---|---|
| Fernseher | erste Seite der Einrichtung, „Mit dem Handy einrichten“: QR-Code und Zahl, zum Beispiel „48 21“. Nicht bei „PIN vergessen“: dort beginnt die Einrichtung mit dem Formular. |
| Handy | Einstellungen → „Fernseher einrichten“ (nur mit angemeldetem Konto) |
| Was zum Fernseher geht | die Adressen des Servers und eine **neue Anmeldung nur für den Fernseher** (ein Refresh-Token, siehe unten) |
| Was nie zum Fernseher geht | dein Passwort, die Anmeldung des Handys, WLAN-Namen |
| Wohin das Passwort geht | nur an deinen Server, und nur an die Adresse, über die die App gerade verbunden ist |
| Weg zum Fernseher | verschlüsselt und direkt im Heimnetz vom Handy zum Fernseher; kein Server dazwischen, nie über das Internet |
| Rückfrage | Der Fernseher zeigt die Server-Adressen und fragt, bevor er etwas tut. Höchstens 60 Sekunden, der Fokus steht auf „Abbrechen“. |
| Rechte der Anmeldung | alle Rechte des Kontos, bei einem Admin also Admin-Rechte |
| Wer liest den Code | nur der Scanner in „Fernseher einrichten“; ein Link von außen öffnet nichts |
| Wie lange gilt ein Code | höchstens 5 Minuten, und nur für ein einziges angenommenes Paket |
| SSO-Konten | können den Fernseher noch nicht einrichten. Am Fernseher tippen geht weiter, aber auch dort ohne SSO. |

Begriffe:

- **Refresh-Token:** ein langes Zufallswort, mit dem sich ein Gerät beim Server neue Zugangsschlüssel holt. Es steht für genau eine Anmeldung (Sitzung) am Server. Wer es hat, ist mit allen Rechten des Kontos angemeldet.
- **Heimnetz:** im Code heißt das: eine private IPv4-Adresse (10.x, 172.16–31.x, 192.168.x), erreichbar aus dem WLAN oder Kabelnetz des Handys. Mobilfunk und VPN zählen nicht. Ob das Netz dein eigenes ist, prüft die App nicht: Auch ein Hotel- oder Gäste-WLAN zählt. Ob Fernseher und Handy im selben Teilnetz sind, prüft sie auch nicht, damit Netze mit mehreren Teilnetzen (VLANs) funktionieren.

## Ablauf für Menschen

1. Am Fernseher: Lauschig öffnen. Die Einrichtung beginnt mit „Mit dem Handy einrichten“. Der Fernseher zeigt einen QR-Code und eine Zahl.
2. Am Handy: Lauschig öffnen, Einstellungen → „Fernseher einrichten“.
3. „Code scannen“ antippen. Beim ersten Mal fragt das Handy nach der Kamera.
4. Die Kamera auf den Code am Fernseher halten.
5. Das Handy fragt: „Diesen Fernseher (Zahl 48 21) mit dem Server … und dem Konto … einrichten?“
6. **Die Zahl vergleichen.** Steht am Fernseher eine andere Zahl, hier abbrechen.
7. Das Passwort des Kontos eintippen und „Fernseher einrichten“ antippen. Das Handy sagt jetzt: „Bestätige die Einrichtung jetzt am Fernseher.“
8. Am Fernseher erscheint „Diesen Fernseher einrichten?“ mit den Adressen des Servers. Unverschlüsselte Adressen sind markiert. Prüfe sie und wähle „Einrichten“.
9. Der Fernseher meldet sich an und zeigt die Startseite. Das Handy sagt „Der Fernseher ist eingerichtet.“
10. Das Handy vergisst den Fernseher wieder. Es speichert nichts davon.

Wählst du am Fernseher „Abbrechen“, drückst Zurück oder tust 60 Sekunden lang nichts, geht nichts zum Server. Das Handy sagt dann: „Am Fernseher wurde abgebrochen oder nicht bestätigt.“

Klappt etwas nicht, sagen Handy und Fernseher warum. Am Fernseher wählst du „Neuer Code“, am Handy „Noch einmal scannen“. Dann geht es von vorn los.

## Was dabei im Hintergrund passiert

1. Das Handy liest den Code. Es merkt sich Adresse, Port und Schlüssel des Fernsehers nur im Speicher, dazu das Konto, das die Frage nennt.
2. Nach dem Tippen auf „Fernseher einrichten“ prüft das Handy:
   1. Ist noch dasselbe Konto aktiv? Sonst bricht es ab.
   2. Ist das Handy in einem Heimnetz (WLAN oder Kabel, kein VPN)? Sonst geht nichts weg, auch nicht das Passwort.
   3. Ist die App gerade mit dem Server dieses Kontos verbunden? Sonst geht nichts weg.
3. Das Handy meldet sich mit dem Benutzernamen des Kontos und dem getippten Passwort **selbst** bei seinem Server an. Es nimmt nur die Adresse, über die die App gerade verbunden ist. Diese Verbindung beachtet die WLAN-Bindung des Kontos und bestätigte `http://`-Adressen. Eine andere Adresse versucht es nicht.
4. Der Server legt dabei eine neue, eigene Sitzung an. Ihr Refresh-Token ist für den Fernseher bestimmt.
5. Das Handy verschlüsselt die Server-Adressen und dieses Token mit dem Schlüssel aus dem Code und schickt genau ein Paket an den Fernseher.
6. Der Fernseher entschlüsselt das Paket und fragt nach (siehe Ablauf, Schritt 8). Erst nach „Einrichten“ tauscht er das Token beim Server gegen eigene Zugangsschlüssel. Dann antwortet er dem Handy mit einem Byte.
7. Sagt die Antwort nicht „angemeldet“, versucht das Handy, die neue Sitzung am Server zu beenden (nächster Abschnitt).

### Wie das Handy die Sitzung beendet

Audiobookshelf meldet eine Sitzung nur mit ihrem **aktuellen** Refresh-Token ab. Wer das Token einmal tauscht, bekommt ein neues; das alte liefert danach noch 10 Minuten lang das aktuelle, ohne erneut zu tauschen. Geprüft am Quelltext von Audiobookshelf am 2026-11-20.

Darum macht das Handy, an derselben Adresse wie bei der Anmeldung:

1. Es tauscht das übergebene Token selbst noch einmal und bekommt so das aktuelle Token der Sitzung.
2. Es meldet die Sitzung mit diesem aktuellen Token ab.
3. Klappt der Tausch nicht, meldet es trotzdem mit dem übergebenen Token ab. Das wirkt, wenn noch niemand getauscht hat.

Nur wenn Tausch und Abmelden beide klappen, gilt die Sitzung als beendet. Sonst sagt das Handy zusätzlich: „Ob die Anmeldung für den Fernseher beendet ist, ließ sich nicht prüfen.“

Das Beenden gelingt nicht, wenn

- ein fremder Empfänger das Token schon zweimal getauscht hat, oder
- der Server gerade nicht erreichbar ist.

Das Handy macht das bei jeder Antwort außer „angemeldet“, auch wenn keine Antwort kommt. Hat sich der Fernseher doch angemeldet und nur die Antwort ging verloren, verliert er die Anmeldung dabei wieder. Dann scannst du noch einmal. Das ist so gewollt (E19).

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
- Leerzeichen und Zeilenumbrüche davor und dahinter entfernt Lauschig vor dem Lesen. Die Grenze von 256 Zeichen zählt sie aber mit.
- Alles ab einem `#` verwirft Lauschig.

### Die Zahl

Beide Geräte rechnen sie aus dem Code aus. Niemand tippt sie.

1. Text bilden: `"lauschig-tv-number-v1\n" + a + "\n" + p + "\n" + k`. Dabei steht `k` so da wie im Code, `p` als Dezimalzahl.
2. SHA-256 über diesen Text (UTF-8).
3. Die ersten vier Bytes als vorzeichenlose Zahl lesen (Big-Endian).
4. Modulo 10000, mit führenden Nullen auf vier Stellen.

Beide Geräte zeigen sie in zwei Gruppen, zum Beispiel „48 21“.

Was die Zahl leistet, und was nicht:

- Sie hilft gegen einen fremden Code, der gemacht wurde, **ohne** die Zahl am Fernseher zu sehen. Ein solcher Code hat fast immer eine andere Zahl.
- Sie hilft nur, wenn du wirklich vergleichst.
- Wer die Zahl am Fernseher gesehen hat, kann mit etwas Rechenarbeit einen eigenen Code mit derselben Zahl machen. Vier Stellen sind dafür zu wenig.

### Das Paket vom Handy zum Fernseher

| Schritt | Inhalt |
|---|---|
| Klartext (JSON) | `{"v":1,"local":"http://192.168.1.20:13378","public":"https://abs.example.org","refresh":"<Refresh-Token>"}` |
| Klartextblock | genau 2048 Bytes: 2 Bytes Länge `n` (Big-Endian), dann `n` Bytes JSON (UTF-8), dann Nullen |
| Verschlüsselung | HPKE (RFC 9180), Basis-Modus: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305. Aus der Bibliothek Tink, Variante ohne Präfix. |
| Kontext (`info`) | UTF-8 von `"lauschig-tv-setup-v1\n" + a + "\n" + p` |
| Paket | genau 2100 Bytes: die vier ASCII-Bytes `LTV1`, dann 32 Bytes gekapselter Schlüssel, 2048 Bytes Geheimtext, 16 Bytes Prüfsumme |

HPKE heißt „Hybrid Public Key Encryption“: Das Handy verschlüsselt mit dem öffentlichen Schlüssel aus dem Code. Nur der Fernseher hat den passenden privaten Schlüssel. Er liegt nur in seinem Speicher und nur, solange der Code gilt.

Wer das Paket geschickt hat, prüft der Fernseher nicht: Jeder, der den Code kennt, kann ein gültiges Paket bauen. Darum fragt der Fernseher nach, bevor er es benutzt (E17).

Regeln für den Klartext:

1. `local` und `public` dürfen fehlen. Mindestens eine Adresse muss da sein.
2. Eine Adresse steht so da wie im Einrichtungsformular: mit `http://` oder `https://`, mit Port und Pfad, ohne `/` am Ende. Es gelten dieselben Regeln wie beim [Server-QR-Code](server-qr-code.md): kein Benutzer in der Adresse, kein `?…`, kein `#…`.
3. `refresh` ist nicht leer.
4. Unbekannte Felder verwirft der Fernseher.
5. Weil der Block immer 2048 Bytes hat, verrät die Länge des Pakets nichts über Token oder Adressen.

### Die Antwort des Fernsehers

Ein Byte, danach schließt der Fernseher die Verbindung. Das Handy wartet höchstens 90 Sekunden darauf, weil der Fernseher erst nachfragt (bis zu 60 Sekunden) und sich dann anmeldet.

| Byte | Bedeutung | Was das Handy sagt |
|---|---|---|
| `1` | angemeldet | „Der Fernseher ist eingerichtet.“ |
| `2` | Paket nicht angenommen (alter Code, falscher Schlüssel, kaputtes Paket) | Der Code ist vielleicht abgelaufen. Neuen Code scannen. |
| `3` | Der Fernseher erreicht den Server nicht. | „Der Fernseher erreicht den Server nicht.“ |
| `4` | Der Server lehnt die Anmeldung ab. | „Der Server lehnt die Anmeldung des Fernsehers ab.“ |
| `5` | anderer Fehler | „Das hat nicht geklappt.“ |
| `6` | Am Fernseher abgebrochen, Zurück gedrückt oder 60 Sekunden nicht bestätigt. Zum Server ging nichts. | „Am Fernseher wurde abgebrochen oder nicht bestätigt.“ |
| keine | Verbindung bricht ab oder 90 s Stille | Am Fernseher nachsehen, ob er angemeldet ist. |

Bei allem außer `1` versucht das Handy, die neue Sitzung zu beenden (siehe oben). Klappt das nicht nachweislich, steht unter dem Grund: „Ob die Anmeldung für den Fernseher beendet ist, ließ sich nicht prüfen.“

### Feste Werte

| Wert | Grenze |
|---|---|
| Code gilt | höchstens 5 Minuten |
| angenommene Pakete je Code | genau eins |
| abgelehnte Verbindungen je Code | höchstens 20, danach gilt der Code nicht mehr |
| Lesefrist je Verbindung am Fernseher | 5 s insgesamt |
| Rückfrage am Fernseher | höchstens 60 s |
| Verbindungsaufbau vom Handy | 5 s |
| Warten auf die Antwort | 90 s |
| Anmeldung des Handys am Server | 5 s |

Die Grenze von 20 abgelehnten Verbindungen schützt nichts. Ohne den Schlüssel aus dem Code lässt sich ohnehin nichts erraten. Sie begrenzt nur die Arbeit des Fernsehers (E22).

## Was Lauschig ablehnt

Beim Scannen am Handy:

| Inhalt | Meldung in der App |
|---|---|
| der Server-QR-Code (`lauschig://server?…`) | „Das ist der Code eines Servers, nicht der eines Fernsehers.“ |
| `v` fehlt oder ist nicht `1` | „Dieser Code stammt von einer anderen Version von Lauschig. …“ |
| eine gültige IPv4-Adresse außerhalb der drei privaten Netze | „Die Adresse des Fernsehers liegt nicht in einem Heimnetz. Lauschig sendet nur im Heimnetz.“ |
| ein anderer QR-Code, ein doppelter Teil, ein Port oder Schlüssel, der nicht passt, ein Name statt einer Adresse, mehr als 256 Zeichen | „Das ist kein Code eines Fernsehers.“ |

Beim Senden:

| Lage | Meldung in der App | Sitzung am Server |
|---|---|---|
| ein anderes Konto ist inzwischen aktiv | „Das hat nicht geklappt.“ | keine entsteht |
| das Handy ist nicht per WLAN oder Kabel im Heimnetz (nur Mobilfunk oder VPN) | „Das Handy ist nicht per WLAN oder Kabel im Heimnetz. …“ Das Passwort geht nicht weg. | keine entsteht |
| die App ist gerade nicht mit dem Server verbunden | „Die App ist gerade nicht mit dem Server verbunden. …“ Das Passwort geht nicht weg. | keine entsteht |
| falsches Passwort (der Server antwortet 401 oder 403) | „Das Passwort stimmt nicht.“ Das Feld leert sich, der Code gilt weiter. | keine entsteht |
| der Server meldet einen anderen Benutzer als den des Kontos | „Das hat nicht geklappt.“ Nichts geht zum Fernseher. | das Handy versucht, sie zu beenden |
| der Server liefert kein Refresh-Token | „Das hat nicht geklappt.“ Nichts geht zum Fernseher. | keine, die das Handy beenden könnte |
| der Fernseher ist nicht erreichbar, auch weil sein Code schon ersetzt ist und der Port zu | „Das Handy erreicht den Fernseher nicht. …“ mit dem Rat, einen neuen Code zu scannen | das Handy versucht, sie zu beenden |

Der Fernseher liest je Verbindung bis zu 2100 Bytes, mehr nicht. Liefert die Verbindung in der Lesefrist weniger, beginnt das Paket nicht mit `LTV1`, lässt es sich nicht entschlüsseln oder verstößt der Klartext gegen eine Regel oben, antwortet er mit `2` und schließt die Verbindung.

## Sicherheitsregeln und bekannte Grenzen

Entschieden beim Bau am 2026-11-20, ergänzt nach der Sicherheitsprüfung am 2026-11-21 (Spec `docs/superpowers/specs/2026-11-16-google-tv-design.md`, E13 bis E22):

| # | Regel | Grund |
|---|---|---|
| E13 | Das Passwort geht nur an den eigenen Server, nie zum Fernseher. Zum Fernseher geht eine neue, eigene Sitzung. Sie hat alle Rechte des Kontos, auch die eines Admins. | Ein falscher Code bekommt so höchstens eine Sitzung, nie das Passwort. Die Sitzung des Handys selbst wandert nie. |
| E14 | SSO-Konten können den Fernseher noch nicht einrichten. | Der Weg über zwei Geräte ist noch nicht geprüft. |
| E15 | Vor dem Senden fragt das Handy keine Profil-PIN. | Es fragt das Passwort des Kontos. Das ist die stärkere Frage. |
| E16 | Ein Code gilt für ein angenommenes Paket und höchstens 5 Minuten. | Einmal-Schlüssel. Bei einem Fehler scannt man neu. |
| E17 | Der Fernseher fragt nach, bevor er etwas tut: Er zeigt die Server-Adressen aus dem Paket (unverschlüsselte markiert) und wartet höchstens 60 Sekunden. Der Fokus steht auf „Abbrechen“. Erst nach „Einrichten“ spricht er mit dem Server. | Der Absender eines Pakets ist nicht geprüft. Ohne Rückfrage könnte jeder im Netz, der den Code sieht, den Fernseher an einem fremden Server anmelden. Mit der Rückfrage braucht es dafür die Fernbedienung. Wer blind bestätigt, ist nicht geschützt. |
| E18 | Nur private IPv4-Adressen, nur über WLAN oder Kabel, nie über Mobilfunk oder VPN, kein Auflösen von Namen. | „Nur im Heimnetz“ ist eine Regel im Code, keine Absicht. |
| E19 | Die Antwort des Fernsehers ist nur ein Hinweis. Bei allem außer „angemeldet“ versucht das Handy, die Sitzung zu beenden (zweiter Tausch, dann Abmelden mit dem aktuellen Token). Gelingt das nicht nachweislich, sagt das Handy es. | Audiobookshelf meldet nur mit dem aktuellen Token ab. |
| E20 | Das Passwort geht nur an die Adresse, über die die App gerade verbunden ist, und nur, wenn das Handy in einem WLAN oder Kabelnetz ist. Ist die App offline, gibt es keine Übergabe. | Die lokale Adresse ist oft unverschlüsselt. In einem fremden Netz könnte ein anderes Gerät unter dieser Adresse antworten. |
| E21 | „Heimnetz“ heißt: eine private IPv4-Adresse, erreichbar aus dem WLAN oder Kabelnetz des Handys. Ob es das eigene Netz ist und ob der Fernseher im selben Teilnetz liegt, wird nicht geprüft. | Netze mit mehreren Teilnetzen sollen funktionieren. Auch ein Hotel- oder Gäste-WLAN zählt; dort ist der Schutz gegen einen falschen Code schwächer. |
| E22 | Die Grenze von 20 abgelehnten Verbindungen bleibt. Sie schützt nicht, sie begrenzt nur die Arbeit des Fernsehers. | Ein Gerät im Netz kann die Einrichtung mit dem Handy so oder so stören; dann bleibt das Tippen. |

Weitere Regeln:

1. Nur der Scanner in „Fernseher einrichten“ liest einen Code. Die App hat keinen Link-Filter für `lauschig://tv`.
2. Der Code, das Passwort und das Token stehen in keinem Log, keiner Fehlermeldung und keiner Datei. Nach der Anmeldung am Server hält das Handy das Passwort nirgends mehr.
3. Das Handy benutzt das Zugriffstoken der neuen Sitzung nie. Es reicht nur das Refresh-Token weiter und tauscht es höchstens, um die Sitzung zu beenden.
4. Wird die Seite am Handy nach dem Tippen auf „Fernseher einrichten“ verlassen, läuft die Übergabe zu Ende. Eine fehlgeschlagene Übergabe versucht die Sitzung trotzdem zu beenden.
5. Kein selbst gebautes Krypto-Verfahren: nur die öffentliche HPKE-Schnittstelle von Tink.

**Größtes Risiko:** ein fremder QR-Code, der sich als Fernseher ausgibt. Wer ihn scannt und das Passwort tippt, gibt einem fremden Gerät im Netz eine Sitzung des Kontos, mit allen seinen Rechten. Dagegen stehen:

1. die Grenze des Heimnetzes (der Angreifer muss im selben Netz erreichbar sein; ein Gäste-WLAN zählt auch),
2. die Frage vor dem Senden mit Server und Konto,
3. die Zahl, aber nur gegen einen Code, der ohne Blick auf den Fernseher gemacht wurde, und nur, wenn du vergleichst,
4. der Versuch des Handys, die Sitzung zu beenden, wenn der Empfänger nicht „angemeldet“ meldet. Ein Angreifer kann aber „angemeldet“ melden.

Ganz ausschließen lässt es sich nicht. Das Passwort geht aber in keinem Fall zum Fernseher.

Bekannte Grenzen:

| Grenze | Folge |
|---|---|
| SSO-Konten | Einrichten nur durch Tippen, und dort noch ohne SSO. |
| Reine IPv6-Netze | Der Fernseher zeigt keinen Code. Einrichten geht nur durch Tippen. |
| Ein Gerät im Netz stört | Es kann die Verbindungen zum Fernseher blockieren oder den Code verbrauchen. Dann bleibt das Tippen. |
| Keine Antwort vom Fernseher | Das Handy beendet die Sitzung, wie oben beschrieben. Hatte sich der Fernseher doch angemeldet, verliert er die Anmeldung wieder. Dann noch einmal scannen. Geprüft am Quelltext von Audiobookshelf am 2026-11-20. |
| Beenden nicht nachweisbar | Hat ein fremder Empfänger das Token schon zweimal getauscht oder ist der Server nicht erreichbar, bleibt die Sitzung vielleicht offen. Das Handy sagt es. |
| Cast | Ungeprüft: ob ein Gerät im Netz per Cast eine nachgemachte Code-Seite über die echte legen kann. Das braucht ein echtes Gerät. |
| Android 17 | verlangt für Verbindungen im Heimnetz vielleicht eine eigene Berechtigung. Ungeprüft. |

## Technik

| Teil | Ort |
|---|---|
| Code, Zahl, Prüfung der Adresse | `app/src/main/java/io/wolftown/lauschig/tvsetup/TvSetupCode.kt` |
| Klartext, Paket, Antwort | `tvsetup/Handover.kt` |
| HPKE (Tink) | `tvsetup/HandoverCrypto.kt` |
| Fernseher: Port, Fristen, Grenzen | `tvsetup/HandoverListener.kt` |
| Handy: Senden nur über WLAN oder Kabel | `tvsetup/HandoverSender.kt`, `tvsetup/HomeNetwork.kt` |
| Seite am Fernseher, mit Rückfrage | `tv/setup/TvPhoneSetupScreen.kt`, `tv/setup/TvPhoneSetupViewModel.kt` |
| Seite am Handy, Anmeldung und Beenden der Sitzung | `ui/tvsetup/TvHandoverScreen.kt`, `ui/tvsetup/TvHandoverViewModel.kt` |
| Abmelden an einer Adresse | `account/ServerLogout.kt` (`logoutAt`) |
| Kamera | `serverqr/ServerQrScanner.kt` (CameraX und ZXing, wie beim Server-QR-Code) |
| Tests | `app/src/test/java/io/wolftown/lauschig/tvsetup/`, `ui/tvsetup/`, `tv/setup/`, `account/ServerLogoutTest.kt` |
