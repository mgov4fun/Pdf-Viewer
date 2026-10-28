package com.rajat.pdfviewer

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.net.URLStreamHandlerFactory
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PdfDownloaderRegressionTest {
    @Test
    fun truncatedKnownLengthResponseReportsErrorAndRemovesAttemptFiles() = withFixture { context, scope ->
        val scenario = FakeResponse("short".toByteArray(), declaredLength = 100)
        val route = register(scenario)
        val result = DownloadResult()

        try {
            PdfDownloader(testUrl(route), TestListener(context, scope, result))

            assertTrue("Failed download did not finish", result.done.await(10, TimeUnit.SECONDS))
            val connection = scenario.connection()
            assertTrue("Connection did not disconnect", connection.disconnected.await(5, TimeUnit.SECONDS))
            assertTrue(result.error != null)
            assertEquals(null, result.path)
            assertEquals(1, scenario.inputOpens.get())
            assertTrue(connection.streamClosed.count == 0L)
            assertTrue(context.cacheDir.listFiles().orEmpty().isEmpty())
        } finally {
            FakeRoutes.responses.remove(route)
        }
    }

    @Test
    fun concurrentSameUrlDownloadsUseIsolatedFilesAndPreserveExistingCacheFile() = withFixture { context, scope ->
        val sentinel = File(context.cacheDir, "downloaded_pdf.pdf")
        val sentinelBytes = byteArrayOf(9, 8, 7)
        sentinel.writeBytes(sentinelBytes)
        val payload = ByteArray(9001) { (it % 251).toByte() }
        val scenario = FakeResponse(payload, declaredLength = payload.size)
        val route = register(scenario)
        val results = listOf(DownloadResult(), DownloadResult())
        val url = testUrl(route)

        try {
            results.forEach { PdfDownloader(url, TestListener(context, scope, it)) }

            results.forEach { assertTrue("Download did not finish", it.done.await(10, TimeUnit.SECONDS)) }
            scenario.connections.forEach { assertTrue("Connection did not disconnect", it.disconnected.await(5, TimeUnit.SECONDS)) }
            assertEquals(2, scenario.inputOpens.get())
            assertEquals(2, scenario.connections.size)
            assertNotEquals(results[0].path, results[1].path)
            scenario.connections.forEach { assertTrue("Input stream did not close", it.streamClosed.count == 0L) }
            results.forEach { result ->
                assertEquals(null, result.error)
                assertArrayEquals(payload, File(result.path!!).readBytes())
                assertEquals(payload.size.toLong(), result.progress.last().first)
                assertEquals(payload.size.toLong(), result.progress.last().second)
            }
            assertArrayEquals(sentinelBytes, sentinel.readBytes())
            results.mapNotNull { it.path }.forEach { File(it).delete() }
        } finally {
            FakeRoutes.responses.remove(route)
        }
    }

    private inline fun withFixture(test: (Context, CoroutineScope) -> Unit) {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = File(targetContext.cacheDir, "pdf-downloader-test-${UUID.randomUUID()}")
        check(cache.mkdir()) { "Unable to create isolated test cache" }
        val context = object : ContextWrapper(targetContext) {
            override fun getCacheDir(): File = cache
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            test(context, scope)
        } finally {
            scope.cancel()
            cache.deleteRecursively()
        }
    }

    private fun register(response: FakeResponse): String {
        val route = UUID.randomUUID().toString()
        FakeRoutes.responses[route] = response
        return route
    }

    private fun testUrl(route: String) = "$TEST_SCHEME://$route/document"

    private class DownloadResult {
        val done = CountDownLatch(1)
        val progress = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        @Volatile var path: String? = null
        @Volatile var error: Throwable? = null
    }

    private class TestListener(
        private val context: Context,
        private val scope: CoroutineScope,
        private val result: DownloadResult
    ) : PdfDownloader.StatusListener {
        override fun getContext() = context
        override fun getCoroutineScope() = scope
        override fun onDownloadProgress(currentBytes: Long, totalBytes: Long) {
            result.progress.add(currentBytes to totalBytes)
        }
        override fun onDownloadSuccess(absolutePath: String) {
            result.path = absolutePath
            result.done.countDown()
        }
        override fun onError(error: Throwable) {
            result.error = error
            result.done.countDown()
        }
    }

    companion object {
        private const val TEST_SCHEME = "pdfdownloader-test"

        @JvmStatic
        @BeforeClass
        fun installTestUrlHandler() {
            try {
                URL.setURLStreamHandlerFactory(URLStreamHandlerFactory { protocol ->
                    if (protocol == TEST_SCHEME) TestUrlHandler() else null
                })
            } catch (error: Error) {
                assumeNoException("URLStreamHandlerFactory already installed; downloader tests skipped", error)
            }
        }
    }
}

private object FakeRoutes {
    val responses = java.util.concurrent.ConcurrentHashMap<String, FakeResponse>()
}

private class FakeResponse(
    val bytes: ByteArray,
    val declaredLength: Int,
    val failAfterBytes: Int? = null
) {
    val inputOpens = AtomicInteger()
    val connections = Collections.synchronizedList(mutableListOf<FakeConnection>())

    fun connection(): FakeConnection = connections.single()
}

private class TestUrlHandler : URLStreamHandler() {
    override fun openConnection(url: URL): URLConnection {
        val response = FakeRoutes.responses[url.host]
            ?: throw IOException("No fake response registered for test route")
        return FakeConnection(url, response)
    }
}

private class FakeConnection(url: URL, private val response: FakeResponse) : HttpURLConnection(url) {
    val disconnected = CountDownLatch(1)
    val streamClosed = CountDownLatch(1)

    init {
        response.connections.add(this)
    }

    override fun connect() = Unit
    override fun disconnect() { disconnected.countDown() }
    override fun usingProxy() = false
    override fun getContentLength() = response.declaredLength

    override fun getInputStream(): InputStream {
        response.inputOpens.incrementAndGet()
        return object : InputStream() {
            private var offset = 0

            override fun read(): Int {
                val oneByte = ByteArray(1)
                val count = read(oneByte, 0, 1)
                return if (count < 0) -1 else oneByte[0].toInt() and 0xff
            }

            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                response.failAfterBytes?.let { if (offset >= it) throw IOException("Synthetic test stream failure") }
                if (offset >= response.bytes.size) return -1
                val count = minOf(len, response.bytes.size - offset, response.failAfterBytes?.minus(offset) ?: Int.MAX_VALUE)
                response.bytes.copyInto(buffer, off, offset, offset + count)
                offset += count
                return count
            }

            override fun close() {
                streamClosed.countDown()
            }
        }
    }
}
