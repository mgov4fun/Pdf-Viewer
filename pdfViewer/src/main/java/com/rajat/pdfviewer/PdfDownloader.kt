package com.rajat.pdfviewer

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URL

/**
 * Created by Rajat on 11,July,2020
 */

internal class PdfDownloader(url: String, private val listener: StatusListener) {
    interface StatusListener {
        fun getContext(): Context
        fun onDownloadStart() {}
        fun onDownloadProgress(currentBytes: Long, totalBytes: Long) {}
        fun onDownloadSuccess(absolutePath: String) {}
        fun onError(error: Throwable) {}
        fun getCoroutineScope(): CoroutineScope
    }

    init {
        listener.getCoroutineScope().launch(Dispatchers.IO) { download(url) }
    }

    private fun download(downloadUrl: String) {
        listener.getCoroutineScope().launch(Dispatchers.Main) { listener.onDownloadStart() }
        val outputFile = File(listener.getContext().cacheDir, "downloaded_pdf.pdf")
        if (outputFile.exists())
            outputFile.delete()

        try {
            var inputStream: InputStream? = null
            var totalLength: Number
            val url = URL(downloadUrl)
            val connection = url.openConnection()
            connection.connect()

            totalLength = connection.contentLength
            inputStream = BufferedInputStream(url.openStream(), bufferSize)

            val outputStream = outputFile.outputStream()
            var downloaded = 0

            do {
                val data = ByteArray(bufferSize)
                val count = inputStream.read(data)
                if (count == -1)
                    break
                if (totalLength > 0) {
                    downloaded += bufferSize
                    listener.getCoroutineScope().launch(Dispatchers.Main) {
                        listener.onDownloadProgress(
                                downloaded.toLong(),
                                totalLength.toLong()
                        )
                    }
                }
                outputStream.write(data, 0, count)
            } while (true)
        } catch (e: Exception) {
            e.printStackTrace()
            listener.getCoroutineScope().launch(Dispatchers.Main) { listener.onError(e) }
            return
        }
        listener.getCoroutineScope().launch(Dispatchers.Main) { listener.onDownloadSuccess(outputFile.absolutePath) }
    }
}
