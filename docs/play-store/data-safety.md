# Data safety form — answers

Basis: the app talks to the Audiobookshelf server the user enters, and to two services only by the user's choice: a ReadMeABook server the user adds, and the public Audible catalog after the user agreed. Since 0.5.0 it contains Google's Cast SDK. The developer operates no backend and receives no data. Google's help does not address self-hosted servers; treating the user's own server as neither collection nor sharing is our reading of it.

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes, collected, one optional case:** after the user agreed, per profile on the device, the ASINs of that profile's books go to the public Audible catalog (App activity → Other actions; optional; purpose: App functionality). **Also collected, by Google's Cast SDK:** App activity (the SDK's interactions with Cast devices: discovery and session events, phone and app information), per [Google's data disclosure](https://developers.google.com/cast/docs/android_sender/data_disclosure). Everything else stays on the device or goes to the user's own server. |
| Data shared with third parties | **Not declared as shared:** the Audible lookup happens only after prominent in-app disclosure and consent, per profile, Google's exemption for sharing. Without that profile's consent nothing is sent for it. **Cast SDK:** Google states it shares this with no third party. **Sending a title to a Cast device** (session address, title, author, cover address) is an action the user starts, to a device the user picks. |
| Is all of the user data encrypted in transit? | **Yes** for the declared Audible lookup (catalog and covers over https only). For the user's own server: https, or plain http only after an explicit confirmation by the user for that address. |
| Do you provide a way for users to request that their data is deleted? | **No** for what Audible received (the developer has no influence on it). Locally: uninstalling the app or clearing its app data in Android settings removes all local data; data on the server is managed there. |
| Analytics / ads / advertising ID | None by the app; no ads and no advertising ID. Google's Cast SDK logs its own use (see below) once the user opened the full player on a device with Google Play services. |
| Cast SDK in the form (to check with the form itself before submitting) | Data type: App activity → App interactions. Purposes: Analytics, App functionality. Processed: not ephemeral by Google's wording ("for a short period of time", then aggregated). Encrypted in transit: yes. Optional for the user: not within the SDK; the app starts it only when the full player opens. Deletion: neither developers nor users can delete it, says Google. |
| Account creation | No account with the developer (login is to the user's own server) |

## Stored on device (informational)

- Access and refresh tokens, per profile (encrypted with Tink / Android Keystore-backed key where available)
- The app PIN of a protected profile, only if the user sets one: a salted hash, encrypted with the same key; it never leaves the device
- Library metadata, covers, listening progress, downloaded audio files, settings
- Home Wi-Fi names (SSIDs) for the optional local-address binding — stored on device only
- A masked diagnostic log; it leaves the device only if the user shares it (user-initiated action)
- The id of a server session opened for a Cast device, until it is closed (so the next start can close it after a crash)

## Chromecast (what goes where)

- **To the Cast device the user picks:** the session address (`/public/session/<id>/track/<n>`, dead once the session is closed), title, author, cover address. **Never** an access or refresh token.
- **From the server to the Cast device:** audio and cover, directly.
- **To Google, by the Cast SDK:** see the table. It runs only on devices with Google Play services, and only after the user opened the full player in this run of the app.
- Discovery: done by Google Play services, per Google "occasionally" when the app comes to the foreground and while the picker is open, stopped in the background. The app also requests discovery while the full player is on screen.

## Permissions and their purpose

- INTERNET, ACCESS_NETWORK_STATE: talk to the user's server
- ACCESS_WIFI_STATE, ACCESS_FINE/COARSE_LOCATION, NEARBY_WIFI_DEVICES: read the Wi-Fi SSID only when home-network behavior is configured (the location permission is required by Android for this); the SSID is processed on-device only and is never stored on a server or transmitted; location itself is not used
- FOREGROUND_SERVICE (+ MEDIA_PLAYBACK, DATA_SYNC), POST_NOTIFICATIONS: playback and download notifications
- USE_BIOMETRIC: the system's lock prompt, only when a profile is protected by the device lock; the app learns only whether it was passed and sees no fingerprint or lock code
- Cast adds **no permission**: the merged manifest of 0.5.0 with `media3-cast` and `play-services-cast-framework` 22.3.1 lists the same `uses-permission` entries as without them (WAKE_LOCK, RECEIVE_BOOT_COMPLETED and ACCESS_NETWORK_STATE were already there through WorkManager). It adds a package query (`com.google.android.gms.policy_cast_dynamite`), non-exported framework components and Google's transport services, and `MediaTransferReceiver` (exported, required by the system's output switcher)

## Other declarations

- Target audience: general / not directed at children
- Content: no ads; foreground service types declared as above (media playback, data sync for downloads)
- Privacy policy URL: https://github.com/Detair/lauschig/blob/main/docs/privacy-policy.md
