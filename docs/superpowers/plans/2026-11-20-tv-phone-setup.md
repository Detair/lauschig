# Fernseher mit dem Handy einrichten – Umsetzungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Ziel:** Der Fernseher zeigt einen QR-Code und eine vierstellige Zahl. Das Handy scannt ihn unter „Fernseher einrichten“, fragt das Passwort des Kontos und übergibt dem Fernseher verschlüsselt eine frische Sitzung. Der Fernseher ist danach angemeldet.

**Architektur:** Ein neues Paket `io.wolftown.lauschig.tvsetup` im Modul `:app` hält Format, Verschlüsselung (HPKE aus Tink) und die zwei Enden einer TCP-Verbindung im Heimnetz, ohne Oberfläche. Die TV-Oberfläche (`tv/setup`) zeigt den Code und meldet sich mit dem übergebenen Refresh-Token über das vorhandene `SetupViewModel` an. Die Handy-Oberfläche (`ui/tvsetup`) scannt, fragt das Passwort, holt sich am Server eine neue Sitzung und sendet sie.

**Tech Stack:** Kotlin, Tink 1.24.0 (`tink-android`, schon eingebaut), `java.net.ServerSocket`/`Socket`, kotlinx.serialization, ZXing und CameraX (schon eingebaut), Compose, `androidx.tv:tv-material`, Hilt, Robolectric.

**Spec:** [`docs/superpowers/specs/2026-11-16-google-tv-design.md`](../specs/2026-11-16-google-tv-design.md), Abschnitte „Einrichtung mit dem Handy“ und „Einrichtung mit dem Handy – entschieden beim Bau“ (E13 bis E19).

## Global Constraints

- **Keine neue Bibliothek, keine neue Berechtigung, keine Play Services.** Kein neuer Eintrag im Manifest. Es gibt keinen Intent-Filter für `lauschig://tv`: Nur der Scanner in der App liest den Code.
- **Kein selbst gebautes Krypto-Verfahren.** Verschlüsselt wird nur über Tinks öffentliche HPKE-Schnittstelle (`HybridEncrypt`/`HybridDecrypt`, `HpkeParameters`). Die genaue API steht in der eingebauten Bibliothek (`tink-android-1.24.0.jar` im Gradle-Cache); dort nachsehen, nicht raten.
- **Geheimnisse:** Der private Schlüssel des Fernsehers lebt nur im Speicher und nur, solange ein Code gilt. Refresh-Token, Passwort, Schlüssel, Code-Text und Adressen stehen in keinem Log, keiner Fehlermeldung, keinem `toString()`, keinem `SavedStateHandle` und keiner Datei. Datenklassen mit Geheimnissen überschreiben `toString()`.
- **Heimnetz:** Adressen des Fernsehers sind wörtliche IPv4-Adressen aus 10.0.0.0/8, 172.16.0.0/12 oder 192.168.0.0/16. Es wird nie ein Name aufgelöst. Das Handy sendet nur über ein Netz mit WLAN- oder Kabel-Transport, das kein VPN ist.
- **Feste Werte:** Code gilt höchstens 5 Minuten · genau ein angenommenes Paket je Code · höchstens 20 abgelehnte Verbindungen je Code · Lesefrist je Verbindung 5 s insgesamt · Handy: Verbindungsaufbau 5 s, Warten auf Antwort 30 s · Klartextblock 2048 Bytes · Paket 2100 Bytes.
- **Handy sonst unverändert,** TV-Regeln aus dem ersten Plan gelten weiter: jede TV-Seite zweigeteilt (`Tv…Screen(state, callbacks)` und `Tv…Route`), genau ein fokussiertes Element beim Betreten, alles mit Steuerkreuz und OK, Ränder aus `TvDimens`, kein Wachhalten des Bildschirms.
- **Logik wird genutzt, nicht kopiert.** Die Anmeldung läuft über `SetupViewModel` und `LoginClient`.
- **Texte:** jeder neue Text in `values/strings.xml` (Englisch) und `values-de/strings.xml`. Schlüssel am Fernseher mit Präfix `tv_`, am Handy mit Präfix `tvsetup_`. Kein Text nennt „RMAB“ oder „ReadMeABook“.
- **Tests:** JUnit und Robolectric nach den Mustern im Projekt, Fakes statt Hilt. TV-Seiten mit `@Config(sdk = [35], qualifiers = TV_QUALIFIERS)`. Netz-Tests laufen über `127.0.0.1` mit Port 0, ohne feste Wartezeiten.
- **Befehle**, nie gleichzeitig, immer in `timeout`: `timeout 1500 ./gradlew :app:testDebugUnitTest`, `timeout 1500 ./gradlew lint`, `timeout 1500 ./gradlew assembleDebug assembleRelease`. Lint und Tests nie im selben Aufruf.
- **Öffentliches Repo:** keine privaten Hostnamen, echten IP-Adressen (Beispiele nur aus 192.168.1.x und `example.org`) oder Geräte-Seriennummern. Kommentare auf Englisch und mit dem Grund, nicht mit dem Was.
- Weicht eine hier genannte vorhandene Schnittstelle vom Code ab, gilt der Code. Die Abweichung steht dann im Bericht.

