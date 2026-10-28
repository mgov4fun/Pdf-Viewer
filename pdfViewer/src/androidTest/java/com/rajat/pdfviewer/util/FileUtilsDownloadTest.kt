package com.rajat.pdfviewer.util

import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileUtilsDownloadTest {
    @Test
    fun modernDownloadPublishesOnePdfNameAndPreservesBytes() {
        assumeTrue("MediaStore Downloads requires Android 10+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d, 0x31)
        val uri = FileUtils.copyBytesToDownloads(context, bytes, "file-utils-regression.pdf")
            ?: throw AssertionError("Expected a MediaStore download URI")

        try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.MIME_TYPE),
                null,
                null,
                null
            ) ?: throw AssertionError("Expected the published download row")
            cursor.use {
                assertEquals(1, it.count)
                assertEquals(true, it.moveToFirst())
                assertEquals(
                    "file-utils-regression.pdf",
                    it.getString(it.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME))
                )
                assertEquals(
                    "application/pdf",
                    it.getString(it.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE))
                )
            }
            val actualBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw AssertionError("Expected readable PDF bytes")
            assertArrayEquals(bytes, actualBytes)
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }
}
