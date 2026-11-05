package io.wolftown.lauschig.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Realistic lines – OkHttp, ExoPlayer, Java networking and SSL errors, ABS login bodies, our own messages – go through
 * the masker; none of the secrets may survive, and the useful rest stays readable.
 */
class LogMaskerTest {
    private val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VySWQiOiJyb290IiwidXNlcm5hbWUiOiJmYWJpYW4iLCJpYXQiOjE3MzAwMDAwMDB9.dGhpc2lzbm90YXJlYWxzaWduYXR1cmVfX19fXw"

    @After fun tearDown() = LogMasker.setLiterals(emptyList())

    private fun masked(line: String, vararg secrets: String): String {
        val out = LogMasker.mask(line)
        for (s in secrets) assertFalse("'$s' leaked in: $out", out.contains(s, ignoreCase = true))
        return out
    }

    @Test fun authorizationAndCookieHeaders() {
        assertEquals("Authorization: <redacted>", masked("Authorization: Bearer $jwt", jwt, "eyJ"))
        assertEquals("authorization: <redacted>", masked("authorization: Basic ZmFiaWFuOmh1bnRlcjI=", "ZmFiaWFu"))
        masked("Cookie: connect.sid=s%3AxYzAbC123.dEf456; theme=dark", "xYzAbC123", "connect.sid=s")
        masked("Set-Cookie: connect.sid=s%3Aabcdef; Path=/; HttpOnly", "abcdef")
        masked("x-api-key: 0123456789abcdef0123456789abcdef01234567", "0123456789abcdef")
    }

    @Test fun bearerTokensAndJwtsAnywhere() {
        masked("retrying with Bearer $jwt after 401", jwt, "eyJ")
        masked("refresh failed for token $jwt", jwt)
        assertEquals("socket auth <token> rejected", masked("socket auth $jwt rejected", jwt))
    }

    @Test fun okHttpRequestLine_keepsTheEndpointShape_dropsHostIdsAndQuery() {
        val out = masked(
            "--> GET https://abs.wolftown.io/api/items/li_8gch9ve09orgn4fdz8/file/12345?token=$jwt HTTP/1.1",
            "wolftown", "li_8gch9ve09orgn4fdz8", "12345", jwt,
        )
        assertEquals("--> GET https://<server>/api/items/<…>/file/<…>?<…> HTTP/1.1", out)
    }

    @Test fun loginAndTokenBodies() {
        masked("""{"username":"fabian","password":"Hunter2!"}""", "fabian", "Hunter2")
        masked("""{"user":{"id":"root","token":"$jwt","accessToken":"abc.def","refreshToken":"r3fr3sh"}}""", jwt, "abc.def", "r3fr3sh")
        masked("password=geheim&username=lukas", "geheim", "lukas")
        masked("POST /login with pass: 'hunter2'", "hunter2")
        masked("rmab api_key=9f8e7d6c5b4a", "9f8e7d6c5b4a")
    }

    @Test fun javaNetworkingErrors_loseTheirHosts() {
        masked("java.net.UnknownHostException: Unable to resolve host \"abs.wolftown.io\": No address associated with hostname", "wolftown")
        masked("java.net.ConnectException: Failed to connect to /192.168.178.20:13378", "192.168", "13378")
        masked("Failed to connect to nas.fritz.box/192.168.178.20:443", "fritz", "192.168")
        masked("javax.net.ssl.SSLPeerUnverifiedException: Hostname hoerbuch.familie-mueller.de not verified", "mueller", "hoerbuch")
        masked("java.net.UnknownServiceException: CLEARTEXT communication to abs.local not permitted by network security policy", "abs.local")
        masked("timeout connecting to abs.tail1234.ts.net:13378", "tail1234")
    }

