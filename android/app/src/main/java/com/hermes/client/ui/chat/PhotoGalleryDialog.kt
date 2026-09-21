package com.hermes.client.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.ui.components.ErrorState
import com.hermes.client.ui.components.EmptyState
import com.hermes.client.ui.components.LoadingState
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal data class GalleryPhoto(
    val uri: String,
    val name: String,
    val albumId: String,
    val albumName: String,
    val takenAt: Long,
)

internal data class GalleryAlbum(
    val id: String,
    val name: String,
    val coverUri: String,
    val count: Int,
)

internal fun galleryAlbums(photos: List<GalleryPhoto>): List<GalleryAlbum> = photos
    .groupBy { it.albumId }
    .map { (id, rows) -> GalleryAlbum(id, rows.first().albumName, rows.first().uri, rows.size) }
    .sortedBy { it.name.lowercase() }

internal fun toggleGallerySelection(selected: List<String>, uri: String, cap: Int): List<String> = when {
    uri in selected -> selected - uri
    selected.size >= cap -> selected
    else -> selected + uri
}

private sealed interface GalleryLoadState {
    data object Loading : GalleryLoadState
    data class Loaded(val photos: List<GalleryPhoto>) : GalleryLoadState
    data class Failed(val cause: String?) : GalleryLoadState
}

/** Full-screen, gesture-stable replacement for the system picker's dismissible bottom sheet. */
@Composable
internal fun PhotoGalleryDialog(
    selectionCap: Int,
    accessRevision: Int,
    permissionDenied: Boolean,
    limitedAccess: Boolean,
    onCancel: () -> Unit,
    onConfirm: (List<Uri>) -> Unit,
    onRequestAccess: () -> Unit,
    onOpenSettings: () -> Unit,
    onChooseFiles: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (permissionDenied) {
                GalleryPermissionState(onCancel, onRequestAccess, onOpenSettings, onChooseFiles)
            } else {
                GalleryContent(selectionCap, accessRevision, limitedAccess, onCancel, onConfirm, onRequestAccess)
            }
        }
    }
}

@Composable
internal fun GalleryPermissionState(
    onCancel: () -> Unit,
    onRequestAccess: () -> Unit,
    onOpenSettings: () -> Unit,
    onChooseFiles: () -> Unit,
) {
    val language = LocalAppLanguage.current
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        GalleryTopBar(
            title = localized(language, "选择照片", "Choose photos"),
            albumsOpen = false,
            onClose = onCancel,
            onToggleAlbums = null,
        )
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                localized(language, "需要照片访问权限（HR-PERM-004）", "Photo access is required (HR-PERM-004)"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                localized(language, "允许全部或部分照片后即可在这里多选；也可以改用手机文件。", "Allow all or selected photos to use this gallery, or choose Files instead."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onRequestAccess, modifier = Modifier.padding(top = 20.dp)) {
                Text(localized(language, "允许访问", "Allow access"))
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 8.dp)) {
                Text(localized(language, "打开系统设置", "Open settings"))
            }
            OutlinedButton(onClick = onChooseFiles, modifier = Modifier.padding(top = 8.dp)) {
                Text(localized(language, "改用手机文件", "Choose from Files"))
            }
        }
    }
}

