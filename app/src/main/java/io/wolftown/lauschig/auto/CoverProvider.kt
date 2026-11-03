package io.wolftown.lauschig.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException

/**
 * Covers for other apps (Android Auto, SystemUI's media controls and resumption card): they can neither read app
 * storage nor send the server token. Read-only, never exported; callers get a prefix grant on connect (see
 * [CarAccess]). Only [CoverUris] of well-formed ids resolve, through [CoverStore]; anything else is "not found".
 */
class CoverProvider : ContentProvider() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun coverStore(): CoverStore
    }

    private val store by lazy { EntryPointAccessors.fromApplication(context!!.applicationContext, Deps::class.java).coverStore() }
    private val authority by lazy { CoverUris.authority(context!!.packageName) }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("covers are read-only")
        val ref = CoverUris.parse(uri, authority) ?: throw FileNotFoundException("not a cover")
        val file = store.file(ref) ?: throw FileNotFoundException("no cover")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = if (CoverUris.parse(uri, authority) != null) "image/jpeg" else null

    /** Name and size only (some image loaders ask before opening). */
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val ref = CoverUris.parse(uri, authority) ?: return null
        val file = store.file(ref) ?: return null
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else "cover.jpg" })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