    @Test fun ipAddresses_v4AndV6() {
        assertEquals("connected to <ip>", masked("connected to 10.0.2.2:13378", "10.0.2.2"))
        masked("failed to connect to [fd00::1a2b]:13378", "fd00")
        masked("route via 2001:db8::8a2e:370:7334", "2001:db8")
        masked("link-local fe80::1%wlan0", "fe80")
        // Times and versions are no addresses.
        assertEquals("paused at 12:30:45 after 1.11.1", LogMasker.mask("paused at 12:30:45 after 1.11.1"))
    }

    @Test fun ourOwnConnectionLine() {
        assertEquals("connection: Online(baseUrl=http://<server>/, via=LOCAL)", masked("connection: Online(baseUrl=http://10.0.2.2:13378/, via=LOCAL)", "10.0.2.2"))
        masked("moving the streamed queue to https://abs.wolftown.io/", "wolftown")
    }

    @Test fun urlsWithUserInfo_otherSchemes_shareLinks() {
        masked("GET https://fabian:geheim@abs.example.com/api/me", "fabian", "geheim", "example.com")
        assertEquals("open https://<server>/share/<…>", masked("open https://abs.example.com/share/omas-geburtstag", "omas-geburtstag"))
        assertEquals("redirect lauschig://<…>", masked("redirect lauschig://oauth?code=abc123&state=xyz789", "abc123", "xyz789"))
        masked("picked content://com.android.externalstorage.documents/tree/primary%3AH%C3%B6rb%C3%BCcher%2FKinder", "Kinder", "primary")
        assertEquals("socket wss://<server>, retrying", masked("socket wss://abs.example.com, retrying", "example"))
    }

    @Test fun emailAddresses() {
        assertEquals("signed in as <email>", masked("signed in as fabian.mustermann@example.de", "mustermann"))
    }

    @Test fun theUsersOwnValues_areMaskedWhereverTheyAppear() {
        LogMasker.setLiterals(listOf("hoerbuch.example.org", "Lukas", "FRITZ!Box 7590 XY", "ab"))
        masked("lukas started a session on HOERBUCH.example.org", "lukas", "hoerbuch")
        masked("home Wi-Fi FRITZ!Box 7590 XY seen", "FRITZ!Box")
        // Too short to mask without mangling words.
        assertEquals("about", LogMasker.mask("about"))
    }

    @Test fun usefulTextStays() {
        val lines = listOf(
            "playback error ERROR_CODE_IO_BAD_HTTP_STATUS",
            "java.io.IOException: http_404",
            "Size mismatch for file 3: 100 of 200",
            "Unable to start service io.wolftown.lauschig.playback.PlaybackService",
            "/data/user/0/io.wolftown.lauschig/files/downloads/d5f7cac00f5eb3cb/0675bc4c-b74d-45fb-aabf-d566d7086252/1.m4b.part: open failed: ENOSPC (No space left on device)",
            "cover.jpg missing, retried",
            "playWhenReady=true reason=1",
            "queue: next title 0675bc4c-b74d-45fb-aabf-d566d7086252",
            "deleted 2 finished downloads",
            "kotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled",
            "Media3 1.11.1 on SDK 35",
        )
        for (l in lines) assertEquals(l, LogMasker.mask(l))
    }

    @Test fun nothingOfAFullLeakyLineSurvives() {
        LogMasker.setLiterals(listOf("abs.wolftown.io", "fabian"))
        val leaky = "W/OkHttp: <-- 401 https://abs.wolftown.io/api/authorize (12ms) user=fabian email=f@wolftown.io " +
            "Authorization: Bearer $jwt"
        val out = masked(leaky, "wolftown", "fabian", jwt, "eyJ", "f@")
        assertTrue(out, out.startsWith("W/OkHttp: <-- 401 https://<server>/api/authorize (12ms)"))
    }

    // --- the review's adversarial lines (scratchpad maskcheck/MaskCheck.java)

