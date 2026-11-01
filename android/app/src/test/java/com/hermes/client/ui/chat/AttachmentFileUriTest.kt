package com.hermes.client.ui.chat

import android.content.Context
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentFileUriTest {
    @Test fun viewers_get_original_unicode_name_and_can_read_the_same_copy_repeatedly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val local = File(context.cacheDir, "chat-files/hash-____________.pdf").apply {
            parentFile!!.mkdirs()
            writeText("%PDF-1.4 fixture")
        }
        val name = "协议书（最终版）-report #1(中文与 English).pdf"
        try {
            repeat(2) {
                val uri = attachmentFileUri(context, local, name)
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)!!.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(name, cursor.getString(0))
                    assertEquals(local.length(), cursor.getLong(1))
                }
                assertEquals("%PDF-1.4 fixture", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            }
            assertTrue(local.isFile)
        } finally { local.delete() }
    }
}
