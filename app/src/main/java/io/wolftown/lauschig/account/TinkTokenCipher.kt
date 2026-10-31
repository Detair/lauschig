package io.wolftown.lauschig.account

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.KeyStore

/** AES-256-GCM via Tink; the keyset is wrapped by an Android Keystore master key. */
class TinkTokenCipher(private val context: Context) : TokenCipher {
    private val aead: Aead = run {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "lauschig_keyset", KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://$MASTER_ALIAS")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override fun encrypt(plain: String): String =
        Base64.encodeToString(aead.encrypt(plain.toByteArray(), ASSOCIATED), Base64.NO_WRAP)

    override fun decrypt(encrypted: String): String? = try {
        String(aead.decrypt(Base64.decode(encrypted, Base64.NO_WRAP), ASSOCIATED))
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val KEYSET_PREFS = "lauschig_keyset_prefs"
        private const val MASTER_ALIAS = "lauschig_master_key"

        /** Deletes keyset prefs and the Keystore master key so a fresh keyset can be generated. */
        fun reset(context: Context) {
            context.deleteSharedPreferences(KEYSET_PREFS)
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MASTER_ALIAS)
            }
        }

        private val ASSOCIATED = "lauschig.tokens".toByteArray()
    }
}
