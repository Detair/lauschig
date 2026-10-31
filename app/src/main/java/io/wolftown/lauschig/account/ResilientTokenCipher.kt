package io.wolftown.lauschig.account

import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Wraps a [TokenCipher] whose creation fails permanently when the Keystore master key or the keyset is lost.
 *
 * Only a failure of [create] with a [GeneralSecurityException] or [IOException] (includes
 * `KeyPermanentlyInvalidatedException` and Tink's `InvalidProtocolBufferException`) is treated as permanent key
 * loss: [reset] wipes the key material and [create] is retried once. This happens at most once per instance
 * (the app holds a singleton, i.e. once per process). Any other exception (e.g. a transient provider error), a
 * second failure, and failures of encrypt/decrypt on an already built cipher just propagate and never reset.
 * Tokens encrypted with the lost key then decrypt to null, which leads to the "please sign in again" state.
 */
class ResilientTokenCipher(
    private val create: () -> TokenCipher,
    private val reset: () -> Unit,
) : TokenCipher {
    private var delegate: TokenCipher? = null
    private var resetDone = false

    @Synchronized
    private fun cipher(): TokenCipher = delegate ?: try {
        create()
    } catch (e: Exception) {
        if (resetDone || !(e is GeneralSecurityException || e is IOException)) throw e
        resetDone = true
        reset()
        create()
    }.also { delegate = it }

    override fun encrypt(plain: String): String = cipher().encrypt(plain)

    override fun decrypt(encrypted: String): String? = cipher().decrypt(encrypted)
}
