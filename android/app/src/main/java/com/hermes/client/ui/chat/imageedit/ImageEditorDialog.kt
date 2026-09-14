package com.hermes.client.ui.chat.imageedit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.media.imageedit.BrushSize
import com.hermes.client.data.media.imageedit.CropAspect
import com.hermes.client.data.media.imageedit.CropBox
import com.hermes.client.data.media.imageedit.EditHistory
import com.hermes.client.data.media.imageedit.ImageEditBaker
import com.hermes.client.data.media.imageedit.ImageEditDocument
import com.hermes.client.data.media.imageedit.ImageEditOp
import com.hermes.client.data.media.imageedit.StrokeWeight
import com.hermes.client.data.media.imageedit.applyAspect
import com.hermes.client.data.media.imageedit.aspectRatioFor
import com.hermes.client.data.media.imageedit.commit
import com.hermes.client.data.media.imageedit.mosaicBlockPx
import com.hermes.client.data.media.imageedit.redo
import com.hermes.client.data.media.imageedit.rotateWithAspect
import com.hermes.client.data.media.imageedit.undo
import com.hermes.client.ui.components.RedoStrokeIcon
import com.hermes.client.ui.components.ResetStrokeIcon
import com.hermes.client.ui.components.RotateStrokeIcon
import com.hermes.client.ui.components.UndoStrokeIcon
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.InkColor
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the editor hands back. */
internal sealed interface ImageEditResult {
    /** The composited image. The caller encodes and re-stages it. */
    data class Edited(val bitmap: android.graphics.Bitmap) : ImageEditResult

    /** Nothing was changed, so the caller keeps the original bytes byte-for-byte. */
    data object Unchanged : ImageEditResult

    data class Failed(val error: AppError) : ImageEditResult
}

/**
 * The image editor: draw, redact, crop.
 *
 * Hosted in a `Dialog` rather than a nav destination because routes here carry only strings — a
 * bitmap or a six-megabyte array cannot travel through one — and because immersive mode needs the
 * surface to own its window: hiding the bars on the activity window does nothing while a dialog
 * holds focus.
 *
 * Non-destructive until Done. The edit is an op list, Cancel discards it, and undo walks all the way
 * back to the untouched image.
 */
@Composable
internal fun ImageEditorDialog(
    sourceBytes: ByteArray,
    onCancel: () -> Unit,
    onDone: (ImageEditResult) -> Unit,
) {
    Dialog(
        // Back and outside taps are routed through the discard guard instead, so unsaved strokes
        // are never dropped by a stray tap.
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            window ?: return@LaunchedEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        ImageEditorContent(sourceBytes, onCancel, onDone)
    }
}

