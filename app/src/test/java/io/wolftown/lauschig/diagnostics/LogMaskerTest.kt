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
}
