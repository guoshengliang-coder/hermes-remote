package com.hermes.client.ui.account

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Account photo only: no Hermes identity store, accent, static bitmap cache or network loader. */
@Composable
internal fun AccountProfileAvatar(owner: String, name: String?, path: String?) = key(owner, path) {
    val bitmap = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = path?.let { withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(it, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 128).coerceAtLeast(1)
                }
                BitmapFactory.decodeFile(it, options)?.asImageBitmap()
            }.getOrNull()
        } }
    }.value
    val modifier = Modifier.size(40.dp).clip(CircleShape).testTag("account-profile-avatar")
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Text(name?.takeIf { it.isNotEmpty() }?.let { String(Character.toChars(it.codePointAt(0))).uppercase() }.orEmpty(), style = MaterialTheme.typography.titleMedium)
    }
}
