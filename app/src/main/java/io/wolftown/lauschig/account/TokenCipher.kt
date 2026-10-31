package io.wolftown.lauschig.account

/** Encrypts/decrypts token strings. The result of [encrypt] is a printable (base64) string. */
interface TokenCipher {
    fun encrypt(plain: String): String

    /** Returns null if [encrypted] cannot be decrypted (e.g. the keystore key was lost). */
    fun decrypt(encrypted: String): String?
}
