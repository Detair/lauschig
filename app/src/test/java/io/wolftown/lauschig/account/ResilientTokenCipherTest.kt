package io.wolftown.lauschig.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.ProviderException

class ResilientTokenCipherTest {
    private class Plain : TokenCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encrypted: String) = encrypted.removePrefix("enc:")
    }

    @Test
    fun cipherKeyLoss_regeneratesAndAllowsNewSave() {
        var resets = 0
        val pauses = mutableListOf<Long>()
        val c = ResilientTokenCipher(
            create = { if (resets == 0) throw GeneralSecurityException("master key lost") else Plain() },
            reset = { resets++ },
            pause = { pauses += it },
        )
        assertEquals("enc:tok", c.encrypt("tok"))
        assertEquals(1, resets)
        assertEquals("tok", c.decrypt("enc:tok"))
        // Given up only after every try: the first one and one after each pause.
        assertEquals(listOf(150L, 400L, 1_000L), pauses)
    }

    /**
     * The Keystore of some devices fails for a moment (busy, not ready yet) with the same exceptions as a lost key. One
     * such failure must not wipe the key material: every profile would have to sign in again, PIN entries would be lost.
     */
    @Test
    fun keystoreFailingForAMoment_isTriedAgain_andNothingIsWiped() {
        var resets = 0
        var creates = 0
        val pauses = mutableListOf<Long>()
        val c = ResilientTokenCipher(
            create = { if (++creates <= 2) throw GeneralSecurityException("keystore busy") else Plain() },
            reset = { resets++ },
            pause = { pauses += it },
        )
        assertEquals("enc:tok", c.encrypt("tok"))
        assertEquals(0, resets)
        assertEquals(listOf(150L, 400L), pauses)
        assertEquals(3, creates)
    }

    @Test
    fun transientProviderError_doesNotReset() {
        var resets = 0
        val c = ResilientTokenCipher(create = { throw ProviderException("busy") }, reset = { resets++ })
        assertThrows(ProviderException::class.java) { c.encrypt("x") }
        assertEquals(0, resets)
    }

    @Test
    fun encryptFailure_doesNotReset() {
        var resets = 0
        val c = ResilientTokenCipher(
            create = { object : TokenCipher {
                override fun encrypt(plain: String): String = throw GeneralSecurityException("boom")
                override fun decrypt(encrypted: String) = null
            } },
            reset = { resets++ },
        )
        assertThrows(GeneralSecurityException::class.java) { c.encrypt("x") }
        assertThrows(GeneralSecurityException::class.java) { c.encrypt("x") }
        assertEquals(0, resets)
    }

    @Test
    fun keyLoss_resetsAtMostOncePerProcess() {
        var resets = 0
        val c = ResilientTokenCipher(create = { throw GeneralSecurityException("lost") }, reset = { resets++ }, pause = {})
        assertThrows(GeneralSecurityException::class.java) { c.encrypt("x") }
        assertThrows(GeneralSecurityException::class.java) { c.encrypt("x") }
        assertEquals(1, resets)
    }
}
