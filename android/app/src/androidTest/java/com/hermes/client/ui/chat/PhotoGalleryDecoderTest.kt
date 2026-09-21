package com.hermes.client.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class PhotoGalleryDecoderTest {
    @Test fun landscape_thumbnail_decodes_to_the_requested_square() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "gallery-landscape-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        try {
            val method = Class.forName("com.hermes.client.ui.chat.PhotoGalleryDialogKt").getDeclaredMethod(
                "decodeGalleryBitmap",
                android.content.ContentResolver::class.java,
                Uri::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val decoded = method.invoke(
                null,
                context.contentResolver,
                Uri.fromFile(source),
                80,
                true,
            ) as Bitmap?
            assertNotNull(decoded)
            assertEquals(80, decoded?.width)
            assertEquals(80, decoded?.height)
            decoded?.recycle()
        } finally {
            source.delete()
        }
    }
}
