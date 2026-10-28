package com.rajat.pdfviewer

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean

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

    private suspend fun download(downloadUrl: String) {
        listener.getCoroutineScope().launch(Dispatchers.Main) { listener.onDownloadStart() }
        val cacheDir = listener.getContext().cacheDir
        var temporaryFile: File? = null
        var completedFile: File? = null
        var connection: java.net.URLConnection? = null
        try {
            val bufferSize = 8192
            connection = URL(downloadUrl).openConnection()
            connection.connect()

            val totalLength = connection.contentLength
            temporaryFile = File.createTempFile("downloaded_pdf_", ".part", cacheDir)
            val outputFile = temporaryFile!!
            var downloaded = 0L

            BufferedInputStream(connection.getInputStream(), bufferSize).use { inputStream ->
                outputFile.outputStream().use { outputStream ->
                    val data = ByteArray(bufferSize)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = inputStream.read(data)
                        if (count == -1) break
                        outputStream.write(data, 0, count)
                        downloaded += count
                        if (totalLength > 0 && downloaded < totalLength) {
                            val currentBytes = downloaded
                            listener.getCoroutineScope().launch(Dispatchers.Main) {
                                listener.onDownloadProgress(currentBytes, totalLength.toLong())
                            }
                        }
                    }
                }
            }

            if (totalLength > 0 && downloaded != totalLength.toLong()) {
                throw IOException("Downloaded byte count did not match the declared content length")
            }
            coroutineContext.ensureActive()
            val finalFile = File(cacheDir, outputFile.name.removeSuffix(".part") + ".pdf")
            if (finalFile.exists() || !outputFile.renameTo(finalFile)) {
                throw IllegalStateException("Unable to publish downloaded PDF")
            }
            temporaryFile = null
            completedFile = finalFile

            if (totalLength > 0) {
                listener.getCoroutineScope().launch(Dispatchers.Main) {
                    listener.onDownloadProgress(downloaded, totalLength.toLong())
                }
            }
            val callbackStarted = AtomicBoolean(false)
            val callbackJob = listener.getCoroutineScope().launch(Dispatchers.Main) {
                callbackStarted.set(true)
                listener.onDownloadSuccess(finalFile.absolutePath)
            }
            callbackJob.invokeOnCompletion { cause ->
                if (cause != null && !callbackStarted.get()) finalFile.delete()
            }
            completedFile = null
        } catch (e: CancellationException) {
            temporaryFile?.delete()
            completedFile?.delete()
            throw e
        } catch (e: Exception) {
            temporaryFile?.delete()
            completedFile?.delete()
            listener.getCoroutineScope().launch(Dispatchers.Main) { listener.onError(e) }
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
    }
}
