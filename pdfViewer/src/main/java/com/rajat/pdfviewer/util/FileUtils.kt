package com.rajat.pdfviewer.util

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationManagerCompat
import java.io.*


private const val MIME_TYPE_PDF = "application/pdf"

object FileUtils {
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
            val bytes = ByteArray(1024)
            while (inputStream!!.read(bytes).also { read = it } != -1) {
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
        val fileNameWithExt = "$fileName.pdf"

        var fos: OutputStream?
        var fileUri: Uri? = null
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileNameWithExt)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.TITLE, fileName)
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE_PDF)
                put(MediaStore.Downloads.SIZE, bytes.size)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            fileUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            fos = resolver.openOutputStream(fileUri!!)

        } else {
            fileUri = Uri.fromFile(file);
            fos = FileOutputStream(file);
        }

        fos!!.write(bytes);
        fos!!.close();

        val downloadManger = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManger.addCompletedDownload(fileName, fileName, false,
            MIME_TYPE_PDF, file.absolutePath, bytes.size.toLong(), true)
            
        return fileUri
    }
}