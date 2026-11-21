package io.wolftown.lauschig.tvsetup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** HPKE through Tink: only the TV's key for exactly this code opens what the phone sealed. */
class HandoverCryptoTest {
    private val plain = ByteArray(HandoverFormat.PLAIN_SIZE) { (it * 7).toByte() }
    private val key = HandoverKey.generate()
    private val context = TvSetupCode("192.168.1.50", 41234, key.publicKey).contextInfo()

    @Test fun roundTrip() {
        assertEquals(32, key.publicKey.size)
        val sealed = HandoverCrypto.seal(key.publicKey, plain, context)
        assertArrayEquals(plain, key.open(sealed, context))
    }

    @Test fun ciphertext_hasTheProtocolSize() {
        // 32 bytes encapsulated key + 2048 bytes plaintext + 16 bytes tag; NO_PREFIX adds nothing.
        assertEquals(2096, HandoverCrypto.seal(key.publicKey, plain, context).size)
        assertEquals(HandoverFormat.PACKET_SIZE, 4 + HandoverCrypto.seal(key.publicKey, plain, context).size)
    }

    @Test fun anotherKey_doesNotOpenIt() {
        val sealed = HandoverCrypto.seal(key.publicKey, plain, context)
        assertNull(HandoverKey.generate().open(sealed, context))
    }

    @Test fun anotherContext_doesNotOpenIt() {
        val sealed = HandoverCrypto.seal(key.publicKey, plain, context)
        assertNull(key.open(sealed, TvSetupCode("192.168.1.50", 41235, key.publicKey).contextInfo()))
        assertNull(key.open(sealed, TvSetupCode("192.168.1.51", 41234, key.publicKey).contextInfo()))
    }

    @Test fun aFlippedBit_doesNotOpen() {
        val sealed = HandoverCrypto.seal(key.publicKey, plain, context)
        listOf(0, 31, 32, 1000, sealed.size - 1).forEach { index ->
            val broken = sealed.copyOf()
            broken[index] = (broken[index].toInt() xor 1).toByte()
            assertNull("byte $index", key.open(broken, context))
        }
        assertNull(key.open(sealed.copyOf(sealed.size - 1), context))
        assertNull(key.open(ByteArray(0), context))
    }

    @Test fun sealingTwice_givesDifferentCiphertexts() {
        assertFalse(HandoverCrypto.seal(key.publicKey, plain, context).contentEquals(HandoverCrypto.seal(key.publicKey, plain, context)))
    }

    @Test fun everyKey_isNew() {
        assertFalse(key.publicKey.contentEquals(HandoverKey.generate().publicKey))
    }

    @Test fun publicKey_isACopy_andToStringHoldsNoKey() {
        key.publicKey[0] = (key.publicKey[0] + 1).toByte()
        val sealed = HandoverCrypto.seal(key.publicKey, plain, context)
        assertArrayEquals(plain, key.open(sealed, context))
        assertFalse(key.toString().contains(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key.publicKey)))
    }
}
