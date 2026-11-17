package app.harvester.companion.core.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import app.harvester.companion.UHLog
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.feature.downloads.filewriter.DownloadFileWriter

class UhDownloadFileWriter(private val context: Context) : DownloadFileWriter {

    override fun useFileStream(
        download: DownloadState,
        append: Boolean,
        shouldUseScopedStorage: Boolean,
        onUpdateState: (DownloadState) -> Unit,
        block: (OutputStream) -> Unit,
    ) {
        if (download.directoryPath.startsWith("content://")) {
            val wroteSaf = useSafTreeStream(download, append, onUpdateState, block)
            if (wroteSaf) return
            UHLog.w("download_warn", "Falling back to default download writer because SAF tree URI could not be opened: ${download.directoryPath}")
        }

        if (shouldUseScopedStorage) {
            useScopedStorageStream(download, append, onUpdateState, block)
        } else {
            useLegacyStorageStream(download, append, onUpdateState, block)
        }
    }

    private fun useSafTreeStream(
        download: DownloadState,
        append: Boolean,
        onUpdateState: (DownloadState) -> Unit,
        block: (OutputStream) -> Unit,
    ): Boolean {
        return try {
            val treeUri = Uri.parse(download.directoryPath)
            val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
            if (treeDoc == null || !treeDoc.canWrite()) {
                UHLog.w("download_warn", "SAF download directory not writable: ${download.directoryPath}")
                return false
            }
            val fileName = download.fileName ?: "download"
            val mimeType = download.contentType ?: "application/octet-stream"
            val existing = treeDoc.findFile(fileName)
            val targetDoc = if (append && existing != null) {
                existing
            } else {
                treeDoc.createFile(mimeType, fileName)
            }
            if (targetDoc == null) {
                UHLog.e("download_error", "Failed to create SAF document for $fileName in ${download.directoryPath}")
                return false
            }
            val resolvedName = targetDoc.name
            if (!resolvedName.isNullOrBlank() && resolvedName != download.fileName) {
                onUpdateState(download.copy(fileName = resolvedName))
            }
            val mode = if (append) "wa" else "w"
            val out = context.contentResolver.openOutputStream(targetDoc.uri, mode)
            if (out == null) {
                UHLog.e("download_error", "ContentResolver returned null stream for ${targetDoc.uri}")
                return false
            }
            out.use { outStream ->
                block(outStream)
            }
            true
        } catch (e: Exception) {
            UHLog.throwable("download_error", "Failed writing download to SAF tree URI", e)
            false
        }
    }

    private fun useScopedStorageStream(
        download: DownloadState,
        append: Boolean,
        onUpdateState: (DownloadState) -> Unit,
        block: (OutputStream) -> Unit,
    ) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val mimeType = download.contentType ?: "application/octet-stream"
        val fileName = download.fileName ?: "download"
        val fallbackDir = DownloadLocationResolver.defaultPublicDownloadsPath()
        val effectiveDir = if (download.directoryPath.startsWith("content://") || download.directoryPath.isBlank()) {
            fallbackDir
        } else {
            download.directoryPath
        }
        val relativePath = effectiveDir.removePrefix("/storage/emulated/0/").ifBlank { Environment.DIRECTORY_DOWNLOADS }

        var uri = if (append) findExistingFileUri(collection, fileName, effectiveDir) else null
        var uriChanged = false

        if (uri == null) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = resolver.insert(collection, values)
            uriChanged = uri != null
        }

        if (uri == null) {
            UHLog.e("download_error", "Failed to insert MediaStore entry for $fileName")
            return
        }
        if (uriChanged) {
            try {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val actualName = c.getString(0)
                        if (!actualName.isNullOrBlank() && actualName != download.fileName) {
                            onUpdateState(download.copy(fileName = actualName))
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        try {
            val mode = if (append) "wa" else "w"
            val stream = resolver.openOutputStream(uri, mode)
            if (stream == null) {
                UHLog.e("download_error", "Failed to open MediaStore output stream for $uri")
                return
            }
            stream.use { outStream ->
                block(outStream)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
        } catch (e: Exception) {
            UHLog.throwable("download_error", "Scoped storage download write failed for $fileName", e)
            throw e
        }
    }

    private fun useLegacyStorageStream(
        download: DownloadState,
        append: Boolean,
        onUpdateState: (DownloadState) -> Unit,
        block: (OutputStream) -> Unit,
    ) {
        val dirPath = if (download.directoryPath.startsWith("content://") || download.directoryPath.isBlank()) {
            DownloadLocationResolver.defaultPublicDownloadsPath()
        } else {
            download.directoryPath
        }
        val directory = File(dirPath)
        if (!directory.exists() && !directory.mkdirs()) {
            UHLog.e("download_error", "Failed to create download directory: $dirPath")
        }

        val file = File(directory, download.fileName ?: "download")

        FileOutputStream(file, append).use { outStream ->
            block(outStream)
        }
    }

    private fun findExistingFileUri(collection: Uri, fileName: String, directoryPath: String): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(fileName, "%${directoryPath.substringAfterLast("/")}%")

        context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }
}
