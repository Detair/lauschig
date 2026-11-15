package io.wolftown.lauschig.serverqr

import io.wolftown.lauschig.connection.ServerAddresses
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The server QR code's text: what the reader takes, what it refuses, and what the writer puts in. See docs/server-qr-code.md. */
class ServerQrTest {
    private val local = "http%3A%2F%2F192.168.1.20%3A13378"
    private val public = "https%3A%2F%2Fabs.example.org%2Faudiobookshelf"

    private fun addresses(local: String = "", public: String = "") = ServerQrResult.Addresses(local, public)
    private fun rejected(reason: ServerQrError) = ServerQrResult.Rejected(reason)

    // --- reader

    @Test fun twoAddresses() {
        assertEquals(
            addresses("http://192.168.1.20:13378", "https://abs.example.org/audiobookshelf"),
            ServerQr.read("lauschig://server?v=1&local=$local&public=$public"),
        )
    }

    @Test fun oneAddress_isEnough() {
        assertEquals(addresses(public = "https://abs.example.org/audiobookshelf"), ServerQr.read("lauschig://server?v=1&public=$public"))
        assertEquals(addresses(local = "http://192.168.1.20:13378"), ServerQr.read("lauschig://server?v=1&local=$local"))
        assertEquals("an empty value is no address", addresses(public = "https://abs.example.org/audiobookshelf"), ServerQr.read("lauschig://server?v=1&local=&public=$public"))
    }

    @Test fun pathAndPort_stay() {
        assertEquals(
            addresses(public = "https://abs.example.org:8443/books/audiobookshelf"),
            ServerQr.read("lauschig://server?v=1&public=https%3A%2F%2Fabs.example.org%3A8443%2Fbooks%2Faudiobookshelf"),
        )
    }

    @Test fun theOrderOfTheKeys_doesNotMatter_andSchemeAndHostIgnoreCase() {
        assertEquals(
            addresses("http://192.168.1.20:13378", "https://abs.example.org/audiobookshelf"),
            ServerQr.read("LAUSCHIG://Server/?public=$public&local=$local&v=1"),
        )
    }

    @Test fun addressesWrittenByHand_withoutPercentEncoding_areRead() {
        assertEquals(
            addresses("http://192.168.1.20:13378", "https://abs.example.org/audiobookshelf"),
            ServerQr.read("lauschig://server?v=1&local=http://192.168.1.20:13378&public=https://abs.example.org/audiobookshelf"),
        )
    }

    @Test fun anAddress_comesOutAsTheFormWritesIt() {
        assertEquals(addresses(public = "https://abs.example.org"), ServerQr.read("lauschig://server?v=1&public=https%3A%2F%2FABS.Example.org%2F"))
    }

    @Test fun otherKeys_areDropped() {
        val result = ServerQr.read("lauschig://server?v=1&user=fabi&token=abc123&local=$local&ssid=Heimnetz&public=$public&password=geheim#fragment")
        assertEquals(addresses("http://192.168.1.20:13378", "https://abs.example.org/audiobookshelf"), result)
        listOf("fabi", "abc123", "Heimnetz", "geheim", "fragment").forEach { assertFalse(it, result.toString().contains(it)) }
    }

    @Test fun somethingElse_isNotAServerCode() {
        listOf(
            "", " ", "hello", "https://abs.example.org/audiobookshelf", "WIFI:S:Heimnetz;T:WPA;P:geheim;;", "lauschig://oauth?code=abc&state=def",
            "lauschig:server?v=1&public=$public", "lauschig://server.example?v=1&public=$public", "lauschig://server/setup?v=1&public=$public",
            "lauschig://user@server?v=1&public=$public", "lauschig://server:80?v=1&public=$public", "xlauschig://server?v=1&public=$public",
        ).forEach { assertEquals(it, rejected(ServerQrError.NOT_A_SERVER_CODE), ServerQr.read(it)) }
    }

    @Test fun aKeyTwice_isNotAServerCode() {
        assertEquals(rejected(ServerQrError.NOT_A_SERVER_CODE), ServerQr.read("lauschig://server?v=1&public=$public&public=https%3A%2F%2Fevil.example"))
        assertEquals(rejected(ServerQrError.NOT_A_SERVER_CODE), ServerQr.read("lauschig://server?v=1&v=1&public=$public"))
    }

    @Test fun anotherVersion_isRefused() {
        assertEquals(rejected(ServerQrError.UNKNOWN_VERSION), ServerQr.read("lauschig://server?v=2&local=$local&public=$public"))
        assertEquals(rejected(ServerQrError.UNKNOWN_VERSION), ServerQr.read("lauschig://server?local=$local&public=$public"))
        assertEquals(rejected(ServerQrError.UNKNOWN_VERSION), ServerQr.read("lauschig://server?v=01&public=$public"))
    }

    @Test fun noAddress_isRefused() {
        assertEquals(rejected(ServerQrError.NO_ADDRESS), ServerQr.read("lauschig://server?v=1"))
        assertEquals(rejected(ServerQrError.NO_ADDRESS), ServerQr.read("lauschig://server?v=1&local=&public="))
        assertEquals(rejected(ServerQrError.NOT_A_SERVER_CODE), ServerQr.read("lauschig://server"))
    }

    @Test fun onlyHttpAndHttps() {
        listOf("ftp%3A%2F%2Fabs.example.org", "file%3A%2F%2F%2Fsdcard%2Fx", "javascript%3Aalert(1)", "abs.example.org", "content%3A%2F%2Fabs.example.org", "intent%3A%2F%2Fabs.example.org%23Intent%3Bend").forEach {
            assertEquals(it, rejected(ServerQrError.BAD_ADDRESS), ServerQr.read("lauschig://server?v=1&public=$it"))
        }
    }

