package com.rajat.pdfviewer.util

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import java.io.*

private const val MIME_TYPE_PDF = "application/pdf"

object FileUtils {

    private const val BUFFER_SIZE = 1024 * 5

    @Throws(IOException::class)
    fun fileFromAsset(context: Context, assetName: String): File {
        val outFile = File(context.cacheDir, "$assetName")
        if (assetName.contains("/")) {
            outFile.parentFile.mkdirs()
        }
        copy(context.assets.open(assetName), outFile)
        return outFile
    }

    @Throws(IOException::class)
    fun copy(inputStream: InputStream?, output: File?) {
        var outputStream: OutputStream? = null
        try {
            outputStream = FileOutputStream(output)
            var read = 0
            val bytes = ByteArray(BUFFER_SIZE)
            while (inputStream!!.read(bytes, 0, BUFFER_SIZE).also { read = it } != -1) {
                outputStream.write(bytes, 0, read)
            }
        } finally {
            try {
                inputStream?.close()
            } finally {
                outputStream?.close()
            }
        }
    }

    @Throws(IOException::class)
    fun copy(inputStream: InputStream?, outputStream: OutputStream?) {
        try {
            var read = 0
            val bytes = ByteArray(BUFFER_SIZE)
            while (inputStream!!.read(bytes, 0, BUFFER_SIZE).also { read = it } != -1) {
                outputStream!!.write(bytes, 0, read)
            }
        } finally {
            try {
                inputStream?.close()
            } finally {
                outputStream?.close()
            }
        }
    }

    @Throws(IOException::class)
    fun downloadFile(context: Context, assetName: String, filePath: String, fileName: String?) {

        val dirPath = "${Environment.getExternalStorageDirectory()}/${filePath}"
        val outFile = File(dirPath)
        //Create New File if not present
        if (!outFile.exists()) {
            outFile.mkdirs()
        }
        val outFile1 = File(dirPath, "/$fileName.pdf")
        copy(context.assets.open(assetName), outFile1)
    }

    @Throws(IOException::class)
    fun copyBytesToDownloads(context: Context, bytes: ByteArray, fileName: String?): Uri? {
        return copyToDownloads(context, ByteArrayInputStream(bytes), bytes.size.toLong(), fileName)
    }

    @Throws(IOException::class)
    fun copyFileToDownloads(context: Context, filePath: String, fileName: String?): Uri? {
        return copyFileToDownloadsInternal(context, filePath, fileName, true)
    }

    /** Saves a local PDF without displaying success; the caller owns lifecycle-aware feedback. */
    internal fun copyFileToDownloadsWithoutToast(context: Context, filePath: String, fileName: String?): Uri? {
        return copyFileToDownloadsInternal(context, filePath, fileName, false)
    }

    @Throws(IOException::class)
    private fun copyFileToDownloadsInternal(
        context: Context,
        filePath: String,
        fileName: String?,
        showSuccessToast: Boolean
    ): Uri? {
        val file = File(filePath)
        if (!file.exists() || !file.isFile()){
            throw IOException("File not found: $filePath")
        }
        return copyToDownloads(context, FileInputStream(filePath), file.length(), fileName, showSuccessToast)
    }

    @Throws(IOException::class)
    fun copyToDownloads(context: Context, inputS: InputStream, size: Long, fileName: String?): Uri? {
        return copyToDownloads(context, inputS, size, fileName, true)
    }

    @Throws(IOException::class)
    private fun copyToDownloads(
        context: Context,
        inputS: InputStream,
        size: Long,
        fileName: String?,
        showSuccessToast: Boolean
    ): Uri? {
        val fileNameWithExt = normalizedPdfFileName(fileName)
        val title = fileNameWithExt.substring(0, fileNameWithExt.length - 4)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.TITLE, title)
                put(MediaStore.Downloads.DISPLAY_NAME, fileNameWithExt)
                put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE_PDF)
                put(MediaStore.Downloads.SIZE, size)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            var insertedUri: Uri? = null
            val publishedUri = try {
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw IOException("Unable to create PDF download")
                insertedUri = uri
                val output = resolver.openOutputStream(uri)
                    ?: throw IOException("Unable to write PDF download")
                copy(inputS, output)
                val published = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                if (resolver.update(uri, published, null, null) <= 0) {
                    throw IOException("Unable to publish PDF download")
                }
                uri
            } catch (exception: Exception) {
                closeQuietly(inputS)
                if (insertedUri != null) {
                    try {
                        resolver.delete(insertedUri, null, null)
                    } catch (_: Exception) {
                        // Preserve the original write/publish failure.
                    }
                }
                if (exception is IOException) throw exception
                throw IOException("Unable to save PDF download", exception)
            }
            if (showSuccessToast) showDownloadSuccess(context)
            return publishedUri
        }

        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileNameWithExt
        )
        val fileUri = Uri.fromFile(file)
        try {
            val output = FileOutputStream(file)
            copy(inputS, output)
            if (!file.isFile) throw IOException("Unable to write PDF download")
            val downloadManger = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManger.addCompletedDownload(
                title, title, false,
                MIME_TYPE_PDF, file.absolutePath, size, true
            )
        } catch (exception: Exception) {
            closeQuietly(inputS)
            if (exception is IOException) throw exception
            throw IOException("Unable to save PDF download", exception)
        }
        if (showSuccessToast) showDownloadSuccess(context)
        return fileUri
    }

    private fun normalizedPdfFileName(fileName: String?): String {
        if (fileName.isNullOrBlank()) return "Dokument.pdf"
        val basename = fileName.replace('\\', '/').substringAfterLast('/')
        val safe = StringBuilder()
        var offset = 0
        var codePointCount = 0
        while (offset < basename.length && codePointCount < 120) {
            val codePoint = basename.codePointAt(offset)
            offset += Character.charCount(codePoint)
            if (!Character.isISOControl(codePoint) && codePoint != '/'.code) {
                safe.appendCodePoint(codePoint)
                codePointCount++
            }
        }
        val normalized = safe.toString().trim()
        if (normalized.isBlank() || normalized == "." || normalized == "..") return "Dokument.pdf"
        return if (normalized.endsWith(".pdf", ignoreCase = true)) normalized else "$normalized.pdf"
    }

    private fun closeQuietly(input: InputStream) {
        try {
            input.close()
        } catch (_: IOException) {
            // Preserve the original failure.
        }
    }

    private fun showDownloadSuccess(context: Context) {
        val applicationContext = context.applicationContext
        val showToast = Runnable {
            Toast.makeText(
                applicationContext,
                "Datei erfolgreich heruntergeladen",
                Toast.LENGTH_SHORT
            ).show()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showToast.run()
        } else {
            Handler(Looper.getMainLooper()).post(showToast)
        }
    }
}
