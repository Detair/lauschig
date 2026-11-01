# Data safety form — answers

Basis: the app talks only to the user-entered Audiobookshelf server; the developer operates no backend and receives no data.

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No data collected or shared with the developer or third parties.** Data stays on the device or goes to the user's own server, which the user configures; this is not collection by the developer. |
| Data shared with third parties | None |
| Is all of the user data encrypted in transit? | **Yes** when the user enters an https address. Plain http only after an explicit confirmation by the user for that address; because this is user-controlled, answer "Yes" and note that http is opt-in by the user. |
| Do you provide a way for users to request that their data is deleted? | Yes: uninstalling the app or clearing its app data in Android settings removes all local data. The developer holds no user data; data on the user's server is controlled by the user. |
| Analytics / ads / advertising ID | None |
| Account creation | No account with the developer (login is to the user's own server) |

## Stored on device (informational)

- Access and refresh tokens (encrypted with Tink / Android Keystore-backed key where available)
- Library metadata, covers, listening progress, downloaded audio files, settings
- Home Wi-Fi names (SSIDs) for the optional local-address binding — stored on device only

## Permissions and their purpose

- INTERNET, ACCESS_NETWORK_STATE: talk to the user's server
- ACCESS_WIFI_STATE, ACCESS_FINE/COARSE_LOCATION, NEARBY_WIFI_DEVICES: read the Wi-Fi SSID only when home-network behavior is configured (the location permission is required by Android for this); the SSID is processed on-device only and is never stored on a server or transmitted; location itself is not used
- FOREGROUND_SERVICE (+ MEDIA_PLAYBACK, DATA_SYNC), POST_NOTIFICATIONS: playback and download notifications

## Other declarations

- Target audience: general / not directed at children
- Content: no ads; foreground service types declared as above (media playback, data sync for downloads)
- Privacy policy URL: https://github.com/Detair/lauschig/blob/main/docs/privacy-policy.md