    @Test fun theFullSslMessage_withAWildcardName_losesEveryName() {
        LogMasker.setLiterals(listOf("audiobookshelf.example.io", "example.io"))
        val ssl = "Hostname audiobookshelf.example.io not verified:\n    certificate: sha256/AbC=\n    DN: CN=audiobookshelf.example.io\n" +
            "    subjectAltNames: [audiobookshelf.example.io, *.example.io]"
        masked(ssl, "example.io", "audiobookshelf")
        LogMasker.setLiterals(emptyList())
        // Without the own values: the patterns alone.
        val out = masked("Hostname abs.other-domain.de not verified: subjectAltNames: [*.other-domain.de, other-domain.de]", "other-domain")
        assertTrue(out, out.contains("[*.<server>, <server>]"))
        masked("Hostname 192.168.178.20 not verified: subjectAltNames: [*.example.io]", "192.168", "example.io")
        masked("cookie domain .example.io set", "example.io")
    }

    @Test fun secretKeys_byPart_inAnyQuotes_withSeveralWords() {
        masked("""{"user":{"username":"lukas","newPassword":"hunter2","oldPassword":"pw1","authToken":"plainsecret"}}""", "lukas", "hunter2", "pw1", "plainsecret")
        masked("""{"authOpenIDClientSecret":"s3cr3tvalue","apiToken":"abcd"}""", "s3cr3tvalue", "abcd")
        masked("""{'password': 'hunter2'}""", "hunter2")
        assertEquals("password=<redacted>", masked("password=my secret phrase", "secret phrase", "my"))
        masked("""{"apiToken": 123456789}""", "123456789")
        masked("x-refresh-token: abc123", "abc123")
        masked("set connect.sid=s%3AabcDEF123.xyz;", "abcDEF123")
        masked("refresh_token=abc.def.ghi rt: 8f14e45fceea167a5a36dedd4bea2543c4ca4238a0b923820dcc509a6f75849b", "abc.def", "8f14e45f")
        masked("header Authorization=Bearer abcdefgh123", "abcdefgh123")
        masked("GET /api/items/li_1/play?token=sekret123&x=1", "sekret123")
        // Author names are no secret.
        assertEquals("author: Astrid Lindgren", LogMasker.mask("author: Astrid Lindgren"))
    }

    @Test fun singleWordHosts_inJavasMessages() {
        masked("Unable to resolve host \"nas\": No address associated with hostname", "\"nas\"")
        masked("Unable to resolve host \"Fabians-NAS.local\": No address", "Fabians")
    }

    @Test fun foldersAndTitles_inPaths_keepOnlyTheExtension_ourOwnDirectoryStays() {
        assertEquals(
            "file /storage/emulated/0/<…>.mp3 missing",
            masked("file /storage/emulated/0/Hörbücher/Lukas/Bibi Blocksberg 001.mp3 missing", "Lukas", "Bibi", "Hörbücher"),
        )
        assertEquals(
            "/storage/1A2B-3C4D/<…>.m4b: open failed: ENOENT (No such file or directory)",
            masked("/storage/1A2B-3C4D/Kinder/Die drei ???/001.m4b: open failed: ENOENT (No such file or directory)", "Kinder", "drei"),
        )
        masked("Missing file for primary:Hörbücher/Lukas/Bibi 001.mp3 at /storage/emulated/0/Hörbücher/Lukas/Bibi 001.mp3", "Lukas", "Bibi", "Hörbücher")
        masked("listing /storage/emulated/0/Hörbücher/Lukas failed", "Lukas", "Hörbücher")
        val own = "/storage/A6E5-110D/Android/data/io.wolftown.lauschig/files/downloads/d5f7cac00f5eb3cb/li_x/1.m4b.part: open failed: ENOSPC (No space left on device)"
        assertEquals(own, LogMasker.mask(own))
    }

