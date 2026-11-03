package io.wolftown.lauschig.auto

import android.net.Uri
import io.wolftown.lauschig.local.DeviceLibrary

/** The cover of a playable [key] (a book, an episode: the podcast's cover, a device title) in [version]. */
data class CoverRef(val serverId: String, val key: String, val version: Long)

/**
 * `content://<authority>/cover/<serverId>/<key>/<version>`: covers other apps (Android Auto, SystemUI) can open through
 * [CoverProvider]. The version is part of the path, so a changed cover gets a new URI and the car's image cache
 * never shows the old one. [parse] accepts only well-formed ids; nothing in a URI is ever used as a path.
 */
object CoverUris {
    private const val COVER = "cover"
    private val SERVER_ID = Regex("[0-9a-f]{16}")
    private val SERVER_KEY = Regex("[A-Za-z0-9_-]{1,128}(~[A-Za-z0-9_-]{1,128})?")
    private val VERSION = Regex("[0-9]{1,19}")

    fun authority(applicationId: String) = "$applicationId.covers"

    /** Every cover URI starts with this (the prefix grant to the car hosts). */
    fun prefix(authority: String): Uri = Uri.Builder().scheme("content").authority(authority).appendPath(COVER).build()

    fun forTitle(authority: String, ref: CoverRef): Uri = Uri.Builder().scheme("content").authority(authority)
        .appendPath(COVER).appendPath(ref.serverId).appendPath(ref.key).appendPath(ref.version.coerceAtLeast(0).toString()).build()

    /** Null for anything that is not one of our cover URIs (other authority, extra segments, malformed ids). */
    fun parse(uri: Uri, authority: String? = null): CoverRef? {
        if (uri.scheme != "content" || (authority != null && uri.authority != authority)) return null
        val segments = uri.pathSegments
        if (segments.size != 4 || segments[0] != COVER) return null
        val (_, serverId, key, version) = segments
        val ok = if (DeviceLibrary.isLocal(serverId)) DeviceLibrary.isValidId(key) else SERVER_ID.matches(serverId) && SERVER_KEY.matches(key)
        if (!ok || !VERSION.matches(version)) return null
        return CoverRef(serverId, key, version.toLongOrNull() ?: return null)
    }
}
