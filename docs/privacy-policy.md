# Privacy Policy / Datenschutzerklärung — Lauschig

Last updated / Stand: 2026-11-06 (Version 0.5.0)

## Deutsch

**Lauschig** ist ein Android-Client für Audiobookshelf-Server. Die App wird als Open-Source-Projekt (GPL-3.0-or-later) entwickelt.

**Mit wem spricht die App?** Mit dem Audiobookshelf-Server, dessen Adresse du bei der Einrichtung selbst einträgst. Dazu kommen zwei optionale Dienste, die unten beschrieben sind: der öffentliche Audible-Katalog und ein von dir eingetragener ReadMeABook-Server (RMAB). Es gibt keinen Server des Entwicklers, mit dem die App kommuniziert. Betreibt jemand anderes deinen Audiobookshelf-Server, ist diese Person für die dort gespeicherten Daten zuständig.

**Anmeldung per SSO.** Bietet dein Server eine Anmeldung über einen externen Anbieter an und du wählst sie, öffnet die App dafür den Browser. Die Anmeldung läuft dann zwischen dir, deinem Server und diesem Anbieter; die App erhält am Ende nur die Token deines Servers. Wenn du ein Profil hinzufügst, eine vergessene PIN aufhebst oder weitere Profile auf dem Gerät sind, bittet die App den Anbieter um eine neue Anmeldung: Er soll nach deinen Zugangsdaten fragen, auch wenn der Browser dort noch angemeldet ist. Ob er das tut, entscheidet der Anbieter.

**Mehrere Profile.** Auf einem Gerät können mehrere Konten angemeldet sein. Jedes hat seine eigenen Daten: Fortschritt, Downloads, Lesezeichen, die RMAB-Verbindung und letzte Suchen bleiben bei ihm, ein Wechsel löscht nichts. Die App spricht für jedes Profil nur mit dessen Server.

**Schutz eines Profils (optional, standardmäßig aus).** Ein Profil lässt sich mit einer eigenen PIN oder der Gerätesperre schützen. Die PIN liegt nur auf dem Gerät, als gesalzener Hash und verschlüsselt, und verlässt es nicht. Die Gerätesperre fragt Android mit seiner eigenen Abfrage ab: Die App erfährt nur, ob sie bestanden wurde, und sieht weder einen Fingerabdruck noch den Code der Sperre. Der Schutz soll Kinder vom Wechsel abhalten; gegen jemanden mit vollem Zugriff auf das Gerät schützt er nicht.

**Audible-Katalog (optional, standardmäßig aus).** Fehlende Bände einer Serie schlägt die App nur nach, wenn du zustimmst, und zwar jedes Profil für sich: Die Serienseite fragt jedes Profil einmal, und in den Einstellungen lässt sich das unter „Fehlende Bände von Audible ergänzen“ jederzeit ändern. Beim Abmelden und beim Entfernen des Profils endet die Zustimmung. Mit deiner Zustimmung fragt die App auf Serienseiten online den öffentlichen Audible-Katalog (api.audible.*, Region in den Einstellungen wählbar) nach den Bänden der Serie; kennt dieser einen Titel nicht, fragt sie auch den Katalog einer zweiten Region. Dabei gehen die ASINs deiner Bücher und, wie bei jeder Verbindung, deine IP-Adresse an Audible, aber keine Kontodaten, keine Server-Adresse und kein Hörfortschritt. Die Antworten speichert die App lokal und fragt nach spätestens sieben Tagen neu; sie verlassen das Gerät nicht. Cover fehlender Bände lädt sie über https von den Bilder-Servern, die Audible in seinen Antworten nennt (Amazon). Ohne Zustimmung unterbleibt jede Anfrage.

**ReadMeABook (RMAB, optional).** Nur wenn du einen RMAB-Server einträgst, verbindet sich die App mit ihm. Dein Passwort wird nur bei der Anmeldung gesendet und nicht gespeichert; gespeichert wird ein verschlüsseltes API-Token. Wenn du einen fehlenden Band anfragst, gehen dessen Angaben (ASIN, Titel, Autor, Sprecher, Cover-Adresse) an diesen Server. Außerdem liest die App dort deine eigenen Anfragen.

