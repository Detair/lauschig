package io.wolftown.lauschig.account

import io.wolftown.lauschig.connection.ServerAddresses
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
