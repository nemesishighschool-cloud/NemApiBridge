package com.ialocalbridge

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Temporary, read-only images exposed only to Android's system document picker.
 * Data stays in the app-private cache and is deleted when the operation ends. */
class BridgeImageProvider : DocumentsProvider() {
    override fun onCreate(): Boolean {
        if (files.isEmpty()) context?.cacheDir?.let { File(it, "pending-images").listFiles()?.forEach(File::delete) }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_MIME_TYPES)
        return MatrixCursor(columns).apply {
            val row = newRow()
            row.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT)
            row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT)
            row.add(DocumentsContract.Root.COLUMN_TITLE, "NemApiBridge — temporary images")
            row.add(DocumentsContract.Root.COLUMN_FLAGS, 0)
            row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "image/*")
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(documentColumns(projection))
        addDocument(cursor, documentId)
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        if (parentDocumentId != ROOT) throw FileNotFoundException()
        return MatrixCursor(documentColumns(projection)).apply { files.keys.filter { it !in hidden }.sorted().forEach { id -> addDocument(this, id) } }
    }

    override fun openDocument(documentId: String, mode: String, signal: android.os.CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val item = files[documentId] ?: throw FileNotFoundException()
        return ParcelFileDescriptor.open(item.file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun documentColumns(projection: Array<out String>?): Array<out String> = projection ?: arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)

    private fun addDocument(cursor: MatrixCursor, id: String) {
        val row = cursor.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
        if (id == ROOT) {
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "Temporary images")
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
            row.add(DocumentsContract.Document.COLUMN_FLAGS, 0)
            return
        }
        val item = files[id] ?: throw FileNotFoundException()
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, item.file.name)
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, item.mime)
        row.add(DocumentsContract.Document.COLUMN_FLAGS, 0)
        row.add(DocumentsContract.Document.COLUMN_SIZE, item.file.length())
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, item.file.lastModified())
    }

    data class Image(val id: String, val file: File, val mime: String)
    companion object {
        private const val ROOT = "images"
        private val files = ConcurrentHashMap<String, Image>()
        private val hidden = ConcurrentHashMap.newKeySet<String>()
        fun stage(context: Context, bytes: ByteArray, mime: String): Image {
            require(bytes.isNotEmpty() && bytes.size <= 4 * 1024 * 1024)
            val extension = when (mime) { "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> throw IllegalArgumentException("Type d’image non pris en charge.") }
            val id = UUID.randomUUID().toString().replace("-", "")
            val directory = File(context.cacheDir, "pending-images").apply { check(exists() || mkdirs()) }
            val file = File(directory, "nem-$id.$extension")
            try { file.outputStream().use { it.write(bytes) } } catch (e: Exception) { file.delete(); throw e }
            val image = Image(id, file, mime)
            files[id] = image
            notify(context)
            return image
        }
        fun hide(context: Context, image: Image) {
            hidden.add(image.id)
            notify(context)
        }
        fun remove(context: Context, image: Image) {
            hidden.remove(image.id)
            files.remove(image.id)
            image.file.delete()
            notify(context)
        }
        private fun notify(context: Context) {
            val authority = context.packageName + ".images"
            context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority), null)
            context.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, ROOT), null)
        }
    }
}
