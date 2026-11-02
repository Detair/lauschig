package io.wolftown.lauschig.db.entities

import androidx.room.Entity
import androidx.room.Index

/** A folder the user picked with the Storage Access Framework ("Auf dem Gerät"); [uri] is the tree URI. */
@Entity(tableName = "local_folders", primaryKeys = ["uri"])
data class LocalFolderEntity(
    val uri: String,
    val name: String,
    val addedAt: Long,
    val lastScanAt: Long = 0,
    /** Null when the last scan could read the folder; else [ERROR_NO_ACCESS] or [ERROR_MISSING]. */
    val error: String? = null,
) {
    companion object {
        const val ERROR_NO_ACCESS = "no_access"
        const val ERROR_MISSING = "missing"
    }
}

/**
 * Where a local title (item id `local:…`, server `DeviceLibrary.SERVER_ID`) comes from. Its item, audio file and chapter
 * rows are ordinary rows under the device server; this row ties them to the folder and remembers what was read.
 */
@Entity(tableName = "local_titles", primaryKeys = ["id"], indices = [Index("folderUri")])
data class LocalTitleEntity(
    val id: String,
    val folderUri: String,
    /** Document id of the title's directory or file plus a kind marker (see `LocalGrouping`). */
    val groupKey: String,
    /** Shown in settings and on the detail page, e.g. `Krimis/Fall 1`. */
    val relativePath: String,
    /** Fingerprint of the files (document id, size, mtime) and the cover the metadata was read from. */
    val signature: String,
    val scannedAt: Long,
)