@Composable
private fun GalleryContent(
    selectionCap: Int,
    accessRevision: Int,
    limitedAccess: Boolean,
    onCancel: () -> Unit,
    onConfirm: (List<Uri>) -> Unit,
    onRequestAccess: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = LocalAppLanguage.current
    var reload by remember { mutableStateOf(0) }
    val loaded by produceState<GalleryLoadState>(GalleryLoadState.Loading, reload, accessRevision) {
        value = withContext(Dispatchers.IO) {
            runCatching { GalleryLoadState.Loaded(queryGalleryPhotos(context)) }
                .getOrElse { GalleryLoadState.Failed(it.message) }
        }
    }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var albumsMode by rememberSaveable { mutableStateOf(false) }
    var albumId by rememberSaveable { mutableStateOf<String?>(null) }
    var previewUri by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler {
        when {
            previewUri != null -> previewUri = null
            albumsMode -> albumsMode = false
            else -> onCancel()
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        val currentAlbumName = (loaded as? GalleryLoadState.Loaded)?.photos
            ?.firstOrNull { it.albumId == albumId }?.albumName
        GalleryTopBar(
            title = currentAlbumName?.ifBlank { null }
                ?: localized(language, "所有照片", "All photos"),
            albumsOpen = albumsMode,
            onClose = onCancel,
            onToggleAlbums = { albumsMode = !albumsMode },
        )
        if (limitedAccess) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onRequestAccess) {
                    Text(localized(language, "选择更多", "Select more"))
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when (val state = loaded) {
                GalleryLoadState.Loading -> LoadingState(Modifier.align(Alignment.Center))
                is GalleryLoadState.Failed -> ErrorState(
                    error = AppError(AppErrorCode.GALLERY_READ_FAILED, true, state.cause, "gallery_query"),
                    onRetry = { reload++ },
                )
                is GalleryLoadState.Loaded -> {
                    val photos = if (albumId == null) state.photos else state.photos.filter { it.albumId == albumId }
                    when {
                        state.photos.isEmpty() -> EmptyState(
                            title = localized(language, "没有可用照片", "No photos available"),
                            subtitle = if (limitedAccess) localized(language, "点“选择更多”添加可访问的照片。", "Use Select more to add accessible photos.") else null,
                        )
                        albumsMode -> AlbumList(
                            albums = galleryAlbums(state.photos),
                            totalCount = state.photos.size,
                            allPhotosCover = state.photos.first().uri,
                            onOpen = {
                                albumId = it
                                albumsMode = false
                            },
                        )
                        else -> PhotoGrid(photos, selected, selectionCap) { uri ->
                            selected = toggleGallerySelection(selected, uri, selectionCap)
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        if (selected.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(selected, key = { it }) { uri ->
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).clickable { previewUri = uri }) {
                        GalleryThumbnail(uri, Modifier.fillMaxSize())
                        Text(
                            "${selected.indexOf(uri) + 1}",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                localized(language, "已选 ${selected.size}/$selectionCap", "${selected.size}/$selectionCap selected"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onConfirm(selected.map(Uri::parse)) }, enabled = selected.isNotEmpty()) {
                Text(localized(language, "添加", "Add"))
            }
        }
    }
    previewUri?.let { uri ->
        Surface(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black), color = androidx.compose.ui.graphics.Color.Black) {
            Box(Modifier.fillMaxSize().clickable { previewUri = null }) {
                GalleryThumbnail(uri, Modifier.fillMaxSize(), requestedPx = 2048, contentScale = ContentScale.Fit)
                IconButton(onClick = { previewUri = null }, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding()) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, localized(language, "返回", "Back"), tint = androidx.compose.ui.graphics.Color.White)
                }
                OutlinedButton(
                    onClick = { selected = toggleGallerySelection(selected, uri, selectionCap) },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp),
                ) {
                    Text(if (uri in selected) localized(language, "取消选择", "Deselect") else localized(language, "选择", "Select"))
                }
            }
        }
    }
}

@Composable
private fun GalleryTopBar(
    title: String,
    albumsOpen: Boolean,
    onClose: () -> Unit,
    onToggleAlbums: (() -> Unit)?,
) {
    val language = LocalAppLanguage.current
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.Rounded.Close, localized(language, "关闭", "Close"))
        }
        Row(
            Modifier.align(Alignment.Center)
                .then(if (onToggleAlbums != null) Modifier.clickable(onClick = onToggleAlbums) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onToggleAlbums != null) {
                Icon(
                    Icons.Rounded.ArrowDropDown,
                    contentDescription = if (albumsOpen) localized(language, "收起相册", "Hide albums")
                    else localized(language, "选择相册", "Choose album"),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun PhotoGrid(photos: List<GalleryPhoto>, selected: List<String>, cap: Int, onToggle: (String) -> Unit) {
    val density = LocalDensity.current
    val tilePx = with(density) {
        (LocalConfiguration.current.screenWidthDp.dp / 4).roundToPx()
    }.coerceAtLeast(1)
    LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(photos, key = { it.uri }) { photo ->
            val order = selected.indexOf(photo.uri)
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clickable(enabled = order >= 0 || selected.size < cap) { onToggle(photo.uri) }) {
                GalleryThumbnail(photo.uri, Modifier.fillMaxSize(), requestedPx = tilePx)
                if (order >= 0) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)))
                    Text(
                        "${order + 1}",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    ) // l10n-allow: selection ordinal
                }
            }
        }
    }
}

