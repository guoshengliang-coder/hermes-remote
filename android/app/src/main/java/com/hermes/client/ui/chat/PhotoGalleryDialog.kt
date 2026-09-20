package com.hermes.client.ui.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
        GalleryTopBar(localized(language, "选择照片", "Choose photos"), onCancel)
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

    BackHandler { if (previewUri != null) previewUri = null else onCancel() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        GalleryTopBar(
            when {
                albumId != null -> (loaded as? GalleryLoadState.Loaded)?.photos?.firstOrNull { it.albumId == albumId }?.albumName.orEmpty()
                else -> localized(language, "选择照片", "Choose photos")
            },
            onBack = { if (albumId != null) albumId = null else onCancel() },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !albumsMode,
                onClick = { albumsMode = false; albumId = null },
                label = { Text(localized(language, "最近", "Recent")) },
            )
            FilterChip(
                selected = albumsMode,
                onClick = { albumsMode = true; albumId = null },
                label = { Text(localized(language, "相册", "Albums")) },
            )
            Spacer(Modifier.weight(1f))
            if (limitedAccess) {
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
                        albumsMode && albumId == null -> AlbumGrid(galleryAlbums(state.photos)) { albumId = it }
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
private fun GalleryTopBar(title: String, onBack: () -> Unit) {
    val language = LocalAppLanguage.current
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, localized(language, "返回", "Back"))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun PhotoGrid(photos: List<GalleryPhoto>, selected: List<String>, cap: Int, onToggle: (String) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(photos, key = { it.uri }) { photo ->
            val order = selected.indexOf(photo.uri)
            Box(Modifier.fillMaxWidth().height(126.dp).clickable(enabled = order >= 0 || selected.size < cap) { onToggle(photo.uri) }) {
                GalleryThumbnail(photo.uri, Modifier.fillMaxSize())
                if (order >= 0) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)))
                    Text("${order + 1}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 7.dp, vertical = 3.dp)) // l10n-allow: selection ordinal
                }
            }
        }
    }
}

@Composable
private fun AlbumGrid(albums: List<GalleryAlbum>, onOpen: (String) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(albums, key = { it.id }) { album ->
            Column(Modifier.clickable { onOpen(album.id) }) {
                GalleryThumbnail(album.coverUri, Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(album.name.ifBlank { localized(LocalAppLanguage.current, "图片", "Pictures") }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${album.count}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) // l10n-allow: album item count
                }
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
    val bitmap by produceState<ImageBitmap?>(null, uri, requestedPx) {
        value = withContext(Dispatchers.IO) { decodeGalleryBitmap(resolver, Uri.parse(uri), requestedPx) }
    }
    val image = bitmap
    if (image == null) Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    else Image(image, contentDescription = null, modifier = modifier, contentScale = contentScale)
}

private fun decodeGalleryBitmap(resolver: android.content.ContentResolver, uri: Uri, requestedPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > requestedPx) sample *= 2
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }?.asImageBitmap()
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
