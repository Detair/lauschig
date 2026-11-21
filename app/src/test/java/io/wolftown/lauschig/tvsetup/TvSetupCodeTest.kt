package io.wolftown.lauschig.tvsetup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TV's code: what the phone's scanner takes, what it refuses, and the number both screens show. */
class TvSetupCodeTest {
    private val key = ByteArray(32) { it.toByte() }
    private val keyText = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val text = "lauschig://tv?v=1&a=192.168.1.50&p=41234&k=$keyText"

    private fun rejected(reason: TvSetupCodeError) = TvSetupCodeResult.Rejected(reason)

    @Test fun text_isTheProtocolLine() {
        assertEquals(text, TvSetupCode("192.168.1.50", 41234, key).text)
    }

    @Test fun roundTrip() {
        val code = TvSetupCode("192.168.1.50", 41234, key)
        assertEquals(TvSetupCodeResult.Code(code), TvSetupCodes.read(code.text))
        val read = (TvSetupCodes.read(code.text) as TvSetupCodeResult.Code).code
        assertEquals("192.168.1.50", read.address)
        assertEquals(41234, read.port)
        assertArrayEquals(key, read.publicKey)
    }

    @Test fun roundTrip_ofTheOtherPrivateNetworks() {
        listOf("10.0.0.7", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.0.1").forEach { address ->
            val code = TvSetupCode(address, 1024, key)
            assertEquals(address, TvSetupCodeResult.Code(code), TvSetupCodes.read(code.text))
        }
    }

    /*
     * Worked out by hand:
     *   printf 'lauschig-tv-number-v1\n192.168.1.50\n41234\nAAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8' | sha256sum
     *   -> c4fd4694...; 0xc4fd4694 = 3304933012; 3304933012 mod 10000 = 3012
     * The same with a=192.168.1.51 -> ef043b46... = 4010031942 -> 1942,
     * with p=41235 -> f1d58c10... = 4057304080 -> 4080,
     * with k = bytes 1..32 (AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA) -> 4b1e37fe... = 1260271614 -> 1614.
     */
    @Test fun number_isFixedByTheProtocol() {
        assertEquals("3012", TvSetupCode("192.168.1.50", 41234, key).number)
        assertEquals("1942", TvSetupCode("192.168.1.51", 41234, key).number)
        assertEquals("4080", TvSetupCode("192.168.1.50", 41235, key).number)
        assertEquals("1614", TvSetupCode("192.168.1.50", 41234, ByteArray(32) { (it + 1).toByte() }).number)
    }

    @Test fun number_hasFourDigits() {
        repeat(200) { port ->
            val number = TvSetupCode("192.168.1.50", 2000 + port, key).number
            assertTrue(number, number.length == 4 && number.all { it in '0'..'9' })
        }
    }

    @Test fun contextInfo_bindsAddressAndPort() {
        assertArrayEquals(
            "lauschig-tv-setup-v1\n192.168.1.50\n41234".toByteArray(Charsets.UTF_8),
            TvSetupCode("192.168.1.50", 41234, key).contextInfo(),
        )
    }

    @Test fun publicKey_isACopy() {
        val outside = key.copyOf()
        val code = TvSetupCode("192.168.1.50", 41234, outside)
        outside[0] = 99
        code.publicKey[1] = 99
        assertArrayEquals(key, code.publicKey)
    }

    @Test fun equality_isByValue_andToStringHoldsNoKey() {
        val code = TvSetupCode("192.168.1.50", 41234, key)
        assertEquals(code, TvSetupCode("192.168.1.50", 41234, key.copyOf()))
        assertEquals(code.hashCode(), TvSetupCode("192.168.1.50", 41234, key.copyOf()).hashCode())
        assertNotEquals(code, TvSetupCode("192.168.1.50", 41234, ByteArray(32)))
        assertNotEquals(code, TvSetupCode("192.168.1.50", 41235, key))
        listOf(keyText, "192.168.1.50", "41234", "lauschig://").forEach { assertFalse(it, code.toString().contains(it)) }
    }

    @Test fun blanksAround_doNotCount_andUnknownPartsAreDropped() {
        val code = TvSetupCodeResult.Code(TvSetupCode("192.168.1.50", 41234, key))
        assertEquals(code, TvSetupCodes.read("  $text\n"))
        assertEquals(code, TvSetupCodes.read("lauschig://tv?x=1&k=$keyText&p=41234&a=192.168.1.50&v=1&name=Wohnzimmer"))
    }

    @Test fun somethingElse_isNotATvCode() {
        listOf(
            "", " ", "hello", "https://abs.example.org", "lauschig://server?v=1&public=https%3A%2F%2Fabs.example.org",
            "lauschig://tv", "lauschig://tvx?v=1&a=192.168.1.50&p=41234&k=$keyText", "lauschig:tv?v=1&a=192.168.1.50&p=41234&k=$keyText",
        ).forEach { assertEquals(it, rejected(TvSetupCodeError.NOT_A_TV_CODE), TvSetupCodes.read(it)) }
    }

