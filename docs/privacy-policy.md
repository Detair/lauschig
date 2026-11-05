# Privacy Policy / Datenschutzerklärung — Lauschig

Last updated / Stand: 2026-11-05 (Version 0.4.0)

## Deutsch

**Lauschig** ist ein Android-Client für Audiobookshelf-Server. Die App wird als Open-Source-Projekt (GPL-3.0-or-later) entwickelt.

**Mit wem spricht die App?** Mit dem Audiobookshelf-Server, dessen Adresse du bei der Einrichtung selbst einträgst. Dazu kommen zwei optionale Dienste, die unten beschrieben sind: der öffentliche Audible-Katalog und ein von dir eingetragener ReadMeABook-Server (RMAB). Es gibt keinen Server des Entwicklers, mit dem die App kommuniziert. Betreibt jemand anderes deinen Audiobookshelf-Server, ist diese Person für die dort gespeicherten Daten zuständig.

**Anmeldung per SSO.** Bietet dein Server eine Anmeldung über einen externen Anbieter an und du wählst sie, öffnet die App dafür den Browser. Die Anmeldung läuft dann zwischen dir, deinem Server und diesem Anbieter; die App erhält am Ende nur die Token deines Servers.

**Audible-Katalog (optional, standardmäßig aus).** Fehlende Bände einer Serie schlägt die App nur nach, wenn du zustimmst: Die Serienseite fragt einmal, und in den Einstellungen lässt sich das unter „Fehlende Bände von Audible ergänzen“ jederzeit ändern. Mit deiner Zustimmung fragt die App auf Serienseiten online den öffentlichen Audible-Katalog (api.audible.*, Region in den Einstellungen wählbar) nach den Bänden der Serie. Dabei gehen die ASINs deiner Bücher und, wie bei jeder Verbindung, deine IP-Adresse an Audible, aber keine Kontodaten, keine Server-Adresse und kein Hörfortschritt. Die Antworten werden bis zu sieben Tage lokal zwischengespeichert. Cover fehlender Bände lädt die App von Audibles Bilder-Servern. Ohne Zustimmung unterbleibt jede Anfrage.

**ReadMeABook (RMAB, optional).** Nur wenn du einen RMAB-Server einträgst, verbindet sich die App mit ihm. Dein Passwort wird nur bei der Anmeldung gesendet und nicht gespeichert; gespeichert wird ein verschlüsseltes API-Token. Wenn du einen fehlenden Band anfragst, gehen dessen Angaben (ASIN, Titel, Autor, Sprecher, Cover-Adresse) an diesen Server. Außerdem liest die App dort deine eigenen Anfragen.

**Welche Daten werden verarbeitet?** Zugangsdaten (Zugriffs- und Aktualisierungs-Token), Bibliothek, Hörfortschritt, Hörsitzungen, Lesezeichen, heruntergeladene Hörbücher und Einstellungen. Diese Daten liegen auf deinem Gerät. Die Token werden verschlüsselt gespeichert. Hörfortschritt, Sitzungen und Lesezeichen werden nur an deinen eigenen Server übertragen. Statistik und Erfolge berechnet die App auf dem Gerät. Den WLAN-Namen (SSID) liest die App nur, wenn du das Heimnetz-Verhalten einrichtest, und verarbeitet ihn nur lokal auf dem Gerät. Die App-Daten sind von der Android-Sicherung ausgenommen.

**Speicherort und eigene Ordner.** Downloads liegen im internen Speicher oder, wenn du das wählst, im App-Bereich einer SD-Karte. Ordner mit eigenen Hörbüchern gibst du über die Dateiauswahl von Android frei; die App liest nur diese Ordner, und ihre Titel und Dateien werden nicht an einen Server übertragen.

**Diagnose-Log.** Die App führt ein lokales Protokoll ihrer letzten Ereignisse (bis zu 500), auch nach Abstürzen. Passwörter, Token, Cookies, Serveradressen, IP- und E-Mail-Adressen und Benutzernamen werden schon beim Schreiben unkenntlich gemacht. Das Log verlässt das Gerät nur, wenn du es unter „Diagnose-Log teilen“ selbst versendest. Dazu zeigt die App es vorher an und nennt, was es enthält, darunter App- und Android-Version und das Gerätemodell; den Empfänger wählst du selbst. Beim Abmelden wird es gelöscht.

**Android Auto und Sprachsteuerung.** Im Auto zeigt die App Titel und Cover über Android Auto auf dem Bildschirm des Fahrzeugs an. Bei einer Sprachanfrage übergibt der Assistent von Google der App den Suchtext; für die Sprachverarbeitung selbst gelten die Bedingungen von Google.

**Was passiert nicht?** Keine Analyse- oder Tracking-Dienste, keine Werbung, keine automatische Übertragung von Absturzberichten durch die App (Google Play kann je nach den Android-Einstellungen des Geräts selbst Absturzdaten erheben), kein Verkauf von Daten, kein Konto beim Entwickler.

