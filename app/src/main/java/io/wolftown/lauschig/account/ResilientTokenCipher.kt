package io.wolftown.lauschig.account

import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Wraps a [TokenCipher] whose creation fails permanently when the Keystore master key or the keyset is lost.
 *
 * A failure of [create] with a [GeneralSecurityException] or [IOException] (includes
 * `KeyPermanentlyInvalidatedException` and Tink's `InvalidProtocolBufferException`) may be that loss. The Keystore of some
 * devices fails the same way for a moment (busy, not ready yet), so [create] is first tried again after each of
 * [retryPausesMs]. Only when every try failed is it treated as permanent key loss: [reset] wipes the key material and
 * [create] runs once more. This happens at most once per instance (the app holds a singleton, i.e. once per process).
 * Any other exception (e.g. a provider error), a failure after the reset, and failures of encrypt/decrypt on an already
 * built cipher just propagate and never reset.
 * Tokens encrypted with the lost key then decrypt to null, which leads to the "please sign in again" state.
 */
class ResilientTokenCipher(
    private val create: () -> TokenCipher,
    private val reset: () -> Unit,
    /** The waits before each further try; together the longest a first use blocks before the reset. */
    private val retryPausesMs: List<Long> = listOf(150, 400, 1_000),
    private val pause: (Long) -> Unit = Thread::sleep,
) : TokenCipher {
    private var delegate: TokenCipher? = null
    private var resetDone = false

    @Synchronized
    private fun cipher(): TokenCipher = delegate ?: try {
        create()
    } catch (e: Exception) {
        if (resetDone || !e.mayBeKeyLoss()) throw e
        triedAgain() ?: run {
            resetDone = true
            reset()
            create()
        }
    }.also { delegate = it }

    /** The cipher, if one of the further tries built it; null if all of them failed like a lost key. */
    private fun triedAgain(): TokenCipher? {
        for (wait in retryPausesMs) {
            pause(wait)
            try {
                return create()
            } catch (e: Exception) {
                if (!e.mayBeKeyLoss()) throw e
            }
        }
        return null
    }

    private fun Exception.mayBeKeyLoss() = this is GeneralSecurityException || this is IOException

    override fun encrypt(plain: String): String = cipher().encrypt(plain)

    override fun decrypt(encrypted: String): String? = cipher().decrypt(encrypted)
}