## Das Protokoll (gilt für alle Aufgaben)

**Code am Fernseher** (eine Zeile Text, höchstens 256 Zeichen):

```
lauschig://tv?v=1&a=192.168.1.50&p=41234&k=<öffentlicher X25519-Schlüssel, 32 Bytes, Base64-URL ohne Füllzeichen>
```

- `v` muss `1` sein. `a`, `p`, `k` kommen genau einmal vor; ein doppelter Teil lehnt den Code ab. Unbekannte Teile werden verworfen.
- `a`: vier Dezimalzahlen 0–255 mit Punkten, ohne führende Nullen, aus einem der drei privaten Netze. `p`: 1024–65535. `k`: genau 32 Bytes.
- **Zahl:** SHA-256 über den UTF-8-Text `"lauschig-tv-number-v1\n" + a + "\n" + p + "\n" + k` (`k` wie im Code geschrieben, `p` dezimal); die ersten vier Bytes als vorzeichenlose Zahl (Big-Endian) modulo 10000, mit führenden Nullen auf vier Stellen.

**Verschlüsselung:** HPKE, Basis-Modus, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305, Tink-Variante ohne Präfix (`NO_PREFIX`). `contextInfo` = UTF-8 von `"lauschig-tv-setup-v1\n" + a + "\n" + p`.

**Klartext:** genau 2048 Bytes: zwei Bytes Länge `n` (Big-Endian), dann `n` Bytes JSON (UTF-8), dann Nullen. JSON:

```json
{"v":1,"local":"http://192.168.1.20:13378","public":"https://abs.example.org","refresh":"<Refresh-Token>"}
```

- `local` und `public` dürfen fehlen, mindestens eine Adresse muss da sein. Für jede gelten die Regeln der Server-Adressen aus `serverqr/ServerQr.kt` (`http`/`https`, kein Benutzer, keine Abfrage, kein Fragment). `refresh` ist nicht leer. Unbekannte Felder werden verworfen. Alles andere ist kein gültiges Paket.

**Paket Handy → Fernseher** (genau 2100 Bytes): die vier ASCII-Bytes `LTV1`, dann der HPKE-Geheimtext (32 Bytes gekapselter Schlüssel + 2048 + 16).

**Antwort Fernseher → Handy** (ein Byte, danach schließt der Fernseher): `1` angemeldet · `2` Paket nicht angenommen · `3` Fernseher erreicht den Server nicht · `4` Server lehnt die Sitzung ab · `5` anderer Fehler.

## Review Focus

1. **Alter Code:** Das Handy scannt einen Code, den der Fernseher schon ersetzt hat. Erwartet: keine Anmeldung, das Handy sagt „neu scannen“ und beendet die neue Sitzung am Server. (Tests in Task 1 und 3.)
2. **Handy ohne Heimnetz:** nur Mobilfunk oder nur VPN. Erwartet: Es verlässt kein Paket das Handy, und am Server entsteht keine Sitzung, die übrig bleibt. (Task 1, 3.)
3. **Seite verlassen:** Der Fernseher wechselt zur Startseite des Geräts oder zum Formular. Erwartet: Der Port ist zu, der Schlüssel weg; beim Zurückkommen gibt es einen neuen Code. (Task 1, 2.)
4. **Stumme Verbindung:** Ein Gerät verbindet sich und sendet nichts oder nur tröpfchenweise. Erwartet: nach 5 s getrennt, das echte Handy kommt danach durch. (Task 1.)
5. **Antwort nach Erfolg:** Nach der Anmeldung wechselt der Fernseher sofort die Seite. Erwartet: Das Handy bekommt trotzdem „angemeldet“. (Task 1, 2.)

---

### Task 1: Format, Verschlüsselung und die zwei Enden der Verbindung

