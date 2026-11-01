# Phase 2b – API-Recherche (2026-11-01)

Quellen: ABS v2.37.1 (Server) und audiobookshelf-app (offizielle App), ReadMeABook v1.2.12 (Commit 1e261b5),
Audible-Katalog live abgefragt. „(V)“ = im Quelltext bzw. live geprüft, „(I)“ = abgeleitet.

## 1. Audible-Katalog (ohne Konto, inoffiziell, darf ausfallen)

Basis je Region: `https://api.audible.de` (de), `https://api.audible.com` (us), `.co.uk` (uk), `.fr`, `.it`, `.es`,
`.ca`, `.com.au`. Header `Accept: application/json`. RMAB nutzt 10 s Timeout.

- **Produkt mit Serie (V, live):** `GET /1.0/catalog/products/{asin}?response_groups=series,relationships`
  - `product.series = [{asin, title, sequence, url}]`
  - `product.relationships[]`: für die Serie `relationship_to_product: "parent"`, `relationship_type: "series"`,
    `content_delivery_type: "BookSeries"`.
  - Unbekannte ASIN: 404 bzw. leeres `product`.
- **Bände einer Serie (V, live):** `GET /1.0/catalog/products/{seriesAsin}?response_groups=relationships`
  - Liefert `product.relationships[]` mit `relationship_to_product: "child"`, `relationship_type: "series"`,
    `asin`, `sequence` (String, z. B. "47", "1.5", "1-3" bei Boxen, auch leer), `sort` (String-Zahl).
  - Die drei ???: 252 Einträge, unsortiert.
- **Details in Paketen (V, live):** `GET /1.0/catalog/products?asins=a,b,c&response_groups=product_desc,contributors,media,product_attrs,series`
  (höchstens 50 ASINs pro Aufruf (I)) → `{products:[…]}`. Je Produkt:
  - `asin, title, subtitle, authors[{name}], narrators[{name}]|null, release_date "YYYY-MM-DD", runtime_length_min`
  - `language` ("german", "english", …), `format_type` ("unabridged"/"abridged")
  - `content_delivery_type` ("SinglePartBook", "MultiPartBook", …)
  - `content_type` ("Product" = Hörbuch, "Performance" = Hörspiel)
  - `is_listenable`, `product_images{"500": url}`, `merchandising_summary` (HTML, kann fehlen)
- **Audnexus (Alternative):** `GET https://api.audnex.us/books/{asin}?region=de` → `seriesPrimary{asin,name,position}`.
  Wird nicht gebraucht, solange der Katalog antwortet.
- Bekannt aus dem Homelab: Dieselbe deutsche Ausgabe hat im US- und im DE-Katalog verschiedene ASINs. ABS-Titel
  können US-ASINs tragen (RMAB hat früher US genutzt). Deshalb: Region aus den Einstellungen zuerst, bei 404 die
  andere Region (de ↔ us) probieren.

## 2. ABS

### Bücher
- Minifizierte Liste (`/api/libraries/:id/items?minified=1`), `media.metadata` (V):
  - `asin, isbn, title, subtitle, authorName, narratorName`
  - `seriesName` ("Name #1"), `genres, publishedYear, language, explicit, abridged, description`
  - Keine `series[]`/`authors[]`-Arrays.
- Expandiert (`/api/items/:id?expanded=1`) (V): zusätzlich `series[{id,name,sequence}]`, `authors[{id,name}]`.

### Podcasts
- **Liste (V):** Podcast-Items mit `mediaType: "podcast"`.
  - `media`: `metadata` (title, author, description, feedUrl, …), `numEpisodes`, `tags`, `size`.
  - **Keine Episoden.**
- **Expandiert (V):** `media.episodes[]`, je Episode:
  - `id, index, season, episode, episodeType, title, subtitle, description`
  - `pubDate` (String), `publishedAt` (ms), `addedAt`
  - `chapters[]`, `audioFile{ino, duration, metadata{filename, ext, size}}`, `duration`, `size`
  - Selbst nach `publishedAt` sortieren.
- **Neueste Episoden (V):** `GET /api/libraries/:id/recent-episodes?limit=N&page=0` (nur Podcast-Bibliotheken, sonst 404).
  - Liefert `{episodes:[Episode + libraryItemId + podcast{metadata…} + libraryId]}`.
  - Nur ungehörte Episoden, neueste zuerst. `limit` immer setzen.
- **Datei (V):** `GET /api/items/:itemId/file/:ino` (Stream) bzw. `/download`. `ino` = `episode.audioFile.ino`.
  Bearer-Token wie bei Büchern.
- **Fortschritt (V):** `/api/me` → `mediaProgress[]` mit `libraryItemId` und `episodeId` (bei Büchern null).
  - Schreiben: `PATCH /api/me/progress/:libraryItemId/:episodeId` mit demselben Body wie bei Büchern.
    Antwort: 200 ohne Body; 404 bei unbekannter Episode.
- **Lokale Sessions (V):** `POST /api/session/local-all` wie bei Büchern, je Session zusätzlich `episodeId`.
  - Fehlende Episode → `{success:false, error:"Media item not found"}`.

