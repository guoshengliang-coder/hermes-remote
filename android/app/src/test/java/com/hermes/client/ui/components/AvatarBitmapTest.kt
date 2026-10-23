package com.hermes.client.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Regression for the 0.1.82 avatar bug: replacing the photo behind a live [ProfileAvatar] left
 * the first decode on screen. The fix re-resolves the bitmap for every cache key.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AvatarBitmapTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun photo(name: String, argb: Int): File {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }
        val file = temp.newFile(name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun ImageBitmap.centrePixel(): Int = asAndroidBitmap().getPixel(1, 1)

    /**
     * Decoding hops to Dispatchers.IO and back; under Robolectric the return hop sits on the paused
     * main looper until something idles it, so poll with waitForIdle rather than the plain clock wait.
     */
    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met within $timeoutMs ms")
            Thread.sleep(20)
            compose.waitForIdle()
        }
    }

    @Test fun replacingThePhotoFileReDecodesInTheSameComposable() {
        val first = photo("a.png", red)
        val second = photo("b.png", blue)
        val file = mutableStateOf(first)
        var latest: ImageBitmap? = null
        compose.setContent { latest = rememberAvatarBitmap(file.value) }
        await { latest != null }
        assertEquals(red, latest!!.centrePixel())

        // Same composable instance, new file: before the fix this stayed red for good.
        file.value = second
        await { latest?.centrePixel() == blue }
    }

    @Test fun firstDecodeStillWorksFromEmpty() {
        val only = photo("c.png", blue)
        var latest: ImageBitmap? = null
        compose.setContent { latest = rememberAvatarBitmap(only) }
        await { latest != null }
        assertEquals(blue, latest!!.centrePixel())
    }
}