**Files:**
- Create: `app/src/main/java/io/wolftown/lauschig/tvsetup/TvSetupCode.kt`, `Handover.kt`, `HandoverCrypto.kt`, `HandoverListener.kt`, `HandoverSender.kt`, `HomeNetwork.kt`
- Modify: `app/src/main/java/io/wolftown/lauschig/serverqr/ServerQr.kt` (die Adressprüfung für `Handover.kt` nutzbar machen, ohne ihr Verhalten zu ändern), bei Bedarf `app/proguard-rules.pro`
- Test: `app/src/test/java/io/wolftown/lauschig/tvsetup/` (`TvSetupCodeTest`, `HandoverFormatTest`, `HandoverCryptoTest`, `HandoverListenerTest`, `HandoverSenderTest`, `HandoverRoundTripTest`)

**Interfaces – Produces:**

```kotlin
// TvSetupCode.kt
class TvSetupCode(val address: String, val port: Int, publicKey: ByteArray) {   // equals/hashCode by value, toString without the key
    val publicKey: ByteArray          // a copy
    val text: String                  // the code's line
    val number: String                // four digits
    fun contextInfo(): ByteArray
}
enum class TvSetupCodeError { NOT_A_TV_CODE, UNKNOWN_VERSION, BAD_CODE, NOT_HOME_NETWORK, TOO_LONG }
sealed interface TvSetupCodeResult {
    data class Code(val code: TvSetupCode) : TvSetupCodeResult
    data class Rejected(val reason: TvSetupCodeError) : TvSetupCodeResult
}
object TvSetupCodes {
    const val MAX_LENGTH = 256
    fun read(text: String): TvSetupCodeResult
    /** A literal IPv4 address of 10/8, 172.16/12 or 192.168/16; never resolves a name. */
    fun homeAddress(address: String): java.net.Inet4Address?
}

// Handover.kt
@Serializable class PhoneHandover(val v: Int = 1, val local: String? = null, val public: String? = null, val refresh: String)  // toString without the token
object HandoverFormat {
    const val PLAIN_SIZE = 2048
    const val PACKET_SIZE = 2100
    fun encode(handover: PhoneHandover): ByteArray?   // PLAIN_SIZE bytes; null when it does not fit
    fun decode(plain: ByteArray): PhoneHandover?      // null for anything that is not a valid packet
}
enum class HandoverReply(val code: Int) { SIGNED_IN(1), NOT_ACCEPTED(2), SERVER_UNREACHABLE(3), SIGN_IN_REFUSED(4), FAILED(5) }

// HandoverCrypto.kt
class HandoverKey private constructor(/* Tink handle, in memory only */) {
    val publicKey: ByteArray                                               // 32 bytes
    fun open(ciphertext: ByteArray, contextInfo: ByteArray): ByteArray?    // null when it does not decrypt
    companion object { fun generate(): HandoverKey }
}
object HandoverCrypto { fun seal(publicKey: ByteArray, plain: ByteArray, contextInfo: ByteArray): ByteArray }

// HandoverListener.kt – the TV's end
data class HandoverLimits(val openMs: Long = 300_000, val readMs: Long = 5_000, val rejected: Int = 20)
sealed interface ListenResult {
    data class Finished(val reply: HandoverReply) : ListenResult   // one packet was accepted; reply is what signIn answered
    data object Expired : ListenResult
    data object TooManyAttempts : ListenResult
    data object Failed : ListenResult                              // the socket could not be opened or broke
}
class HandoverListener @Inject constructor() {
    constructor(limits: HandoverLimits)
    /** One code's life: opens a port on [bind], calls [onReady] with the code, and ends as [ListenResult] says. */
    suspend fun listen(bind: InetAddress, onReady: (TvSetupCode) -> Unit, signIn: suspend (PhoneHandover) -> HandoverReply): ListenResult
}

// HandoverSender.kt – the phone's end
enum class SendResult { SIGNED_IN, NOT_ACCEPTED, SERVER_UNREACHABLE, SIGN_IN_REFUSED, FAILED, NO_HOME_NETWORK, NOT_SENT, NO_REPLY }
fun interface HomeSockets { fun open(): java.net.Socket? }   // an unconnected socket of a Wi-Fi or Ethernet network that is no VPN; null: there is none
class HandoverSender(private val sockets: HomeSockets, private val connectMs: Int = 5_000, private val replyMs: Int = 30_000) {
    suspend fun send(code: TvSetupCode, handover: PhoneHandover): SendResult
}

// HomeNetwork.kt – Android
interface HomeNetwork : HomeSockets { fun ownAddress(): java.net.Inet4Address? }   // the device's own home address; null: none
class AndroidHomeNetwork @Inject constructor(@ApplicationContext context: Context) : HomeNetwork
// plus a Hilt module that binds HomeNetwork and provides HandoverSender
```