**Welche Daten werden verarbeitet?** Zugangsdaten (Zugriffs- und Aktualisierungs-Token), Bibliothek, Hörfortschritt, Hörsitzungen, Lesezeichen, heruntergeladene Hörbücher und Einstellungen. Diese Daten liegen auf deinem Gerät. Die Token werden verschlüsselt gespeichert. Hörfortschritt, Sitzungen und Lesezeichen werden nur an deinen eigenen Server übertragen. Statistik und Erfolge berechnet die App auf dem Gerät. Den WLAN-Namen (SSID) liest die App nur, wenn du das Heimnetz-Verhalten einrichtest, und verarbeitet ihn nur lokal auf dem Gerät. Die App-Daten sind von der Android-Sicherung ausgenommen.

**Speicherort und eigene Ordner.** Downloads liegen im internen Speicher oder, wenn du das wählst, im App-Bereich einer SD-Karte. Ordner mit eigenen Hörbüchern gibst du über die Dateiauswahl von Android frei; die App liest nur diese Ordner, und ihre Titel und Dateien werden nicht an einen Server übertragen.

**Diagnose-Log.** Die App führt ein lokales Protokoll ihrer letzten Ereignisse (bis zu 500), auch nach Abstürzen. Passwörter, Token, Cookies, Serveradressen, IP- und E-Mail-Adressen und Benutzernamen werden schon beim Schreiben unkenntlich gemacht. Das Log verlässt das Gerät nur, wenn du es unter „Diagnose-Log teilen“ selbst versendest. Dazu zeigt die App es vorher an und nennt, was es enthält, darunter App- und Android-Version und das Gerätemodell; den Empfänger wählst du selbst. Beim Abmelden und beim Wechsel des Profils wird es gelöscht.

**Android Auto und Sprachsteuerung.** Im Auto zeigt die App Titel und Cover über Android Auto auf dem Bildschirm des Fahrzeugs an. Bei einer Sprachanfrage übergibt der Assistent von Google der App den Suchtext; für die Sprachverarbeitung selbst gelten die Bedingungen von Google.

**Was passiert nicht?** Keine Analyse- oder Tracking-Dienste, keine Werbung, keine automatische Übertragung von Absturzberichten durch die App (Google Play kann je nach den Android-Einstellungen des Geräts selbst Absturzdaten erheben), kein Verkauf von Daten, kein Konto beim Entwickler.

**Berechtigungen.**
- Standort (ungefähr/genau) und „Geräte in der Nähe“: Android verlangt diese Berechtigung, damit die App den WLAN-Namen (SSID) lesen kann, nur für die optionale Heimnetz-Bindung. Die SSID wird ausschließlich auf dem Gerät verarbeitet und weder auf einem Server gespeichert noch übertragen. Der Standort selbst wird nicht verwendet.
- Netzwerkstatus: erkennen, ob eine Verbindung besteht.
- Benachrichtigungen: Wiedergabe- und Download-Benachrichtigungen.
- Vordergrunddienste: Wiedergabe und Downloads im Hintergrund.
- Vibration: kurze Rückmeldung, wenn du den Sleep-Timer durch Schütteln verlängerst.
- Biometrie und Gerätesperre: die Abfrage des Systems, nur wenn du ein Profil mit der Gerätesperre schützt.

**Bewegungssensor.** In der letzten Minute eines Sleep-Timers wertet die App den Beschleunigungssensor aus, um ein Schütteln zu erkennen. Die Werte werden nicht gespeichert und nicht übertragen.

**Verschlüsselung.** Verbindungen zu deinem Server sind verschlüsselt, wenn du eine https-Adresse verwendest. Unverschlüsselte http-Adressen verwendet die App nur, nachdem du sie ausdrücklich bestätigt hast.

**Löschen.** Beim Abmelden wählst du, ob die Downloads und Daten des Kontos gelöscht werden oder auf dem Gerät bleiben. Das Deinstallieren der App oder das Löschen der App-Daten in den Android-Einstellungen entfernt alle lokal gespeicherten Daten. Das Entfernen eines Profils löscht seine Downloads und Daten auf dem Gerät und seinen Schutz. Downloads auf einer SD-Karte, die dabei nicht eingesteckt ist, bleiben auf der Karte, bis du sie dort löschst. Daten auf deinem Server verwaltest du dort selbst.

**Kontakt.** Fragen und Anfragen: GitHub-Issues unter <https://github.com/Detair/lauschig/issues>.

## English

**Lauschig** is an Android client for Audiobookshelf servers, developed as an open-source project (GPL-3.0-or-later).

**Who does the app talk to?** The Audiobookshelf server whose address you enter during setup. In addition there are two optional services, described below: the public Audible catalog and a ReadMeABook (RMAB) server you configure. There is no developer-operated server that the app communicates with. If someone else runs your Audiobookshelf server, that person is responsible for the data stored there.