**Berechtigungen.**
- Standort (ungefähr/genau) und „Geräte in der Nähe“: Android verlangt diese Berechtigung, damit die App den WLAN-Namen (SSID) lesen kann, nur für die optionale Heimnetz-Bindung. Die SSID wird ausschließlich auf dem Gerät verarbeitet und weder auf einem Server gespeichert noch übertragen. Der Standort selbst wird nicht verwendet.
- Netzwerkstatus: erkennen, ob eine Verbindung besteht.
- Benachrichtigungen: Wiedergabe- und Download-Benachrichtigungen.
- Vordergrunddienste: Wiedergabe und Downloads im Hintergrund.
- Vibration: kurze Rückmeldung, wenn du den Sleep-Timer durch Schütteln verlängerst.

**Bewegungssensor.** In der letzten Minute eines Sleep-Timers wertet die App den Beschleunigungssensor aus, um ein Schütteln zu erkennen. Die Werte werden nicht gespeichert und nicht übertragen.

**Verschlüsselung.** Verbindungen zu deinem Server sind verschlüsselt, wenn du eine https-Adresse verwendest. Unverschlüsselte http-Adressen verwendet die App nur, nachdem du sie ausdrücklich bestätigt hast.

**Löschen.** Beim Abmelden wählst du, ob die Downloads und Daten des Kontos gelöscht werden oder auf dem Gerät bleiben. Das Deinstallieren der App oder das Löschen der App-Daten in den Android-Einstellungen entfernt alle lokal gespeicherten Daten. Downloads auf einer SD-Karte, die dabei nicht eingesteckt ist, bleiben auf der Karte, bis du sie dort löschst. Daten auf deinem Server verwaltest du dort selbst.

**Kontakt.** Fragen und Anfragen: GitHub-Issues unter <https://github.com/Detair/lauschig/issues>.

## English

**Lauschig** is an Android client for Audiobookshelf servers, developed as an open-source project (GPL-3.0-or-later).

**Who does the app talk to?** The Audiobookshelf server whose address you enter during setup. In addition there are two optional services, described below: the public Audible catalog and a ReadMeABook (RMAB) server you configure. There is no developer-operated server that the app communicates with. If someone else runs your Audiobookshelf server, that person is responsible for the data stored there.

**Single sign-on.** If your server offers sign-in through an external provider and you choose it, the app opens the browser for it. The sign-in then takes place between you, your server and that provider; in the end the app receives only your server's tokens.

**Audible catalog (optional, off by default).** The app looks up missing volumes of a series only if you agree: the series page asks once, and you can change it at any time in settings under "Add missing volumes from Audible". With your consent, on series pages the app queries the public Audible catalog (api.audible.*, region selectable in settings) for the volumes of the series while online. The ASINs of your books and, as with any connection, your IP address go to Audible, but no account data, server address or listening progress. Answers are cached locally for up to seven days. Covers of missing volumes are loaded from Audible's image servers. Without your consent, no request is made.

**ReadMeABook (RMAB, optional).** The app connects to an RMAB server only if you add one. Your password is sent only when signing in and is not stored; an encrypted API token is stored instead. When you request a missing volume, its details (ASIN, title, author, narrator, cover address) go to that server. The app also reads your own requests there.

**What data is processed?** Credentials (access and refresh tokens), library data, listening progress, listening sessions, bookmarks, downloaded audiobooks and settings. This data stays on your device. Tokens are stored encrypted. Listening progress, sessions and bookmarks are sent only to your own server. Statistics and achievements are computed on the device. The app reads the Wi-Fi name (SSID) only if you set up home-network behavior, and processes it only locally on the device. App data is excluded from Android backup.

**Storage and your own folders.** Downloads are kept in internal storage or, if you choose so, in the app's area on an SD card. You share folders with your own audiobooks through Android's file picker; the app reads only those folders, and their titles and files are not sent to any server.

**Diagnostic log.** The app keeps a local record of its latest events (up to 500), also after crashes. Passwords, tokens, cookies, server addresses, IP and e-mail addresses and user names are masked when written. The log leaves the device only if you send it yourself under "Share diagnostic log". The app shows it to you first and says what it contains, including the app and Android version and the device model; you choose the recipient. It is deleted when you sign out.

**Android Auto and voice control.** In the car the app shows titles and covers on the vehicle's screen through Android Auto. For a voice request, Google's assistant passes the search text to the app; Google's terms apply to the voice processing itself.

**What does not happen?** No analytics or tracking, no ads, no automatic sending of crash reports by the app (Google Play may collect crash data depending on your Android settings), no selling of data, no account with the developer.

**Permissions.**
- Location (approximate/precise) and "nearby devices": Android requires this permission so the app can read the Wi-Fi name (SSID), only for the optional home-network binding. The SSID is processed on the device only and is never stored on a server or transmitted. The location itself is not used.
- Network state: detect whether a connection is available.
- Notifications: playback and download notifications.
- Foreground services: playback and downloads in the background.
- Vibration: a short signal when you extend the sleep timer by shaking.

**Motion sensor.** During the last minute of a sleep timer the app reads the accelerometer to detect a shake. The values are neither stored nor transmitted.

**Encryption.** Connections to your server are encrypted when you use an https address. The app uses unencrypted http addresses only after you explicitly confirm them.

**Deletion.** When you sign out, you choose whether the account's downloads and data are deleted or stay on the device. Uninstalling the app or clearing its app data in Android settings removes all locally stored data. Downloads on an SD card that is not inserted at that moment stay on the card until you delete them there. Data on your server is managed by you there.

**Contact.** Questions and requests: GitHub issues at <https://github.com/Detair/lauschig/issues>.