**Verhalten:**

- `TvSetupCodes.read`: Leerzeichen und Zeilenumbrüche außen zählen nicht. Ein anderer Text (auch der Server-Code `lauschig://server`) ist `NOT_A_TV_CODE`; `v` fehlt oder ist nicht `1`: `UNKNOWN_VERSION`; Adresse gültig, aber nicht aus einem privaten Netz: `NOT_HOME_NETWORK`; alles andere Ungültige (Adresse, Port, Schlüssel, doppelter Teil): `BAD_CODE`; mehr als 256 Zeichen: `TOO_LONG`.
- `HandoverListener.listen`:
  1. Erzeugt einen neuen Schlüssel, öffnet `ServerSocket(0, 1, bind)`, ruft `onReady` mit dem Code.
  2. Nimmt Verbindungen einzeln nacheinander an. Je Verbindung: innerhalb von `readMs` insgesamt genau 2100 Bytes lesen (eine Frist für die ganze Verbindung, nicht je Lesevorgang), `LTV1` prüfen, entschlüsseln, `HandoverFormat.decode`.
  3. Ungültig (zu kurz, zu langsam, falsche Kennung, nicht entschlüsselbar, kein gültiger Inhalt): Antwort `2` versuchen, schließen, zählen. Bei `rejected` abgelehnten Verbindungen endet der Code mit `TooManyAttempts`.
  4. Gültig: sofort den `ServerSocket` schließen und den Schlüssel verwerfen, dann `signIn` aufrufen, die Antwort schreiben und mit `Finished(reply)` enden. Die Antwort wird auch dann noch geschrieben, wenn der Aufrufer in diesem Moment abgebrochen wird (der Fernseher wechselt nach dem Erfolg die Seite); das Schreiben blockiert nicht länger als 2 s.
  5. Nach `openMs` ohne gültiges Paket: `Expired`.
  6. Abbruch der Coroutine schließt Port und Verbindung sofort. Läuft blockierend auf `Dispatchers.IO`; ein Abbruch darf nicht auf eine Lesefrist warten.
  7. Wirft `signIn`, endet es mit Antwort `5`.
- `HandoverSender.send`: prüft die Adresse noch einmal mit `TvSetupCodes.homeAddress` (sonst `NOT_SENT`), holt einen Socket von `sockets` (`null`: `NO_HOME_NETWORK`), verbindet zur wörtlichen Adresse, sendet das Paket, liest ein Byte. Passt der Inhalt nicht in den Block: `NOT_SENT`. Schlägt etwas fehl, bevor das Paket vollständig geschrieben ist: `NOT_SENT`. Danach keine oder eine unbekannte Antwort: `NO_REPLY`. Läuft auf `Dispatchers.IO`, ein Abbruch schließt den Socket.
- `AndroidHomeNetwork`: `ownAddress()` ist die erste private IPv4-Adresse eines Netzes mit `TRANSPORT_WIFI` oder `TRANSPORT_ETHERNET` und ohne `TRANSPORT_VPN` (zuerst das aktive Netz). `open()` gibt `network.socketFactory.createSocket()` eines solchen Netzes zurück. Beides ohne neue Berechtigung (`ACCESS_NETWORK_STATE` ist da).

**Tests (mindestens):**

- Code: Hin und zurück; die Zahl für einen festen Code als Konstante im Test (einmal von Hand mit `sha256sum` nachgerechnet, der Rechenweg steht im Testkommentar); andere Adresse, anderer Port oder anderer Schlüssel ergeben den erwarteten anderen Wert; jede Ablehnung aus „Verhalten“; öffentliche Adresse (`8.8.8.8`), `172.32.0.1`, `192.169.0.1`, Hostname, IPv6, führende Null, Port 80, Schlüssel mit 31 Bytes.
- Format: Hin und zurück; zu langer Inhalt gibt `null`; falsche Blockgröße, Länge größer als der Block, kaputtes JSON, `v` = 2, keine Adresse, Adresse mit Benutzer, leeres Token geben `null`; zwei verschieden lange Inhalte ergeben gleich lange Blöcke.
- Krypto: Hin und zurück; falscher Schlüssel, falsches `contextInfo` (anderer Port), ein gekipptes Bit geben `null`; zwei Verschlüsselungen desselben Inhalts unterscheiden sich; Geheimtext ist 2096 Bytes lang.
- Listener (über `127.0.0.1`): gültiges Paket ruft `signIn` einmal und antwortet mit dessen Wert; danach ist der Port zu (zweite Verbindung scheitert); Müll und ein Paket für einen anderen Schlüssel antworten `2` und zählen; nach `rejected` Ablehnungen `TooManyAttempts`; stumme Verbindung wird nach `readMs` getrennt und das richtige Paket kommt danach durch; `Expired` nach `openMs`; Abbruch schließt den Port; Abbruch direkt nach `signIn` liefert dem Sender trotzdem die Antwort.
- Sender: Adresse außerhalb des Heimnetzes sendet nichts (der Fake zählt geöffnete Sockets: 0); kein Socket: `NO_HOME_NETWORK`; niemand hört: `NOT_SENT`; Gegenstelle schließt ohne Antwort: `NO_REPLY`.
- Rundlauf: echter `HandoverListener` und echter `HandoverSender` über `127.0.0.1` (der Test baut den Code mit der Schleifenadresse selbst und umgeht dafür nur die Heimnetz-Prüfung über einen Test-Parameter, der im Produktivcode nicht erreichbar ist).

