package io.wolftown.lauschig.account

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** AES-256-GCM via Tink; the keyset is wrapped by an Android Keystore master key. */
@Singleton
class TinkTokenCipher @Inject constructor(
    @ApplicationContext private val context: Context,
) : TokenCipher {
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "lauschig_keyset", "lauschig_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://lauschig_master_key")
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

    private companion object {
        val ASSOCIATED = "lauschig.tokens".toByteArray()
    }
}
