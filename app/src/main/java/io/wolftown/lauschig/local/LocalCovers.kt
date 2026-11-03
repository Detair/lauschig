package io.wolftown.lauschig.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Covers of local titles, copied into app storage (`filesDir/device_covers/<hex>.jpg`) by the scan, so lists, the player
 * and the notification never open the user's folder for a picture. The extension is fixed; decoders sniff the format.
 */
class LocalCovers(private val dir: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, DIR))

    /** Null for anything that is not a well-formed local id (never a path outside [dir]). */
    fun file(itemId: String): File? =
        if (DeviceLibrary.isValidId(itemId)) File(dir, itemId.removePrefix(DeviceLibrary.ID_PREFIX) + ".jpg") else null

    /** Atomic: written next to the target and renamed. */
    fun write(itemId: String, bytes: ByteArray): Boolean {
        val target = file(itemId) ?: return false
        dir.mkdirs()
        val part = File(target.path + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(target)) { part.delete(); return false }
        return true
    }

    fun delete(itemId: String) {
        file(itemId)?.delete()
    }

    companion object {
        const val DIR = "device_covers"
    }
}
