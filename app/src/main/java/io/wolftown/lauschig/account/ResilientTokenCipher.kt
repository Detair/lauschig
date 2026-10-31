package io.wolftown.lauschig.account

/**
 * Wraps a [TokenCipher] whose creation or use can fail permanently when the Keystore master key is lost.
 * On such a failure [reset] wipes the key material and a fresh cipher is created once, so a new login
 * can store tokens again. Tokens encrypted with the lost key then decrypt to null (see [TokenCipher.decrypt]),
 * which leads to the "please sign in again" state.
 */
class ResilientTokenCipher(
    private val create: () -> TokenCipher,
    private val reset: () -> Unit,
) : TokenCipher {
    private var delegate: TokenCipher? = null

    @Synchronized
    private fun cipher(): TokenCipher = delegate ?: try {
        create()
    } catch (_: Exception) {
        reset()
        create()
    }.also { delegate = it }

    @Synchronized
    override fun encrypt(plain: String): String = try {
        cipher().encrypt(plain)
    } catch (_: Exception) {
        reset()
        delegate = create()
        delegate!!.encrypt(plain)
    }

    @Synchronized
    override fun decrypt(encrypted: String): String? = try {
        cipher().decrypt(encrypted)
    } catch (_: Exception) {
        null
    }
}