    @Test fun mappedIpv6_tailscaleAndBracketedAddresses() {
        masked("addr ::ffff:192.168.178.20 and fe80::1%wlan0 and 2001:db8:85a3:0:0:8a2e:370:7334", "192", "168", "fe80", "2001", "8a2e")
        masked("failed to connect to /100.64.12.34 (port 13378) from /100.64.0.7 (port 40000)", "100.64")
        masked("Failed to connect to audiobookshelf.example.io/[fd7a:115c:a1e0::1]:443", "example.io", "fd7a")
        // A MAC address goes too (it names the device); a time stays.
        assertEquals("MAC <ip> time 12:34:56", LogMasker.mask("MAC aa:bb:cc:dd:ee:ff time 12:34:56"))
    }

    @Test fun credentialsWithoutAScheme() {
        assertEquals("<redacted>@<server>/api", masked("user:pass@audiobookshelf.example.io:13378/api", "user:pass", "example.io"))
    }

    @Test fun everyProbeLine_leaksNoneOfItsSecrets() {
        LogMasker.setLiterals(listOf("audiobookshelf.example.io", "example.io", "fabian", "FRITZ!Box 7590 XY"))
        val probes = mapOf(
            "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOiJ4In0.abc123" to listOf("eyJ"),
            "https://audiobookshelf.example.io/api/items/li_1/file/2?token=eyJx" to listOf("example.io", "eyJx", "li_1"),
            "mail lukas.mustermann@gmx.de" to listOf("mustermann", "gmx"),
            "Cookie: connect.sid=s%3Aabc.def" to listOf("abc.def"),
            "token eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOiJ4In0" to listOf("eyJ"),
            "opaque refresh 4f3c2a1b9e8d7c6b5a4f3c2a1b9e8d7c6b5a4f3c2a1b9e8d7c6b5a4f3c2a1b" to listOf("4f3c2a1b"),
            "WLAN FRITZ!Box 7590 XY connected" to listOf("FRITZ"),
            "user fabians tablet" to listOf("fabian"),
            "socket http://10.0.2.2:13378, via=LOCAL" to listOf("10.0.2.2", "13378"),
            "ws://audiobookshelf.example.io/socket.io/?EIO=4&token=x" to listOf("example.io", "EIO", "token=x"),
            "ABS host abs.wolftown.io and audiobookshelf.wolftown.io:443 and netbird abs.netbird.cloud" to listOf("wolftown", "netbird.cloud"),
            "OIDC redirect audiobookshelf://oauth?code=abc&state=xyz" to listOf("code=abc", "xyz"),
            "Basic dXNlcjpwYXNz" to listOf("dXNlcjpwYXNz"),
            "share https://audiobookshelf.example.io/share/my-secret-slug" to listOf("my-secret-slug", "example.io"),
        )
        for ((line, secrets) in probes) masked(line, *secrets.toTypedArray())
    }

    @Test fun aSecretAtTheCut_isMaskedBeforeTheMessageIsCut() {
        DiagnosticLog.clear()
        // Cut first, the value would lose its closing quote and no longer look like a JSON secret ("hunter" would leak).
        DiagnosticLog.record('W', "Cut", "x".repeat(980) + " {\"password\":\"hunter2\"}")
        val e = DiagnosticLog.entries().last()
        assertFalse(e, e.contains("hunter"))
        DiagnosticLog.clear()
    }

    // --- round 2

    @Test fun anOwnParentDomain_takesItsSubdomainsWithIt() {
        LogMasker.setLiterals(listOf("example.io", "fabian"))
        assertEquals("<private> and <private>", masked("abs.example.io and auth.example.io", "abs.", "auth."))
        assertEquals("connect <private>", masked("connect fabian-nas.example.io", "nas", "fabian"))
        assertEquals("[*.<private>]", masked("[*.example.io]", "example"))
        masked("lukas-tablet.netbird.cloud", "lukas").also { LogMasker.setLiterals(listOf("netbird.cloud")); masked("lukas-tablet.netbird.cloud", "lukas", "tablet") }
    }

