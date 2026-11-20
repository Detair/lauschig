package io.wolftown.lauschig.tvsetup

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.util.Bytes
import java.security.GeneralSecurityException

/**
 * HPKE base mode, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305, without Tink's key-id prefix: the
 * ciphertext is the plain RFC 9180 encapsulated key and sealed block, so its size is fixed and nothing else rides on it.
 */
private val PARAMETERS: HpkeParameters by lazy {
    HybridConfig.register()
    HpkeParameters.builder()
        .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
        .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
        .setAeadId(HpkeParameters.AeadId.CHACHA20_POLY1305)
        .setVariant(HpkeParameters.Variant.NO_PREFIX)
        .build()
}

/**
 * The TV's key for one code. It lives only in this object: no keyset manager, no preferences, no file, so dropping the
 * reference is all it takes to retire a code.
 */
class HandoverKey private constructor(private val handle: KeysetHandle) {
    private val decrypt: HybridDecrypt = handle.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)

    private val publicBytes: ByteArray =
        (handle.primary.key as HpkePrivateKey).publicKey.publicKeyBytes.toByteArray()

    /** 32 bytes; a copy on every call. */
    val publicKey: ByteArray get() = publicBytes.copyOf()

    /** The plaintext, or null when it was not sealed for this key and [contextInfo]. */
    fun open(ciphertext: ByteArray, contextInfo: ByteArray): ByteArray? = try {
        decrypt.decrypt(ciphertext, contextInfo)
    } catch (_: GeneralSecurityException) {
        null
    }

    override fun toString(): String = "HandoverKey(…)"

    companion object {
        fun generate(): HandoverKey = HandoverKey(KeysetHandle.generateNew(PARAMETERS))
    }
}

object HandoverCrypto {
    /** Seals [plain] for the TV's [publicKey]; throws [GeneralSecurityException] for a key Tink does not take. */
    fun seal(publicKey: ByteArray, plain: ByteArray, contextInfo: ByteArray): ByteArray {
        // NO_PREFIX keys carry no id; the keyset still needs one, and a random one is never written anywhere.
        val key = HpkePublicKey.create(PARAMETERS, Bytes.copyFrom(publicKey), null)
        val handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary()).build()
        return handle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java).encrypt(plain, contextInfo)
    }
}