    @Test fun anotherVersion_isRefused() {
        listOf("v=2&", "", "v=01&", "v=&").forEach {
            assertEquals(it, rejected(TvSetupCodeError.UNKNOWN_VERSION), TvSetupCodes.read("lauschig://tv?${it}a=192.168.1.50&p=41234&k=$keyText"))
        }
    }

    @Test fun anAddressOutsideTheHomeNetwork_isRefused() {
        listOf("8.8.8.8", "172.32.0.1", "172.15.255.255", "192.169.0.1", "127.0.0.1", "0.0.0.0", "255.255.255.255", "169.254.1.1").forEach {
            assertEquals(it, rejected(TvSetupCodeError.NOT_HOME_NETWORK), TvSetupCodes.read("lauschig://tv?v=1&a=$it&p=41234&k=$keyText"))
        }
    }

    @Test fun aBadAddress_isABadCode() {
        listOf(
            "", "tv.example.org", "localhost", "192.168.1", "192.168.1.50.1", "192.168.001.50", "192.168.01.50", "010.0.0.1",
            "192.168.1.256", "192.168.1.-1", "fe80::1", "[fd00::1]", "::ffff:192.168.1.50", "192.168.1.50%20", "192.168.1.%35%30", " 192.168.1.50",
            "+192.168.1.50", "0x0a.0.0.1",
        ).forEach {
            assertEquals(it, rejected(TvSetupCodeError.BAD_CODE), TvSetupCodes.read("lauschig://tv?v=1&a=$it&p=41234&k=$keyText"))
        }
    }

    @Test fun aBadPort_isABadCode() {
        listOf("", "80", "1023", "65536", "041234", "-1", "+41234", "4123x", "99999999999").forEach {
            assertEquals(it, rejected(TvSetupCodeError.BAD_CODE), TvSetupCodes.read("lauschig://tv?v=1&a=192.168.1.50&p=$it&k=$keyText"))
        }
        assertEquals(TvSetupCodeResult.Code(TvSetupCode("192.168.1.50", 65535, key)), TvSetupCodes.read("lauschig://tv?v=1&a=192.168.1.50&p=65535&k=$keyText"))
    }

    @Test fun aBadKey_isABadCode() {
        val short = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(31))
        val long = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(33))
        val standardAlphabet = java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { -1 })
        listOf(
            "", short, long, "$keyText=", standardAlphabet, keyText.dropLast(1) + "h", keyText.dropLast(1) + "!",
        ).forEach {
            assertEquals(it, rejected(TvSetupCodeError.BAD_CODE), TvSetupCodes.read("lauschig://tv?v=1&a=192.168.1.50&p=41234&k=$it"))
        }
    }

    @Test fun aPartTwice_isABadCode() {
        listOf(
            "v=1&v=1&a=192.168.1.50&p=41234&k=$keyText",
            "v=1&a=192.168.1.50&a=192.168.1.50&p=41234&k=$keyText",
            "v=1&a=192.168.1.50&p=41234&p=41235&k=$keyText",
            "v=1&a=192.168.1.50&p=41234&k=$keyText&k=$keyText",
        ).forEach { assertEquals(it, rejected(TvSetupCodeError.BAD_CODE), TvSetupCodes.read("lauschig://tv?$it")) }
    }

    @Test fun aMissingPart_isABadCode() {
        listOf("v=1&p=41234&k=$keyText", "v=1&a=192.168.1.50&k=$keyText", "v=1&a=192.168.1.50&p=41234").forEach {
            assertEquals(it, rejected(TvSetupCodeError.BAD_CODE), TvSetupCodes.read("lauschig://tv?$it"))
        }
    }

    @Test fun tooLong_isRefusedUnread() {
        assertEquals(TvSetupCodeResult.Code(TvSetupCode("192.168.1.50", 41234, key)), TvSetupCodes.read(text.padEnd(TvSetupCodes.MAX_LENGTH, ' ')))
        assertEquals(rejected(TvSetupCodeError.TOO_LONG), TvSetupCodes.read(text.padEnd(TvSetupCodes.MAX_LENGTH + 1, ' ')))
        assertEquals(rejected(TvSetupCodeError.TOO_LONG), TvSetupCodes.read("$text&x=" + "y".repeat(300)))
    }

    @Test fun homeAddress_takesOnlyLiteralPrivateIpv4() {
        assertEquals(listOf<Byte>(192.toByte(), 168.toByte(), 1, 50), TvSetupCodes.homeAddress("192.168.1.50")!!.address.toList())
        assertEquals("10.0.0.7", TvSetupCodes.homeAddress("10.0.0.7")!!.hostAddress)
        listOf("8.8.8.8", "172.32.0.1", "192.169.0.1", "localhost", "tv.example.org", "fd00::1", "192.168.01.50", "127.0.0.1", "").forEach {
            assertNull(it, TvSetupCodes.homeAddress(it))
        }
    }
}