    @Test fun afterAPathInTheAppsOwnDirectory_anotherPathOnTheLineIsMasked() {
        val own = "/storage/emulated/0/Android/data/io.wolftown.lauschig/files/x.mp3"
        assertEquals(
            "rename $own to /storage/emulated/0/<…>.mp3",
            masked("rename $own to /storage/emulated/0/Hörbücher/Lukas/Bibi 002.mp3", "Lukas", "Bibi", "Hörbücher"),
        )
    }

    @Test fun certificateSubjects_loseOwnerOrganisationAndTown() {
        val dn = "Hostname nas not verified:\n    DN: CN=nas.fritz.box,O=Fabian Hofmann,OU=Keller,L=Berlin,ST=Berlin,C=DE,EMAILADDRESS=f@example.de"
        val out = masked(dn, "Fabian", "Hofmann", "Keller", "Berlin", "f@example", "fritz")
        assertTrue(out, out.contains("O=<redacted>") && out.contains("L=<redacted>") && out.contains("C=<redacted>"))
    }

    @Test fun round2ProbeLines() {
        masked("subjectAltNames: [*.home.arpa, nas.home.arpa]", "home.arpa")
        assertEquals("connect to <ip> failed", masked("connect to [::ffff:c0a8:b214]:13378 failed", "c0a8"))
        assertEquals("<redacted>@<server>", masked("lukas:hunter2@192.168.178.20:13378", "lukas", "hunter2", "192.168"))
        masked("{\"accessToken\":\"x\\\"y z\"}", "y z")
    }

    // --- round 3

    @Test fun emailsUnderAnOwnDomain_areOneAddress() {
        LogMasker.setLiterals(listOf("example.io", "audiobookshelf.example.io"))
        assertEquals("OIDC: user <email>; admin <email>", masked("OIDC: user lukas@example.io; admin mia.mueller@example.io", "lukas", "mia", "example"))
    }

    @Test fun aPathJoinedByACommaToTheAppsOwnPath_isMaskedToo() {
        val own = "/storage/emulated/0/Android/data/io.wolftown.lauschig/files/a.mp3"
        assertEquals("$own,/storage/emulated/0/<…>.mp3", masked("$own,/storage/emulated/0/Hörbücher/Lukas/Bibi.mp3", "Lukas", "Bibi"))
    }

    @Test fun certificateNames_escapedCommas_withoutCn_andOidForm() {
        masked("DN: CN=nas,O=Hofmann\\, Fabian,L=Berlin", "Fabian", "Hofmann", "Berlin")
        masked("DN: O=Fabian Hofmann, L=Berlin", "Fabian", "Hofmann", "Berlin")
        masked("DN: CN=nas, 1.2.840.113549.1.9.1=#16106c756b6173406578616d706c652e696f", "#16106c75")
    }

    // --- round 4 (re-review 3 probe lines)

    @Test fun theAppsOwnPath_endsAtTheFirstCharacterNoIdHas_soAPathJoinedByAnySignIsMasked() {
        val own = "/storage/emulated/0/Android/data/io.wolftown.lauschig/files/a.mp3"
        for (join in listOf(";", "|", ":")) {
            assertEquals("$own$join/storage/emulated/0/<…>.mp3", masked("$own$join/storage/emulated/0/Hörbücher/Lukas/Bibi.mp3", "Lukas", "Bibi", "Hörbücher"))
        }
        assertEquals("[$own|/storage/emulated/0/<…>.mp3]", masked("[$own|/storage/emulated/0/Hörbücher/Lukas/Bibi.mp3]", "Lukas", "Bibi"))
        // The app's own download path (ids, an episode's item~episode key) still stays readable.
        val episode = "/storage/1A2B-3C4D/Android/data/io.wolftown.lauschig/files/downloads/aaaaaaaaaaaaaaaa/li_x~ep_1/1.mp3"
        assertEquals("open $episode failed", masked("open $episode failed"))
    }