@Composable
internal fun ImageEditorContent(
    sourceBytes: ByteArray,
    onCancel: () -> Unit,
    onDone: (ImageEditResult) -> Unit,
) {
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()

    val working by produceState<ImageBitmap?>(null, sourceBytes) {
        value = withContext(Dispatchers.IO) { decodeWorkingBitmap(sourceBytes)?.asImageBitmap() }
    }
    var reportedFailure by remember(sourceBytes) { mutableStateOf(false) }
    LaunchedEffect(working, sourceBytes) {
        if (working != null || reportedFailure) return@LaunchedEffect
        val undecodable = withContext(Dispatchers.IO) { decodeWorkingBitmap(sourceBytes) == null }
        if (undecodable) {
            reportedFailure = true
            onDone(ImageEditResult.Failed(AppError(AppErrorCode.IMAGE_DECODE_FAILED, retryable = true)))
        }
    }

    val bitmap = working
    if (bitmap == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            // Allowed over a photo surface by DESIGN.md §5.6: a single-colour brand mark has no
            // guaranteed contrast against an unpredictable backdrop.
            CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.dp, color = Color.White)
        }
        return
    }

    // The document survives rotation; the undo stack deliberately does not. See the Saver's note.
    var document by rememberSaveable(stateSaver = ImageEditDocumentSaver) {
        mutableStateOf(ImageEditDocument(bitmap.width, bitmap.height))
    }
    var past by remember { mutableStateOf(listOf<ImageEditDocument>()) }
    var future by remember { mutableStateOf(listOf<ImageEditDocument>()) }
    var toolOrdinal by rememberSaveable { mutableStateOf(EditorTool.DOODLE.ordinal) }
    var inkOrdinal by rememberSaveable { mutableStateOf(InkColor.RED.ordinal) }
    var weightOrdinal by rememberSaveable { mutableStateOf(StrokeWeight.MEDIUM.ordinal) }
    var brushOrdinal by rememberSaveable { mutableStateOf(BrushSize.MEDIUM.ordinal) }
    var aspectOrdinal by rememberSaveable { mutableStateOf(CropAspect.FREE.ordinal) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var cropDragBaseline by remember { mutableStateOf<ImageEditDocument?>(null) }

    val tool = EditorTool.entries[toolOrdinal]
    val ink = InkColor.entries[inkOrdinal]
    val weight = StrokeWeight.entries[weightOrdinal]
    val brush = BrushSize.entries[brushOrdinal]
    val aspect = CropAspect.entries[aspectOrdinal]

    fun history() = EditHistory(document, past, future)
    fun applyHistory(next: EditHistory) {
        document = next.present
        past = next.past
        future = next.future
    }
    // One commit per gesture, which is what makes one undo remove exactly one visible thing.
    fun commitDocument(next: ImageEditDocument) = applyHistory(history().commit(next))

    // A crop drag streams updates while the finger moves; history must only see where it started
    // and where it stopped.
    fun beginOrContinueCrop(next: CropBox) {
        if (cropDragBaseline == null) cropDragBaseline = document
        document = document.copy(crop = next)
    }
    fun settleCrop() {
        val baseline = cropDragBaseline ?: return
        cropDragBaseline = null
        if (baseline.effectiveCrop == document.effectiveCrop && baseline.quarterTurns == document.quarterTurns) {
            // The drag was clamped against an edge and moved nothing; leave no undo step behind.
            document = baseline
            return
        }
        applyHistory(EditHistory(baseline, past, future).commit(document))
    }

    val state = ImageEditorState(history(), tool, ink, weight, brush, aspect)

    // Built lazily and only once a mosaic is in play: it costs as much memory as the image itself.
    val needsPixelated = tool == EditorTool.MOSAIC || document.ops.any { it is ImageEditOp.Mosaic }
    val pixelated by produceState<ImageBitmap?>(null, bitmap, needsPixelated) {
        value = if (!needsPixelated) null else withContext(Dispatchers.IO) {
            runCatching {
                ImageEditBaker
                    .pixelate(bitmap.asAndroidBitmap(), mosaicBlockPx(max(bitmap.width, bitmap.height)))
                    .asImageBitmap()
            }.getOrNull()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ImageEditCanvas(
            working = bitmap,
            pixelated = pixelated,
            state = state,
            onCommit = ::commitDocument,
            onCropChange = ::beginOrContinueCrop,
            onCropSettled = ::settleCrop,
            modifier = Modifier.fillMaxSize().padding(top = 100.dp, bottom = 156.dp),
        )

        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 18.dp, vertical = 30.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorTextButton(localized(language, "取消", "Cancel"), enabled = !saving) {
                if (document.isUntouched) onCancel() else confirmDiscard = true
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorActionButton(
                    icon = UndoStrokeIcon,
                    contentDescription = localized(language, "撤销", "Undo"),
                    enabled = past.isNotEmpty() && !saving,
                    onClick = { applyHistory(history().undo()) },
                )
                EditorActionButton(
                    icon = RedoStrokeIcon,
                    contentDescription = localized(language, "重做", "Redo"),
                    enabled = future.isNotEmpty() && !saving,
                    onClick = { applyHistory(history().redo()) },
                )
            }
            EditorTextButton(localized(language, "完成", "Done"), enabled = !saving) {
                // Guarded so a double tap cannot produce two attachments.
                saving = true
                scope.launch { onDone(bakeResult(bitmap, document)) }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 14.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (tool) {
                EditorTool.DOODLE -> DoodleOptions(
                    ink = ink,
                    weight = weight,
                    onInk = { inkOrdinal = it.ordinal },
                    onWeight = { weightOrdinal = it.ordinal },
                )
                EditorTool.MOSAIC -> MosaicOptions(brush = brush, onBrush = { brushOrdinal = it.ordinal })
                EditorTool.CROP -> CropControls(
                    aspect = aspect,
                    canReset = document.crop != null || document.quarterTurns != 0,
                    onAspect = { next ->
                        aspectOrdinal = next.ordinal
                        val ratio = aspectRatioFor(next, bitmap.width, bitmap.height, document.quarterTurns)
                        if (ratio != null) {
                            commitDocument(
                                document.copy(
                                    crop = applyAspect(document.effectiveCrop, ratio, bitmap.width, bitmap.height),
                                ),
                            )
                        }
                    },
                    onRotate = {
                        val turned = document.rotatedQuarter()
                        val ratio = aspectRatioFor(aspect, bitmap.width, bitmap.height, document.quarterTurns)
                        commitDocument(
                            turned.copy(
                                crop = rotateWithAspect(turned.effectiveCrop, ratio, bitmap.width, bitmap.height),
                            ),
                        )
                    },
                    onReset = {
                        aspectOrdinal = CropAspect.FREE.ordinal
                        commitDocument(document.clearedCrop())
                    },
                )
            }
            ToolRow(tool = tool, onSelect = { toolOrdinal = it.ordinal })
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(localized(language, "放弃这些修改？", "Discard these edits?")) },
            text = {
                Text(
                    localized(
                        language,
                        "你在这张图片上的涂鸦、打码和裁切都会丢失。",
                        "Your drawing, redaction and cropping on this image will be lost.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onCancel() }) {
                    Text(localized(language, "放弃", "Discard"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(localized(language, "继续编辑", "Keep editing"))
                }
            },
        )
    }
}

@Composable
private fun CropControls(
    aspect: CropAspect,
    canReset: Boolean,
    onAspect: (CropAspect) -> Unit,
    onRotate: () -> Unit,
    onReset: () -> Unit,
) {
    val language = LocalAppLanguage.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CropOptions(aspect = aspect, onAspect = onAspect)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            EditorActionButton(
                icon = RotateStrokeIcon,
                contentDescription = localized(language, "旋转 90 度", "Rotate 90 degrees"),
                onClick = onRotate,
            )
            EditorActionButton(
                icon = ResetStrokeIcon,
                contentDescription = localized(language, "重置裁切", "Reset crop"),
                enabled = canReset,
                onClick = onReset,
            )
        }
    }
}

@Composable
private fun EditorTextButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(23.dp),
        color = EditorScrim,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * Decode at the working resolution, refusing an absurd image **before** allocating anything — the
 * same order the transcript exporter uses for its own budget.
 */
private fun decodeWorkingBitmap(bytes: ByteArray): android.graphics.Bitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (!ImageEditBaker.fitsBudget(bounds.outWidth, bounds.outHeight)) return@runCatching null
    val sample = ImageEditBaker.workingSampleSize(max(bounds.outWidth, bounds.outHeight))
    android.graphics.BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
    )
}.getOrNull()

private suspend fun bakeResult(working: ImageBitmap, document: ImageEditDocument): ImageEditResult {
    // Opening the editor and changing nothing must not re-encode, or "I only looked at it" silently
    // downscales a 4000px screenshot to 2560.
    if (document.isUntouched) return ImageEditResult.Unchanged
    return withContext(Dispatchers.IO) {
        runCatching {
            ImageEditResult.Edited(ImageEditBaker.bake(working.asAndroidBitmap(), document))
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            ImageEditResult.Failed(AppError(AppErrorCode.IMAGE_EDIT_SAVE_FAILED, retryable = true))
        }
    }
}
