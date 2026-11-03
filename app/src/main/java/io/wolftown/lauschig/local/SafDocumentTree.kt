package io.wolftown.lauschig.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** [DocumentTree] over the Storage Access Framework: only folders the user granted (persisted read permission). */
@Singleton
class SafDocumentTree @Inject constructor(@ApplicationContext private val context: Context) : DocumentTree {
    private val resolver get() = context.contentResolver

    override fun access(treeUri: String): TreeAccess {
        val uri = treeUri.toUri()
        val held = try {
            resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        } catch (e: Exception) {
            false
        }
        if (!held) return TreeAccess.NO_ACCESS
        return if (root(treeUri) != null) TreeAccess.OK else TreeAccess.MISSING
    }

    override fun root(treeUri: String): DocEntry? {
        val uri = treeUri.toUri()
        val docId = try { DocumentsContract.getTreeDocumentId(uri) } catch (e: Exception) { return null }
        return query(DocumentsContract.buildDocumentUriUsingTree(uri, docId))?.firstOrNull()
    }

    override fun children(treeUri: String, dir: DocEntry): List<DocEntry>? =
        query(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri.toUri(), dir.documentId))

    override fun documentUri(treeUri: String, documentId: String): String =
        DocumentsContract.buildDocumentUriUsingTree(treeUri.toUri(), documentId).toString()

    override fun readBytes(uri: String, maxBytes: Int): ByteArray? = try {
        resolver.openInputStream(uri.toUri())?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > maxBytes) return@use null
            }
            out.toByteArray()
        }
    } catch (e: Exception) {
        null
    }

    /** Null when the provider refuses (permission revoked) or the document is gone. */
    private fun query(uri: Uri): List<DocEntry>? = try {
        resolver.query(uri, COLUMNS, null, null, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val size = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modified = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            buildList {
                while (c.moveToNext()) {
                    val type = c.getString(mime)
                    add(
                        DocEntry(
                            documentId = c.getString(id) ?: continue,
                            name = c.getString(name).orEmpty(),
                            isDirectory = type == DocumentsContract.Document.MIME_TYPE_DIR,
                            size = if (c.isNull(size)) 0 else c.getLong(size),
                            lastModified = if (c.isNull(modified)) 0 else c.getLong(modified),
                            mimeType = type,
                        ),
                    )
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "listing a picked folder failed: ${e.javaClass.simpleName}")
        null
    }

    private companion object {
        const val TAG = "SafDocumentTree"
        val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

/** [AudioProbe] over [MediaMetadataRetriever] (reads `content://` documents through the persisted permission). */
@Singleton
class RetrieverAudioProbe @Inject constructor(@ApplicationContext private val context: Context) : AudioProbe {
    override fun tags(uri: String): AudioTags? = withRetriever(uri) { r ->
        AudioTags(
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
            album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
            albumArtist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
            author = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR),
            composer = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
        )
    }

    override fun embeddedPicture(uri: String): ByteArray? = withRetriever(uri) { it.embeddedPicture }

    private fun <T> withRetriever(uri: String, block: (MediaMetadataRetriever) -> T?): T? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri.toUri())
            block(r)
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }
}
