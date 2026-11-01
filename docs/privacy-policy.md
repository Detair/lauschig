# Privacy Policy / Datenschutzerklärung — Lauschig

Last updated / Stand: 2026-11-01

## Deutsch

**Lauschig** ist ein Android-Client für Audiobookshelf-Server. Die App wird als Open-Source-Projekt (GPL-3.0-or-later) entwickelt.

**Mit wem spricht die App?** Ausschließlich mit dem Audiobookshelf-Server, dessen Adresse du bei der Einrichtung selbst einträgst. Optional (in späteren Versionen) kann die App zusätzlich einen von dir eingetragenen RMAB-Server sowie öffentliche Metadaten-Dienste (Audible/Audnexus) für Buchinformationen abfragen. Es gibt keinen Server des Entwicklers, mit dem die App kommuniziert.

**Audible-Katalog (Schalter „Fehlende Bände von Audible ergänzen“, standardmäßig an).** Auf Serienseiten fragt die App online den öffentlichen Audible-Katalog (api.audible.*, Region in den Einstellungen wählbar) nach den Bänden der Serie. Dabei werden ausschließlich die ASINs deiner Bücher übertragen, keine Kontodaten, Server-Adresse oder Hörfortschritte. Die Antworten werden bis zu sieben Tage lokal zwischengespeichert. Cover fehlender Bände lädt die App von Audibles Bilder-Servern. Mit dem Schalter in den Einstellungen unterbleibt jede Anfrage.

**Welche Daten werden verarbeitet?** Zugangsdaten (Zugriffs- und Aktualisierungs-Token), Bibliothek, Hörfortschritt, heruntergeladene Hörbücher und Einstellungen. Diese Daten liegen ausschließlich auf deinem Gerät. Die Token werden verschlüsselt gespeichert. Hörfortschritt und Sitzungen werden nur an deinen eigenen Server übertragen. Den WLAN-Namen (SSID) liest die App nur, wenn du das Heimnetz-Verhalten einrichtest, und nur lokal auf dem Gerät.

**Was passiert nicht?** Keine Analyse- oder Tracking-Dienste, keine Werbung, keine eigene Absturzberichterstattung (Google Play kann je nach den Android-Einstellungen des Geräts selbst Absturzdaten erheben), keine Weitergabe von Daten an Dritte, kein Konto beim Entwickler.

**Berechtigungen.**
- Standort (ungefähr/genau) und „Geräte in der Nähe“: Android verlangt diese Berechtigung, damit die App den WLAN-Namen (SSID) lesen kann, nur für die optionale Heimnetz-Bindung. Die SSID wird ausschließlich auf dem Gerät verarbeitet und weder auf einem Server gespeichert noch übertragen. Der Standort selbst wird nicht verwendet.
- Netzwerkstatus: erkennen, ob eine Verbindung besteht.
- Benachrichtigungen: Wiedergabe- und Download-Benachrichtigungen.
- Vordergrunddienste: Wiedergabe und Downloads im Hintergrund.

**Verschlüsselung.** Verbindungen zu deinem Server sind verschlüsselt, wenn du eine https-Adresse verwendest. Unverschlüsselte http-Adressen verwendet die App nur, nachdem du sie ausdrücklich bestätigt hast.

**Löschen.** Das Deinstallieren der App oder das Löschen der App-Daten in den Android-Einstellungen entfernt alle lokal gespeicherten Daten. Daten auf deinem Server verwaltest du dort selbst.

**Kontakt.** Fragen und Anfragen: GitHub-Issues unter <https://github.com/Detair/lauschig/issues>.

## English

**Lauschig** is an Android client for Audiobookshelf servers, developed as an open-source project (GPL-3.0-or-later).

**Who does the app talk to?** Only to the Audiobookshelf server whose address you enter during setup. Optionally (in later versions) it may also query an RMAB server you configure and public metadata services (Audible/Audnexus) for book information. There is no developer-operated server that the app communicates with.

**Audible catalog (switch "Add missing volumes from Audible", on by default).** On series pages the app queries the public Audible catalog (api.audible.*, region selectable in settings) for the volumes of the series while online. Only the ASINs of your books are sent: no account data, server address or listening progress. Answers are cached locally for up to seven days. Covers of missing volumes are loaded from Audible's image servers. With the switch off in settings, no request is made.

**What data is processed?** Credentials (access and refresh tokens), library data, listening progress, downloaded audiobooks and settings. This data stays on your device. Tokens are stored encrypted. Listening progress and sessions are sent only to your own server. The app reads the Wi-Fi name (SSID) only if you set up home-network behavior, and only locally on the device.

**What does not happen?** No analytics or tracking, no ads, no crash reporting of its own (Google Play may collect crash data depending on your Android settings), no sharing of data with third parties, no account with the developer.

**Permissions.**
- Location (approximate/precise) and "nearby devices": Android requires this permission so the app can read the Wi-Fi name (SSID), only for the optional home-network binding. The SSID is processed on the device only and is never stored on a server or transmitted. The location itself is not used.
- Network state: detect whether a connection is available.
- Notifications: playback and download notifications.
- Foreground services: playback and downloads in the background.

**Encryption.** Connections to your server are encrypted when you use an https address. The app uses unencrypted http addresses only after you explicitly confirm them.

**Deletion.** Uninstalling the app or clearing its app data in Android settings removes all locally stored data. Data on your server is managed by you there.

**Contact.** Questions and requests: GitHub issues at <https://github.com/Detair/lauschig/issues>.