- [ ] Tests schreiben, fehlschlagen sehen, umsetzen, `timeout 1500 ./gradlew :app:testDebugUnitTest --tests 'io.wolftown.lauschig.tvsetup.*'` grün, dann die ganze Suite, Lint und `assembleDebug assembleRelease`. Commit.

---

### Task 2: Der Fernseher zeigt den Code und meldet sich an

**Files:**
- Create: `app/src/main/java/io/wolftown/lauschig/tv/setup/TvPhoneSetupScreen.kt`, `tv/setup/TvPhoneSetupViewModel.kt`
- Modify: `ui/setup/LoginClient.kt`, `ui/setup/SetupViewModel.kt`, `tv/setup/TvSetupScreen.kt` (`TvSetupRoute`), `values/strings.xml`, `values-de/strings.xml`, vorhandene Fakes von `LoginClient` in den Tests
- Test: `app/src/test/java/io/wolftown/lauschig/tv/setup/TvPhoneSetupScreenTest.kt`, `TvPhoneSetupViewModelTest.kt`, Ergänzung in `ui/setup/SetupViewModelTest.kt`

**Interfaces – Consumes:** alles aus Task 1 (`HandoverListener`, `ListenResult`, `HomeNetwork`, `PhoneHandover`, `HandoverReply`, `TvSetupCode`), `QrCode.modules` und `QrCodeImage` aus `serverqr`, `AuthApi.refresh`.

**Interfaces – Produces:**

```kotlin
// LoginClient.kt
interface LoginClient { /* … */ suspend fun refresh(baseUrl: HttpUrl, refreshToken: String): LoginResponse }

// SetupViewModel.kt
/** A session the phone handed over: exchanged at the server, then saved like any sign-in. Never in the mode of a forgotten PIN. */
suspend fun signInFromPhone(handover: PhoneHandover): HandoverReply

// TvPhoneSetupViewModel.kt
sealed interface TvPhoneSetupState {
    data object Starting : TvPhoneSetupState
    data object NoHomeNetwork : TvPhoneSetupState
    data class Waiting(val code: String, val number: String) : TvPhoneSetupState
    data object SigningIn : TvPhoneSetupState
    data class Stopped(val reason: TvPhoneSetupStop) : TvPhoneSetupState
}
enum class TvPhoneSetupStop { EXPIRED, TOO_MANY_ATTEMPTS, SERVER_UNREACHABLE, SIGN_IN_REFUSED, FAILED }
@HiltViewModel class TvPhoneSetupViewModel @Inject constructor(network: HomeNetwork, listener: HandoverListener) : ViewModel() {
    val state: StateFlow<TvPhoneSetupState>
    fun show(signIn: suspend (PhoneHandover) -> HandoverReply)   // the page is on screen: a new code
    fun hide()                                                    // the page is gone: port closed, key forgotten
    fun newCode()
}

// TvPhoneSetupScreen.kt
@Composable fun TvPhoneSetupScreen(state: TvPhoneSetupState, addProfile: Boolean, onNewCode: () -> Unit, onType: () -> Unit)
```

**Verhalten:**

