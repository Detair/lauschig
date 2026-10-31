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
        val c = ResilientTokenCipher(
            create = { if (resets == 0) throw GeneralSecurityException("master key lost") else Plain() },
            reset = { resets++ },
        )
        assertEquals("enc:tok", c.encrypt("tok"))
        assertEquals(1, resets)
        assertEquals("tok", c.decrypt("enc:tok"))
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
        val c = ResilientTokenCipher(create = { throw GeneralSecurityException("lost") }, reset = { resets++ })
        assertThrows(GeneralSecurityException::class.java) { c.encrypt("x") }
        assertThrows(GeneralSecurityException::class.java) { c.encrypt("x") }
        assertEquals(1, resets)
    }
}
