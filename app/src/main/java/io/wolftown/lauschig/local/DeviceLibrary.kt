package io.wolftown.lauschig.local

import java.security.MessageDigest

/**
 * The reserved pseudo server of titles that only live on this device ("Auf dem Gerät", see the phase-3C plan). Every Room
 * row of a local title is stored under [SERVER_ID]; its item id starts with [ID_PREFIX]. A real server id is always 16 hex
 * characters and ABS ids only use `[A-Za-z0-9_-]`, so neither can ever be mistaken for a server's.
 *
 * Two layers keep local titles away from the server: sync code only ever reads the account's server id, and every
 * sync/upload/download/API entry point also refuses [SERVER_ID] and [isLocalKey] keys on its own.
 */
object DeviceLibrary {
    const val SERVER_ID = "device"
    const val LIBRARY_ID = "device"
    const val ID_PREFIX = "local:"
    private val ID = Regex("local:[0-9a-f]{24}")

    fun isLocal(serverId: String?): Boolean = serverId == SERVER_ID

    /** True for a local title's id (or a playable key starting with one). */
    fun isLocalKey(itemId: String?): Boolean = itemId != null && itemId.startsWith(ID_PREFIX)

    /** True if either the server or the key belongs to a local title: such a pair must never reach a server. */
    fun isLocal(serverId: String?, itemId: String?): Boolean = isLocal(serverId) || isLocalKey(itemId)

    /** A well-formed local id (used before it becomes part of a file name). */
    fun isValidId(itemId: String): Boolean = ID.matches(itemId)

    /** The server a title lives on: [SERVER_ID] for a local id, else the account's server. */
    fun serverFor(accountServerId: String?, itemId: String): String? = if (isLocalKey(itemId)) SERVER_ID else accountServerId

    /** Stable id of the title [groupKey] (a document id plus a kind marker) inside the folder [treeUri]. */
    fun titleId(treeUri: String, groupKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$treeUri\n$groupKey".toByteArray())
        return ID_PREFIX + digest.joinToString("") { "%02x".format(it) }.take(24)
    }
}