@Composable
private fun AlbumList(
    albums: List<GalleryAlbum>,
    totalCount: Int,
    allPhotosCover: String,
    onOpen: (String?) -> Unit,
) {
    val language = LocalAppLanguage.current
    val rows = listOf(GalleryAlbum("", localized(language, "所有照片", "All photos"), allPhotosCover, totalCount)) + albums
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(rows, key = { "album:${it.id}" }) { album ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(album.id.ifBlank { null }) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GalleryThumbnail(
                    album.coverUri,
                    Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                    requestedPx = 256,
                )
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        album.name.ifBlank { localized(language, "图片", "Pictures") },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${album.count}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) // l10n-allow: album item count
                }
                Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GalleryThumbnail(
    uri: String,
    modifier: Modifier,
    requestedPx: Int = 320,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
    val crop = contentScale == ContentScale.Crop
    val cacheKey = "$uri#$requestedPx#$crop"
    val bitmap by produceState<Bitmap?>(GalleryBitmapCache.get(cacheKey), uri, requestedPx, crop) {
        value = withContext(Dispatchers.IO) {
            GalleryBitmapCache.get(cacheKey) ?: decodeGalleryBitmap(resolver, Uri.parse(uri), requestedPx, crop)
                ?.also { GalleryBitmapCache.put(cacheKey, it) }
        }
    }
    val image = bitmap
    if (image == null) Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    else Image(image.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = contentScale)
}

private object GalleryBitmapCache : LruCache<String, Bitmap>(24 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
}

internal fun gallerySampleSize(width: Int, height: Int, requestedPx: Int, crop: Boolean = true): Int {
    if (width <= 0 || height <= 0 || requestedPx <= 0) return 1
    var sample = 1
    val edge = if (crop) minOf(width, height) else maxOf(width, height)
    while (edge / (sample * 2) >= requestedPx) sample *= 2
    return sample
}

internal fun galleryTargetSize(width: Int, height: Int, requestedPx: Int): Pair<Int, Int> {
    val longEdge = maxOf(width, height)
    if (width <= 0 || height <= 0 || requestedPx <= 0 || longEdge <= requestedPx) return width to height
    val scale = requestedPx.toFloat() / longEdge.toFloat()
    return (width * scale).roundToInt().coerceAtLeast(1) to
        (height * scale).roundToInt().coerceAtLeast(1)
}

private fun decodeGalleryBitmap(
    resolver: android.content.ContentResolver,
    uri: Uri,
    requestedPx: Int,
    crop: Boolean,
): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(resolver, uri)
        return@runCatching ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            if (crop) {
                val edge = minOf(info.size.width, info.size.height)
                val left = (info.size.width - edge) / 2
                val top = (info.size.height - edge) / 2
                decoder.setCrop(android.graphics.Rect(left, top, left + edge, top + edge))
                val target = minOf(requestedPx, edge).coerceAtLeast(1)
                decoder.setTargetSize(target, target)
            } else {
                val (width, height) = galleryTargetSize(info.size.width, info.size.height, requestedPx)
                decoder.setTargetSize(width, height)
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(
            it,
            null,
            BitmapFactory.Options().apply {
                inSampleSize = gallerySampleSize(bounds.outWidth, bounds.outHeight, requestedPx, crop)
            },
        )
    } ?: return@runCatching null
    val orientation = resolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                setRotate(180f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) {
        decoded
    } else Bitmap.createBitmap(
        decoded,
        0,
        0,
        decoded.width,
        decoded.height,
        matrix,
        true,
    ).also { if (it !== decoded) decoded.recycle() }
}.getOrNull()

private fun queryGalleryPhotos(context: Context): List<GalleryPhoto> {
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.BUCKET_ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_ADDED,
    )
    val result = mutableListOf<GalleryPhoto>()
    context.contentResolver.query(collection, projection, null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
        val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val name = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val bucketId = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
        val bucketName = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val taken = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
        val added = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        while (cursor.moveToNext()) {
            val mediaId = cursor.getLong(id)
            result += GalleryPhoto(
                uri = Uri.withAppendedPath(collection, mediaId.toString()).toString(),
                name = cursor.getString(name).orEmpty(),
                albumId = cursor.getString(bucketId).orEmpty(),
                albumName = cursor.getString(bucketName).orEmpty(),
                takenAt = cursor.getLong(taken).takeIf { it > 0 } ?: cursor.getLong(added) * 1000L,
            )
        }
    }
    return result
}