    @Test fun credentialsInAnAddress_areRefused() {
        assertEquals(rejected(ServerQrError.CREDENTIALS), ServerQr.read("lauschig://server?v=1&public=https%3A%2F%2Ffabi%3Ageheim%40abs.example.org"))
        assertEquals(rejected(ServerQrError.CREDENTIALS), ServerQr.read("lauschig://server?v=1&local=http%3A%2F%2Ffabi%40192.168.1.20%3A13378"))
        assertEquals("hand-written too", rejected(ServerQrError.CREDENTIALS), ServerQr.read("lauschig://server?v=1&public=https://fabi:geheim@abs.example.org"))
    }

    @Test fun oneBadAddress_refusesTheWholeCode() {
        assertEquals(rejected(ServerQrError.BAD_ADDRESS), ServerQr.read("lauschig://server?v=1&local=$local&public=ftp%3A%2F%2Fabs.example.org"))
        assertEquals(rejected(ServerQrError.CREDENTIALS), ServerQr.read("lauschig://server?v=1&local=$local&public=https%3A%2F%2Fa%3Ab%40abs.example.org"))
    }

    @Test fun anAddressWithMoreThanAnAddress_isRefused() {
        listOf(
            "https%3A%2F%2Fabs.example.org%2F%3Ftoken%3Dabc", // ?token=abc
            "https%3A%2F%2Fabs.example.org%2F%23frag", // #frag
            "https%3A%2F%2Fabs.example.org%0A.evil.example", // a line break
            "https%3A%2F%2Fabs.example.org%20%2Fx", // a blank
            "https%3A%2F%2Fabs.example.org%2F%ZZ", // broken percent-encoding
            "https%3A%2F%2F", // no host
        ).forEach { assertEquals(it, rejected(ServerQrError.BAD_ADDRESS), ServerQr.read("lauschig://server?v=1&public=$it")) }
    }

    @Test fun tooLong_isRefused_whateverItIs() {
        val filler = "a".repeat(ServerQr.MAX_LENGTH)
        assertEquals(rejected(ServerQrError.TOO_LONG), ServerQr.read("lauschig://server?v=1&public=$public&x=$filler"))
        assertEquals(rejected(ServerQrError.TOO_LONG), ServerQr.read(filler + "b"))
        val longest = "lauschig://server?v=1&public=$public&x=".let { it + "a".repeat(ServerQr.MAX_LENGTH - it.length) }
        assertEquals(ServerQr.MAX_LENGTH, longest.length)
        assertEquals(addresses(public = "https://abs.example.org/audiobookshelf"), ServerQr.read(longest))
    }

    // --- writer

    @Test fun text_isExactlyTheTwoAddresses() {
        val text = ServerQr.text(ServerAddresses("http://192.168.1.20:13378/".toHttpUrl(), "https://abs.example.org/audiobookshelf/".toHttpUrl()))
        assertEquals("lauschig://server?v=1&local=$local&public=$public", text)
    }

    @Test fun text_namesNoWifi_whateverTheAccountHas() {
        val text = ServerQr.text(ServerAddresses("http://192.168.1.20:13378/".toHttpUrl(), "https://abs.example.org/".toHttpUrl(), homeSsids = setOf("Heimnetz", "Gartenhaus")))!!
        assertEquals("lauschig://server?v=1&local=$local&public=https%3A%2F%2Fabs.example.org", text)
        assertFalse(text.contains("Heimnetz") || text.contains("Gartenhaus"))
    }

    @Test fun text_oneAddress_orNone() {
        assertEquals("lauschig://server?v=1&public=$public", ServerQr.text(ServerAddresses(null, "https://abs.example.org/audiobookshelf".toHttpUrl())))
        assertEquals("lauschig://server?v=1&local=$local", ServerQr.text(ServerAddresses("http://192.168.1.20:13378".toHttpUrl(), null)))
        assertNull(ServerQr.text(ServerAddresses(null, null)))
    }

    @Test fun text_neverCarriesCredentialsQueryOrFragment_ofAStoredAddress() {
        val text = ServerQr.text(ServerAddresses(null, "https://fabi:geheim@abs.example.org/audiobookshelf/?token=abc#frag".toHttpUrl()))!!
        assertEquals("lauschig://server?v=1&public=$public", text)
    }

    @Test fun whatTheWriterWrites_theReaderReads_asTheFormShowsIt() {
        val a = ServerAddresses("http://192.168.1.20:13378/".toHttpUrl(), "https://abs.example.org:8443/books/audiobookshelf/".toHttpUrl())
        assertEquals(
            addresses(a.local.toString().trimEnd('/'), a.public.toString().trimEnd('/')),
            ServerQr.read(ServerQr.text(a)!!),
        )
    }

    @Test fun thePage_showsTheCodesText_andItsAddressesAsPlainText() {
        val a = ServerAddresses("http://192.168.1.20:13378/".toHttpUrl(), "https://fabi:geheim@abs.example.org/audiobookshelf/?x=1".toHttpUrl(), setOf("Heimnetz"))
        assertEquals(
            ServerQrCode("lauschig://server?v=1&local=$local&public=$public", listOf("http://192.168.1.20:13378", "https://abs.example.org/audiobookshelf")),
            ServerQr.code(a),
        )
        assertNull(ServerQr.code(ServerAddresses(null, null, setOf("Heimnetz"))))
    }
}