    @Test fun certificateNames_quotedValues_moreAttributeTypes_andNamesWithoutCnOOrOu() {
        assertEquals("DN: CN=<redacted>, O=<redacted>, L=<redacted>", masked("DN: CN=nas, O=\"Hofmann, Fabian\", L=Berlin", "Fabian", "Hofmann", "Berlin"))
        assertEquals(
            "DN: CN=<redacted>, GIVENNAME=<redacted>, SURNAME=<redacted>, O=<redacted>",
            masked("DN: CN=nas, GIVENNAME=Fabian, SURNAME=Hofmann, O=x", "Fabian", "Hofmann"),
        )
        assertEquals("DN: CN=<redacted>, OID.2.5.4.42=<redacted>, O=<redacted>", masked("DN: CN=nas, OID.2.5.4.42=Fabian, O=x", "Fabian"))
        assertEquals("DN: L=<redacted>, ST=<redacted>, C=<redacted>", masked("DN: L=Berlin, ST=Berlin, C=DE", "Berlin"))
        masked("subject: L=Berlin, ST=Bayern, C=DE", "Berlin", "Bayern")
        masked("DN: SN=Hofmann, GN=Fabian, T=Dr, INITIALS=FH, PSEUDONYM=fabi", "Hofmann", "Fabian", "fabi")
        // A quote that is never closed goes to the end of the line.
        masked("DN: CN=nas, O=\"Hofmann, Fabian", "Fabian", "Hofmann")
    }

    @Test fun round3ProbeLines_leakNothing() {
        for (own in listOf(emptyList(), listOf("audiobookshelf.example.io", "example.io", "netbird.cloud", "fabian"))) {
            LogMasker.setLiterals(own)
            masked("OIDC: user lukas@example.io not allowed; admin mia.mueller@example.io", "lukas", "mia", "example")
            masked("mail from lukas@abs.example.io and fabian@audiobookshelf.example.io", "lukas", "fabian", "example")
            masked("user fabian@gmx.de signed in", "fabian@", "gmx")
            masked("/storage/emulated/0/Android/data/io.wolftown.lauschig/files/a.mp3,/sdcard/Kinder/Mia/x.mp3,/storage/1A2B-3C4D/Lukas/y.mp3", "Kinder", "Mia", "Lukas")
            masked("DN: CN=nas,O=Hofmann\\, Fabian,L=Berlin", "Hofmann", "Fabian", "Berlin")
            masked("DN: OU=Familie Hofmann, L=Berlin", "Familie", "Hofmann", "Berlin")
            masked("1.2.840.113549.1.9.1=#161466616269616e406578616d706c652e696f,CN=nas", "#1614", "nas")
            masked("DN: CN=nas+UID=lukas, O=Hofmann", "lukas", "Hofmann")
            masked("DN: CN=nas; O=Hofmann; L=Berlin", "Hofmann", "Berlin")
            masked("DN: CN=nas, 2.5.4.42=Fabian, 2.5.4.4=Hofmann", "Fabian", "Hofmann")
            masked("DN: CN=nas,2.5.4.10=#0c07486f666d616e6e", "#0c07")
            masked("issuer: CN=Lukas Root CA, O=Lukas; subject: CN=nas", "Lukas")
            masked(
                "Hostname audiobookshelf.example.io not verified:\n    certificate: sha256/AbC=\n    DN: CN=audiobookshelf.example.io,O=Fabian Hofmann,L=Berlin,EMAILADDRESS=fabian@example.io\n    subjectAltNames: [audiobookshelf.example.io, *.example.io]",
                "audiobookshelf", "example", "Fabian", "Hofmann", "Berlin",
            )
            masked("lukas-tablet.netbird.cloud", "lukas", "netbird")
            masked("li_abc@audiobookshelf.example.io", "li_abc", "audiobookshelf")
        }
    }
}