- `SetupViewModel.signInFromPhone`: versucht wie `attempt` zuerst die lokale, dann die öffentliche Adresse mit `loginClient.refresh`. 401 oder 403: `SIGN_IN_REFUSED` (endgültig). Keine Adresse antwortet: `SERVER_UNREACHABLE`. Danach derselbe Abschluss wie bei jeder Anmeldung (`complete`): Konto speichern, bei „Profil hinzufügen“ als weiteres Profil, Sync anstoßen, `done = true`. Server zu alt oder Speichern scheitert: `FAILED`. Erfolg: `SIGNED_IN`. Unverschlüsselte Adressen aus der Übergabe gelten als bestätigt (die Person hat sie am Handy bestätigt und dort vor dem Senden gesehen). Im Modus „PIN vergessen“ und bei ungültigen Adressen: `FAILED`, ohne Anfrage. Während der Anmeldung zeigt `state.loading` nichts Neues an; der Zustand der Seite kommt aus `TvPhoneSetupViewModel`.
- `AbsLoginClient.refresh`: wie `login` über `AuthApi.refresh`, mit denselben Schutzregeln (keine Weiterleitung, Klartext nur an die eine Adresse).
- `TvPhoneSetupViewModel`: `show` startet `listener.listen` auf `network.ownAddress()`; ohne Adresse `NoHomeNetwork`. `onReady` → `Waiting`. Ruft der Listener `signIn`, wird der Zustand `SigningIn`. `ListenResult` → `Stopped` mit dem passenden Grund; bei `Finished(SIGNED_IN)` bleibt `SigningIn` (die Seite wechselt gleich). `hide` und `onCleared` brechen ab. `newCode` startet neu. Ein zweites `show` ohne `hide` startet keinen zweiten Listener.
- `TvSetupRoute`: Im Modus Anmelden und „Profil hinzufügen“ zeigt sie zuerst `TvPhoneSetupScreen`; „Mit der Fernbedienung tippen“ wechselt zum Formular, Zurück im Formular führt zur Code-Seite zurück (und schließt das Formular nicht). Im Modus „PIN vergessen“ gibt es nur das Formular, wie bisher. Die Code-Seite ruft `show` beim Start (`Lifecycle.State.STARTED`) und `hide` beim Stopp und beim Wechsel zum Formular: Der Port ist nur offen, solange die Code-Seite sichtbar ist. Welche der zwei Seiten zu sehen ist, übersteht eine Neuerstellung der Activity.
- `TvPhoneSetupScreen`:
  - `Waiting`: links Überschrift („Mit dem Handy einrichten“, bei `addProfile` „Profil hinzufügen“) und drei nummerierte Schritte (Lauschig am Handy öffnen → Einstellungen → „Fernseher einrichten“; Code scannen; Zahl vergleichen und Passwort tippen), darunter der Hinweis, dass Handy und Fernseher im selben Heimnetz sein müssen. Rechts der QR-Code (schwarz auf weiß, mindestens 240 dp Kante) und darunter die Zahl groß, in zwei Zweiergruppen („48 21“), mit Beschreibung für die Vorlesefunktion. Der Knopf „Mit der Fernbedienung tippen“ hat den Fokus.
  - `Starting`: dieselbe Seite ohne Code, mit Fortschrittsanzeige an dessen Stelle.
  - `SigningIn`: „Der Fernseher meldet sich an …“ mit Fortschrittsanzeige; der Code ist weg.
  - `NoHomeNetwork`: Erklärung (kein WLAN oder Kabel im Heimnetz), Knöpfe „Erneut versuchen“ (Fokus) und „Mit der Fernbedienung tippen“.
  - `Stopped`: ein Satz je Grund (abgelaufen · zu viele Versuche · Fernseher erreicht den Server nicht · Server lehnt ab · Fehler), Knöpfe „Neuer Code“ (Fokus) und „Mit der Fernbedienung tippen“.
  - Wechselt der Zustand, liegt der Fokus danach auf dem Knopf, der oben genannt ist, nie im Nichts.

**Tests:** `signInFromPhone`: Erfolg speichert das Konto mit den Adressen der Übergabe und den Tokens aus der Antwort und setzt `done`; lokale Adresse tot, öffentliche antwortet; 401 → `SIGN_IN_REFUSED`, nichts gespeichert; nichts erreichbar → `SERVER_UNREACHABLE`; „Profil hinzufügen“ nutzt den Weg des weiteren Profils; „PIN vergessen“ → `FAILED` ohne Anfrage; `http`-Adresse der Übergabe landet in `allowCleartext`. · ViewModel: ohne Adresse `NoHomeNetwork`; `Waiting` mit Code und Zahl; jeder `ListenResult` ergibt den erwarteten Zustand; `hide` bricht den Listener ab; `newCode` gibt einen anderen Code; doppeltes `show` startet einen Listener. · Seite: je Zustand der genannte Knopf fokussiert und per OK ausgelöst; die Zahl steht in Zweiergruppen da; in `SigningIn` ist kein QR-Code im Baum. · Route: „PIN vergessen“ zeigt nur das Formular; Zurück im Formular führt zur Code-Seite.

