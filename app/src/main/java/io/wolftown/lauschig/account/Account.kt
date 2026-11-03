package io.wolftown.lauschig.account

import io.wolftown.lauschig.connection.ServerAddresses
import okhttp3.HttpUrl
import java.security.MessageDigest

/**
 * The signed-in user on one server.
 *
 * @property serverId stable id used as part of every Room primary key; see [computeServerId].
 * @property allowCleartext addresses (normalized URL strings) for which the user explicitly confirmed plain HTTP.
 */
data class Account(
    val serverId: String,
    val userId: String,
    val username: String,
    val isAdmin: Boolean,
    val addresses: ServerAddresses,
    val allowCleartext: Set<String>,
) {
    companion object {
        /**
         * `serverId` = first 16 hex chars of SHA-256 over `"<primary address>|<userId>"`, where the
         * primary address is the public address if set, otherwise the local one, normalized by OkHttp
         * (lowercase scheme/host) with trailing slashes removed. Stable across restarts and token refreshes.
         */
        fun computeServerId(addresses: ServerAddresses, userId: String): String {
            val primary = (addresses.public ?: addresses.local)
                ?: throw IllegalArgumentException("At least one server address is required")
            val normalized = primary.toString().trimEnd('/')
            val digest = MessageDigest.getInstance("SHA-256").digest("$normalized|$userId".toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}

/**
 * The server as the user knows it (settings, sign-out questions): the public address's host, else the local one's,
 * with the port only when it is not the scheme's default. Empty without any address.
 */
val Account.displayHost: String
    get() {
        val url = addresses.public ?: addresses.local ?: return ""
        return if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"
    }

/**
 * The form in which a cleartext (http://) server is stored in [Account.allowCleartext]:
 * `scheme://host:port` (no path), so every URL on a confirmed server matches.
 */
fun HttpUrl.cleartextKey(): String = "$scheme://$host:$port"
