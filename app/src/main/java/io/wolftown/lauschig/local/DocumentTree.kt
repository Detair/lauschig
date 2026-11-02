package io.wolftown.lauschig.local

/** Whether a picked folder can be read right now. */
enum class TreeAccess { OK, NO_ACCESS, MISSING }

/**
 * Read access to a folder picked with the Storage Access Framework, behind an interface so the scan logic runs in unit
 * tests (`SafDocumentTree` is the real one). Every call may block (content provider I/O): call it off the main thread.
 */
interface DocumentTree {
    /** [TreeAccess.NO_ACCESS]: the persisted permission is gone; [TreeAccess.MISSING]: the folder cannot be found. */
    fun access(treeUri: String): TreeAccess

    /** The picked folder itself; null if it cannot be read. */
    fun root(treeUri: String): DocEntry?

    /** Children of [dir]; null if it cannot be listed. */
    fun children(treeUri: String, dir: DocEntry): List<DocEntry>?

    /** The `content://` URI of a document below [treeUri], as stored in `audio_files.localPath`. */
    fun documentUri(treeUri: String, documentId: String): String

    /** The bytes of a small document (a cover image); null if unreadable or larger than [maxBytes]. */
    fun readBytes(uri: String, maxBytes: Int): ByteArray?
}

/** Tags and pictures of an audio file (`RetrieverAudioProbe` is the real one). Blocking; never throws. */
interface AudioProbe {
    /** Null if the file cannot be read at all. */
    fun tags(uri: String): AudioTags?

    fun embeddedPicture(uri: String): ByteArray?
}
