# Data safety form — answers

Basis: the app talks to the Audiobookshelf server the user enters, and to one more service only by the user's choice: the public Audible catalog after the user agreed. The developer operates no backend and receives no data. Google's help does not address self-hosted servers; treating the user's own server as neither collection nor sharing is our reading of it.

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes, collected, one optional case:** after the user agreed, per profile on the device, the ASINs of that profile's books go to the public Audible catalog (App activity → Other actions; optional; purpose: App functionality). Everything else stays on the device or goes to the user's own server. |
| Data shared with third parties | **Not declared as shared:** the Audible lookup happens only after prominent in-app disclosure and consent, per profile, Google's exemption for sharing. Without that profile's consent nothing is sent for it. |
| Is all of the user data encrypted in transit? | **Yes** for the declared Audible lookup (catalog and covers over https only). For the user's own server: https, or plain http only after an explicit confirmation by the user for that address. |
| Do you provide a way for users to request that their data is deleted? | **No** for what Audible received (the developer has no influence on it). Locally: uninstalling the app or clearing its app data in Android settings removes all local data; data on the server is managed there. |
| Photos and videos | **Not collected.** The camera is used only to scan a server QR code during setup, or a TV's code under "Set up TV"; frames are analysed in memory on the device and are never stored or transmitted. The server code holds server addresses only (see `docs/server-qr-code.md`). |
| Setting up a TV with the phone | **Not collected or shared.** Under "Set up TV" the phone signs in to the user's own server with the password the user types, and the server issues a new session for the TV. Only the server addresses and that session's refresh token go to the TV, encrypted (HPKE) and directly within the home network from the phone to the user's own TV, to no one else. The password goes only to the user's server. If the setup fails, the phone ends that session at the server (see `docs/tv-einrichtung-mit-dem-handy.md`). |
| Analytics / ads / advertising ID | None |
| Account creation | No account with the developer (login is to the user's own server) |

## Stored on device (informational)

- Access and refresh tokens, per profile (encrypted with Tink / Android Keystore-backed key where available)
- The app PIN of a protected profile, only if the user sets one: a salted hash, encrypted with the same key; it never leaves the device
- Library metadata, covers, listening progress, downloaded audio files, settings
- Home Wi-Fi names (SSIDs) for the optional local-address binding — stored on device only
- A masked diagnostic log; it leaves the device only if the user shares it (user-initiated action)

## Permissions and their purpose

- INTERNET, ACCESS_NETWORK_STATE: talk to the user's server
- ACCESS_WIFI_STATE, ACCESS_FINE/COARSE_LOCATION, NEARBY_WIFI_DEVICES: read the Wi-Fi SSID only when home-network behavior is configured (the location permission is required by Android for this); the SSID is processed on-device only and is never stored on a server or transmitted; location itself is not used
- FOREGROUND_SERVICE (+ MEDIA_PLAYBACK, DATA_SYNC), POST_NOTIFICATIONS: playback and download notifications
- CAMERA: scanning another device's server QR code in the setup form, or a TV's code under "Set up TV", requested when the user taps "Scan QR code" or "Scan code" and not before; no camera is required (`uses-feature … android:required="false"` for `android.hardware.camera`, `.any` and `.autofocus`), devices without one get the app without the scan button
- USE_BIOMETRIC: the system's lock prompt, only when a profile is protected by the device lock; the app learns only whether it was passed and sees no fingerprint or lock code

## Other declarations

- Target audience: general / not directed at children
- Content: no ads; foreground service types declared as above (media playback, data sync for downloads)
- Privacy policy URL: https://github.com/Detair/lauschig/blob/main/docs/privacy-policy.md