- [ ] Tests schreiben, fehlschlagen sehen, umsetzen, ganze Suite grün, Lint, `assembleDebug assembleRelease`. Commit.

---

### Task 3: „Fernseher einrichten“ am Handy, Doku

**Files:**
- Create: `app/src/main/java/io/wolftown/lauschig/ui/tvsetup/TvHandoverViewModel.kt`, `ui/tvsetup/TvHandoverScreen.kt`, `docs/tv-einrichtung-mit-dem-handy.md`
- Modify: `ui/nav/Destinations.kt` und `ui/nav/AppNavHost.kt` (neue Seite), die Einstellungen des Handys (neue Zeile „Fernseher einrichten“), `values/strings.xml`, `values-de/strings.xml`, `docs/privacy-policy.md`, `docs/play-store/data-safety.md`, `docs/tester-anleitung.md`, `README.md`
- Test: `app/src/test/java/io/wolftown/lauschig/ui/tvsetup/TvHandoverViewModelTest.kt`, `TvHandoverScreenTest.kt`, ein Test, dass das Manifest keinen Filter für `lauschig://tv` hat (nach dem Muster des Tests für den Server-Code, falls es einen gibt)

**Interfaces – Consumes:** `TvSetupCodes.read`, `TvSetupCode`, `PhoneHandover`, `HandoverSender`, `SendResult` (Task 1); `AccountStore`, `LoginClient.login`, `ServerLogout.logoutProfile`, `ServerQrScanner` und der Ablauf der Kamera-Berechtigung aus `ui/setup/SetupScreen.kt`.

**Interfaces – Produces:**

```kotlin
sealed interface TvHandoverStep {
    /** What the page explains before the camera opens; [note] says why the last scan was not taken. */
    data class Intro(val note: TvHandoverNote? = null) : TvHandoverStep
    data object Scanning : TvHandoverStep
    data class Confirm(val number: String, val servers: List<String>, val username: String, val password: String = "",
        val working: Boolean = false, val error: TvHandoverError? = null) : TvHandoverStep
    data object Done : TvHandoverStep
    data class Failed(val reason: TvHandoverError) : TvHandoverStep
}
enum class TvHandoverNote { CAMERA_REFUSED, NOT_A_TV_CODE, SERVER_CODE, OTHER_VERSION, NOT_HOME_NETWORK }
enum class TvHandoverError { WRONG_PASSWORD, SERVER_UNREACHABLE, NO_HOME_NETWORK, TV_UNREACHABLE, CODE_EXPIRED, TV_CANNOT_REACH_SERVER, TV_REFUSED, NO_REPLY, FAILED }
@HiltViewModel class TvHandoverViewModel @Inject constructor(accounts: AccountStore, loginClient: LoginClient, sender: HandoverSender, logout: ServerLogout) : ViewModel() {
    val step: StateFlow<TvHandoverStep>
    fun startScan(); fun cameraRefused(); fun scanClosed(); fun onScanned(text: String)
    fun onPassword(value: String); fun send(); fun again()
}
```

**Verhalten:**

