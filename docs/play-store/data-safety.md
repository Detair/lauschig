# Data safety form — answers

Basis: the app talks to the Audiobookshelf server the user enters, and to two services only by the user's choice: a ReadMeABook server the user adds, and the public Audible catalog after the user agreed. The developer operates no backend and receives no data. Google's help does not address self-hosted servers; treating the user's own server as neither collection nor sharing is our reading of it.

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes, one optional case:** after the user agreed, the ASINs of their books go to the public Audible catalog (App activity → Other actions; optional; purpose: App functionality). Everything else stays on the device or goes to the user's own server. |
| Data shared with third parties | Only that Audible lookup, based on prominent in-app disclosure and consent (Google's exemption for sharing); nothing without it. |
| Is all of the user data encrypted in transit? | **Yes** for the declared Audible lookup (https only). For the user's own server: https, or plain http only after an explicit confirmation by the user for that address. |
| Do you provide a way for users to request that their data is deleted? | **No** for what Audible received (the developer has no influence on it). Locally: uninstalling the app or clearing its app data in Android settings removes all local data; data on the server is managed there. |
| Analytics / ads / advertising ID | None |
| Account creation | No account with the developer (login is to the user's own server) |

## Stored on device (informational)

- Access and refresh tokens (encrypted with Tink / Android Keystore-backed key where available)
- Library metadata, covers, listening progress, downloaded audio files, settings
- Home Wi-Fi names (SSIDs) for the optional local-address binding — stored on device only
- A masked diagnostic log; it leaves the device only if the user shares it (user-initiated action)

## Permissions and their purpose

- INTERNET, ACCESS_NETWORK_STATE: talk to the user's server
- ACCESS_WIFI_STATE, ACCESS_FINE/COARSE_LOCATION, NEARBY_WIFI_DEVICES: read the Wi-Fi SSID only when home-network behavior is configured (the location permission is required by Android for this); the SSID is processed on-device only and is never stored on a server or transmitted; location itself is not used
- FOREGROUND_SERVICE (+ MEDIA_PLAYBACK, DATA_SYNC), POST_NOTIFICATIONS: playback and download notifications

## Other declarations

- Target audience: general / not directed at children
- Content: no ads; foreground service types declared as above (media playback, data sync for downloads)
- Privacy policy URL: https://github.com/Detair/lauschig/blob/main/docs/privacy-policy.md