**Single sign-on.** If your server offers sign-in through an external provider and you choose it, the app opens the browser for it. The sign-in then takes place between you, your server and that provider; in the end the app receives only your server's tokens. When you add a profile, lift a forgotten PIN or have other profiles on the device, the app asks the provider for a fresh login: it should ask for your credentials even if the browser is still signed in there. Whether it does is up to the provider.

**Several profiles.** Several accounts can be signed in on one device. Each has its own data: progress, downloads, bookmarks, the RMAB connection and recent searches stay with it, and a switch deletes nothing. For each profile the app talks only to that profile's server.

**Protecting a profile (optional, off by default).** A profile can be protected by a PIN of its own or by the device lock. The PIN stays on the device only, as a salted hash and encrypted, and does not leave it. The device lock is asked for by Android with its own prompt: the app learns only whether it was passed, and sees neither a fingerprint nor the lock's code. The protection is meant to keep children from switching; it does not protect against someone with full access to the device.

**Audible catalog (optional, off by default).** The app looks up missing volumes of a series only if you agree, each profile for itself: the series page asks each profile once, and you can change it at any time in settings under "Add missing volumes from Audible". Signing out or removing the profile ends the consent. With your consent, on series pages the app queries the public Audible catalog (api.audible.*, region selectable in settings) for the volumes of the series while online; if it does not know a title, the app also asks the catalog of a second region. The ASINs of your books and, as with any connection, your IP address go to Audible, but no account data, server address or listening progress. The app keeps the answers locally and asks again after seven days at the latest; they do not leave the device. Covers of missing volumes are loaded over https from the image servers Audible names in its answers (Amazon). Without your consent, no request is made.

**ReadMeABook (RMAB, optional).** The app connects to an RMAB server only if you add one. Your password is sent only when signing in and is not stored; an encrypted API token is stored instead. When you request a missing volume, its details (ASIN, title, author, narrator, cover address) go to that server. The app also reads your own requests there.

**What data is processed?** Credentials (access and refresh tokens), library data, listening progress, listening sessions, bookmarks, downloaded audiobooks and settings. This data stays on your device. Tokens are stored encrypted. Listening progress, sessions and bookmarks are sent only to your own server. Statistics and achievements are computed on the device. The app reads the Wi-Fi name (SSID) only if you set up home-network behavior, and processes it only locally on the device. App data is excluded from Android backup.

**Storage and your own folders.** Downloads are kept in internal storage or, if you choose so, in the app's area on an SD card. You share folders with your own audiobooks through Android's file picker; the app reads only those folders, and their titles and files are not sent to any server.

**Diagnostic log.** The app keeps a local record of its latest events (up to 500), also after crashes. Passwords, tokens, cookies, server addresses, IP and e-mail addresses and user names are masked when written. The log leaves the device only if you send it yourself under "Share diagnostic log". The app shows it to you first and says what it contains, including the app and Android version and the device model; you choose the recipient. It is deleted when you sign out and when you switch profiles.

**Android Auto and voice control.** In the car the app shows titles and covers on the vehicle's screen through Android Auto. For a voice request, Google's assistant passes the search text to the app; Google's terms apply to the voice processing itself.

**What does not happen?** No analytics or tracking, no ads, no automatic sending of crash reports by the app (Google Play may collect crash data depending on your Android settings), no selling of data, no account with the developer.

**Permissions.**
- Location (approximate/precise) and "nearby devices": Android requires this permission so the app can read the Wi-Fi name (SSID), only for the optional home-network binding. The SSID is processed on the device only and is never stored on a server or transmitted. The location itself is not used.
- Network state: detect whether a connection is available.
- Notifications: playback and download notifications.
- Foreground services: playback and downloads in the background.
- Vibration: a short signal when you extend the sleep timer by shaking.
- Biometrics and device lock: the system's prompt, only if you protect a profile with the device lock.

**Motion sensor.** During the last minute of a sleep timer the app reads the accelerometer to detect a shake. The values are neither stored nor transmitted.

**Encryption.** Connections to your server are encrypted when you use an https address. The app uses unencrypted http addresses only after you explicitly confirm them.

**Deletion.** When you sign out, you choose whether the account's downloads and data are deleted or stay on the device. Uninstalling the app or clearing its app data in Android settings removes all locally stored data. Removing a profile deletes its downloads and data on the device, and its protection. Downloads on an SD card that is not inserted at that moment stay on the card until you delete them there. Data on your server is managed by you there.

**Contact.** Questions and requests: GitHub issues at <https://github.com/Detair/lauschig/issues>.