- Einstieg: eine Zeile „Fernseher einrichten“ in den Einstellungen des Handys, nur mit angemeldetem Konto. Sie öffnet die neue Seite. Kein Link von außen öffnet sie.
- `Intro`: erklärt in drei Sätzen, was passiert (Code am Fernseher scannen; Passwort tippen; der Fernseher bekommt eine eigene Anmeldung, das Passwort bleibt auf dem Handy). Knopf „Code scannen“ fragt die Kamera-Berechtigung wie das Einrichtungsformular und öffnet `ServerQrScanner`.
- `onScanned`: `TvSetupCodes.read`. Ein Code des Fernsehers führt zu `Confirm`. Der Server-Code (`ServerQr.read` nimmt ihn an) gibt die Notiz `SERVER_CODE` („Das ist der Code eines Servers, nicht der eines Fernsehers“), eine andere Version `OTHER_VERSION`, eine Adresse außerhalb des Heimnetzes `NOT_HOME_NETWORK`, alles andere `NOT_A_TV_CODE`. Der gescannte Text wird nirgends gespeichert oder geloggt.
- `Confirm`: „Diesen Fernseher (Zahl 48 21) mit dem Server … und dem Konto … einrichten?“, darunter der Hinweis, die Zahl mit der am Fernseher zu vergleichen, die Adressen des Servers (lokal zuerst), das Passwortfeld (mit Anzeigen-Schalter) und der Hinweis, dass Konten, die sich nur über SSO anmelden, den Fernseher noch nicht einrichten können. Knopf „Fernseher einrichten“, aktiv mit nicht leerem Passwort.
- `send`:
  1. Meldet sich mit Benutzername des aktiven Kontos und dem getippten Passwort am Server an (`loginClient.login`, lokale Adresse zuerst, dann die öffentliche; nur die Adressen des Kontos, Klartext nur wie für das Konto bestätigt). 401/403: `WRONG_PASSWORD`, die Seite bleibt bei `Confirm` und das Feld leert sich. Nichts erreichbar: `SERVER_UNREACHABLE`. Antwortet der Server mit einem anderen Benutzer als dem des Kontos oder ohne Refresh-Token: `FAILED` (und die Sitzung wird beendet).
  2. Sendet `PhoneHandover(local, public, refresh)` mit den Adressen des Kontos (ohne WLAN-Namen) über `HandoverSender`.
  3. `SIGNED_IN` → `Done`. Jedes andere Ergebnis → die neue Sitzung am Server beenden (`logout.logoutProfile(account, refresh)`, in `NonCancellable`, Fehler dabei zählen nicht) und `Failed` mit dem passenden Grund: `NOT_ACCEPTED` → `CODE_EXPIRED` · `SERVER_UNREACHABLE` → `TV_CANNOT_REACH_SERVER` · `SIGN_IN_REFUSED` → `TV_REFUSED` · `NO_HOME_NETWORK` → `NO_HOME_NETWORK` · `NOT_SENT` → `TV_UNREACHABLE` · `NO_REPLY` → `NO_REPLY` · `FAILED` → `FAILED`.
  4. Das Passwort und die Tokens stehen danach in keinem Zustand mehr. Das Zugriffstoken der neuen Sitzung wird nie benutzt. Wird die Seite während des Sendens verlassen, läuft Schritt 3 zu Ende.
- `Done`: „Der Fernseher ist eingerichtet.“ mit „Fertig“ (zurück zu den Einstellungen). `Failed`: ein Satz je Grund, bei `NO_REPLY` der Rat, am Fernseher nachzusehen; Knopf „Noch einmal scannen“ (`again` → `Intro`).
- Der Code des Fernsehers lebt nur im ViewModel und nur bis `Done`, `Failed` oder Verlassen der Seite. Nichts davon wird gespeichert („Das Handy vergisst den Fernseher wieder“).
- `docs/tv-einrichtung-mit-dem-handy.md` (Deutsch, Aufbau wie `docs/server-qr-code.md`): Auf einen Blick · Ablauf für Menschen · Format von Code, Paket und Antwort · was abgelehnt wird · Sicherheitsregeln und bekannte Grenzen (E13 bis E19 der Spec in einfachen Sätzen; größtes Risiko: ein fremder Code, der sich als Fernseher ausgibt) · Technik (Dateien, Tests).
- Datenschutzerklärung und Data-Safety-Text: ein kurzer Absatz, dass bei „Fernseher einrichten“ die Server-Adressen und eine Anmeldung für den Fernseher verschlüsselt und direkt im Heimnetz vom Handy zum Fernseher gehen und an niemanden sonst.
- `docs/tester-anleitung.md`: in Teil B ein Abschnitt „Einrichten mit dem Handy“ mit einer kleinen Prüfliste (Scan aus Sofa-Entfernung, Zahl gleich, falsches Passwort, Fernseher und Handy in getrennten Netzen, abgelaufener Code); die Zeile „kommt in der nächsten Version“ unter den bekannten Grenzen fällt weg, SSO bleibt. `README.md`: eine Zeile bei den Funktionen. Keine Versionsnummer nennen: Die Version legt erst das Release fest.

**Tests:** ViewModel mit Fakes: jeder Scan-Fall ergibt die erwartete Notiz; falsches Passwort bleibt bei `Confirm`, sendet nichts und leert das Feld; Server unerreichbar sendet nichts; Erfolg sendet genau die Adressen des Kontos und das Refresh-Token der neuen Sitzung und endet in `Done`, ohne Abmeldung; jedes andere `SendResult` beendet die Sitzung genau einmal mit genau diesem Token und zeigt den erwarteten Grund; fremder Benutzer in der Antwort → Sitzung beendet, nichts gesendet; `toString()` der Zustände enthält weder Passwort noch Token. · Seite: je Schritt die erwarteten Texte und Knöpfe; die Zahl in Zweiergruppen; Knopf erst mit Passwort aktiv. · Einstellungen: die Zeile ist da und öffnet die Seite. · Manifest: kein Intent-Filter mit Host `tv`.

- [ ] Tests schreiben, fehlschlagen sehen, umsetzen, ganze Suite grün, Lint, `assembleDebug assembleRelease`. Commit.