### OIDC (SSO) für Apps (V)
1. **Start:** PKCE (S256) und `state` erzeugen. Im **eigenen** OkHttp-Client mit Cookie-Jar und **ohne Redirect-Folgen**
   aufrufen:
   `GET {server}/auth/openid?response_type=code&client_id=Lauschig&redirect_uri=lauschig%3A%2F%2Foauth&code_challenge=…&code_challenge_method=S256&state=…`
   - Antwort ist ein 302 auf den IdP; die Adresse steht im `Location`-Header.
   - Der Server setzt dabei zwei Cookies: Session-Cookie und `auth_method=openid-mobile`.
   - 400 „Invalid redirect_uri“ heißt: Die Redirect-URI ist nicht freigegeben.
2. **Anmeldung:** Die `Location` in einem Custom Tab öffnen. Der IdP leitet über den Server auf
   `lauschig://oauth?code=…&state=…` weiter.
3. **Abschluss:** `state` prüfen. Dann mit **demselben Cookie-Jar** aufrufen:
   `GET {server}/auth/openid/callback?state=…&code=…&code_verifier=…`
   - Antwort ist JSON wie bei `POST /login`: `{user:{…, accessToken, refreshToken}, userDefaultLibraryId, serverSettings}`.
   - Den Refresh-Token gibt es nur wegen des Cookies `auth_method=openid-mobile` (der `x-return-tokens`-Header spielt
     hier keine Rolle).
   - Ohne Session-Cookie: 400 „No session“.
4. **Freigabe durch den Admin:** In der Servereinstellung `authOpenIDMobileRedirectURIs` muss `lauschig://oauth` stehen.
   - Standard ist nur `audiobookshelf://oauth`. Verglichen wird exakt, `*` erlaubt alles.
   - Die Einstellung findet sich in der ABS-Oberfläche unter Einstellungen → Authentifizierung.
5. **Anmeldearten abfragen (V):** `GET /status` (ohne Login) →
   `{serverVersion, isInit, authMethods:["local","openid"], authFormData:{authLoginCustomMessage, authOpenIDButtonText, authOpenIDAutoLaunch}}`.
   - `openid` erscheint nur, wenn OIDC gültig konfiguriert ist.
6. **Erneuern:** Wie bisher `POST /auth/refresh` mit `x-refresh-token`.

## 3. ReadMeABook (RMAB) v1.2.12 (V)

- **Auth:** nur `Authorization: Bearer <token>`. Keine Cookies, kein CSRF, kein CORS nötig.
  Ein Login mit ABS-Zugangsdaten gibt es **nicht**.
- **Anmeldearten:** `GET /api/auth/providers` (ohne Login) → `{backendMode, providers:["oidc","local"], registrationEnabled}`.
- **Lokaler Login:** `POST /api/auth/local/login` `{username, password}`.
  - Erfolg: `{success:true, user, accessToken, refreshToken}`. Access-Token gilt 1 h, Refresh-Token 7 d.
  - Fehler: 401 `{error}`.
  - Konto wartet auf Freigabe: 200 `{success:false, pendingApproval:true, message}`.
  - Lokaler Login abgeschaltet: 403.
- **Dauerhafter API-Token (für Lauschig):** `POST /api/user/api-tokens` `{name, expiresAt:null}`, mit dem JWT aus dem Login.
  - Antwort: 201 `{token:{id,…}, fullToken}`. `fullToken` hat die Form `rmab_` + 64 Hex-Zeichen und wird nur einmal
    angezeigt.
  - Höchstens 25 Tokens pro Nutzer.
  - Erlaubt mit API-Token: `GET /api/auth/me`, `GET /api/audiobooks/search`, `GET|POST /api/requests`,
    `GET /api/requests/:id`.
  - `/api/series/*` ist **nicht** erlaubt (403).
- **Anfrage:** `POST /api/requests`
  `{"audiobook":{"asin","title","author","narrator?","description?","coverArtUrl?"}}`. `asin`, `title` und `author`
  sind Pflicht.
  - Erfolg: 201 `{success:true, request}`.
  - Fehler (`{error, message}`):

    | Status | `error` |
    |---|---|
    | 409 | `AlreadyAvailable` |
    | 409 | `BeingProcessed` |
    | 409 | `DuplicateRequest` |
    | 409 | `Ignored` |
    | 400 | `ValidationError` |
    | 404 | `UserNotFound` |
    | 500 | `RequestError` |

  - Hat derselbe Nutzer eine fehlgeschlagene oder abgebrochene Anfrage, wird sie ersetzt.
- **Eigene Anfragen:** `GET /api/requests?myOnly=true&take=100&cursor=…` →
  `{requests:[{id, status, audiobook:{audibleAsin, title, …}, …}], nextCursor}`.
  - Status-Werte: `pending, searching, downloading, processing, awaiting_search, awaiting_import, awaiting_approval,
    awaiting_release, available, downloaded, failed, cancelled, denied, warn`.
