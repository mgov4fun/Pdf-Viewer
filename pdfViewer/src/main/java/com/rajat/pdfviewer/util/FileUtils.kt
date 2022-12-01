package com.rajat.pdfviewer.util

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
    fun copyBytesToDownloads(context: Context, bytes: ByteArray, fileName: String?) {
        val fileNameWithExt = "$fileName.pdf"

        var fos: OutputStream?
        var fileUri: Uri? = null
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
            val fileDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString();
            val file = File(fileDir, fileNameWithExt);

            fos = FileOutputStream(file);

            val downloadManger = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManger.addCompletedDownload(fileName, fileName, true,
                    MIME_TYPE_PDF, fileNameWithExt, bytes.size.toLong(), true)
        }

        fos!!.write(bytes);
        fos!!.close();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            //Send notification when finished
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_FINISHED, fileUri!!))
        }
    }
}