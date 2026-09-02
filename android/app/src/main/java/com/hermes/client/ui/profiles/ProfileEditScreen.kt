package com.hermes.client.ui.profiles

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.media.AvatarPhotoImporter
import com.hermes.client.data.repository.AvatarStyle
import com.hermes.client.data.repository.ProfileIdentity
import com.hermes.client.data.repository.ProfileIdentityStore
import com.hermes.client.ui.components.CameraStrokeIcon
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.components.ProfileAvatar
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.localization.localizedMessage
import com.hermes.client.ui.theme.AVATAR_SWATCHES
import com.hermes.client.ui.theme.avatarColorArgb
import com.hermes.client.ui.theme.avatarColorForHue
import com.hermes.client.ui.theme.avatarHueOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Identity settings for one profile. Everything on the page is a DRAFT previewed live on the
 * big avatar; only 保存 writes it (explicit save, like the cron editor). Leaving with a dirty
 * draft asks first. Photos are imported to disk as soon as they are picked so the preview is
 * real; files the user never saved are deleted on discard.
 */
@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val store: ProfileIdentityStore,
    private val importer: AvatarPhotoImporter,
    /** File deletes run here (any off-main dispatcher will do; tests inject theirs). */
    @com.hermes.client.di.DefaultDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    val profile: String = savedState.get<String>("profile").orEmpty()

    data class Draft(
        val displayName: String = "",
        val avatarFile: String? = null,
        val colorArgb: Int? = null,
        val style: AvatarStyle = AvatarStyle.SOLID,
    ) {
        fun toIdentity(): ProfileIdentity = ProfileIdentity(
            displayName = ProfileIdentity.normalizeDisplayName(displayName),
            avatarFile = avatarFile,
            colorArgb = colorArgb,
            style = style,
        )

        companion object {
            fun of(identity: ProfileIdentity) = Draft(
                displayName = identity.displayName.orEmpty(),
                avatarFile = identity.avatarFile,
                colorArgb = identity.colorArgb,
                style = identity.style,
            )
        }
    }

    data class State(
        val saved: ProfileIdentity = ProfileIdentity.DEFAULT,
        val draft: Draft = Draft(),
        val loaded: Boolean = false,
        val importing: Boolean = false,
        val saving: Boolean = false,
        val error: AppError? = null,
    ) {
        /** What the big avatar previews and what 保存 would write. */
        val preview: ProfileIdentity get() = draft.toIdentity()
        val dirty: Boolean get() = loaded && preview != saved.copy(updatedAt = 0L)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Photo files imported in this session and not (yet) saved. */
    private val pending = LinkedHashSet<String>()

    init {
        viewModelScope.launch {
            val saved = store.get(profile)
            _state.update { it.copy(saved = saved, draft = Draft.of(saved), loaded = true) }
        }
    }

    fun setDisplayName(value: String) = edit { it.copy(displayName = value.take(ProfileIdentity.MAX_DISPLAY_NAME_LENGTH)) }
    fun setColor(argb: Int?) = edit { it.copy(colorArgb = argb) }
    fun setHue(hue: Float) = setColor(avatarColorForHue(hue))
    fun setStyle(style: AvatarStyle) = edit { it.copy(style = style) }
    fun removePhoto() = edit { it.copy(avatarFile = null) }
    fun resetToDefault() = edit { Draft() }
    fun clearError() = _state.update { it.copy(error = null) }

    fun importPhoto(uri: Uri) {
        if (_state.value.importing) return
        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            importer.import(uri, profile)
                .onSuccess { name ->
                    pending += name
                    _state.update { it.copy(importing = false, draft = it.draft.copy(avatarFile = name)) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            importing = false,
                            error = AppError(AppErrorCode.AVATAR_PHOTO_FAILED, retryable = true, technicalCause = e.toString(), stage = "avatar_import"),
                        )
                    }
                }
        }
    }

    /** Persists the draft; pops back only on success. Old/unsaved photo files are removed. */
    fun save(onDone: (Boolean) -> Unit) {
        val s = _state.value
        if (s.saving || !s.loaded) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val next = s.preview
            val result = runCatching { store.save(profile, next) }
            result.onSuccess {
                val keep = next.avatarFile
                val stale = (pending + listOfNotNull(s.saved.avatarFile)).filter { it != keep }
                pending.clear()
                deleteFiles(stale)
                _state.update { it.copy(saving = false, saved = next.copy(updatedAt = 0L)) }
                onDone(true)
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        saving = false,
                        error = AppError(AppErrorCode.PROFILE_IDENTITY_SAVE_FAILED, retryable = true, technicalCause = e.toString(), stage = "identity_save"),
                    )
                }
                onDone(false)
            }
        }
    }

    /** Drops the draft's unsaved photo files. Called on an explicit discard and when leaving. */
    fun discard() {
        if (pending.isEmpty()) return
        val files = pending.toList()
        pending.clear()
        viewModelScope.launch { deleteFiles(files) }
    }

    private suspend fun deleteFiles(names: List<String>) = withContext(io) {
        names.forEach { runCatching { File(store.avatarDir, it).delete() } }
    }

    private inline fun edit(block: (Draft) -> Draft) = _state.update { it.copy(draft = block(it.draft)) }

    override fun onCleared() {
        // A ViewModel cleared with unsaved imports (process kept, screen gone) must not leak files.
        if (pending.isNotEmpty()) {
            val dir = store.avatarDir
            pending.forEach { runCatching { File(dir, it).delete() } }
            pending.clear()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    onBack: () -> Unit,
    vm: ProfileEditViewModel = hiltViewModel(),
) {
    val language = LocalAppLanguage.current
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val draft = state.draft
    val preview = state.preview
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var discardDialog by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::importPhoto)
    }
    val pickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val leave: () -> Unit = {
        if (state.dirty) discardDialog = true else { vm.discard(); onBack() }
    }
    BackHandler(onBack = leave)

    LaunchedEffect(state.error) {
        state.error?.let {
            Toast.makeText(context, it.localizedMessage(language), Toast.LENGTH_SHORT).show()
            vm.clearError()
        }
    }

    Scaffold(
        // Primary action pinned to the bottom (docs/DESIGN.md §5.12): 52dp, 16dp sides, 16dp above
        // the navigation bar, rides up with the keyboard; content scrolls underneath.
        bottomBar = {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
                Button(
                    onClick = { vm.save { ok -> if (ok) onBack() } },
                    enabled = state.dirty && !state.saving && !state.importing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 16.dp)
                        .height(52.dp),
                ) {
                    Text(localized(language, "保存", "Save"), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        topBar = {
            HermesTopBar(
                title = localized(language, "身份设置", "Profile settings"),
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = localized(language, "返回", "Back"))
                    }
                },
                actions = {
                    // Resets the DRAFT only; 保存 still has to confirm it.
                    TextButton(onClick = vm::resetToDefault, enabled = !preview.isDefault) {
                        Text(localized(language, "恢复默认", "Reset"), color = if (preview.isDefault) muted.copy(alpha = 0.38f) else muted)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            // ── Avatar preview + photo actions ──────────────────────────────────────
            Column(
                Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(96.dp)) {
                    ProfileAvatar(vm.profile, size = 96.dp, identity = preview)
                    if (state.importing) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), strokeWidth = 3.dp)
                    }
                    Surface(
                        onClick = pickPhoto,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
                        modifier = Modifier.align(Alignment.BottomEnd).size(32.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                CameraStrokeIcon,
                                contentDescription = localized(language, "更换照片", "Change photo"),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = pickPhoto) {
                        Text(if (draft.avatarFile != null) localized(language, "更换照片", "Change photo") else localized(language, "选择照片", "Choose photo"))
                    }
                    if (draft.avatarFile != null) {
                        TextButton(onClick = vm::removePhoto) {
                            Text(localized(language, "移除照片", "Remove photo"), color = muted)
                        }
                    }
                }
            }

            // ── Display name: placeholder IS the profile name; clearing = back to it ─
            // Section header instead of a floating label: M3 shows a label INSIDE an empty,
            // unfocused field and only reveals the placeholder on focus, which hid the profile
            // name until the user tapped in (docs/DESIGN.md §5.10, decision 2026-09-02).
            Text(
                localized(language, "显示名", "Display name"),
                style = MaterialTheme.typography.titleSmall,
                color = muted,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp),
            )
            OutlinedTextField(
                value = draft.displayName,
                onValueChange = vm::setDisplayName,
                placeholder = { Text(vm.profile, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                trailingIcon = if (draft.displayName.isNotEmpty()) {
                    {
                        IconButton(onClick = { vm.setDisplayName("") }) {
                            Icon(Icons.Rounded.Clear, contentDescription = localized(language, "清除", "Clear"))
                        }
                    }
                } else null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
            )

            // ── Hermes profile (read-only, no chevron) ───────────────────────────────
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                Text("Hermes profile", style = MaterialTheme.typography.bodySmall, color = muted) // l10n-allow: product noun, identical in both languages
                Text(vm.profile, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            // ── Avatar colour (+ style when the avatar is lettered) ──────────────────
            Text(
                localized(language, "头像颜色", "Avatar colour"),
                style = MaterialTheme.typography.titleSmall,
                color = muted,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
            if (draft.avatarFile != null) {
                Text(
                    localized(language, "已设照片时只用于通知强调色", "With a photo set, the colour only accents notifications"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                )
            } else {
                val styles = listOf(
                    AvatarStyle.SOLID to localized(language, "实心", "Solid"),
                    AvatarStyle.OUTLINE to localized(language, "空心", "Outline"),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp)) {
                    styles.forEachIndexed { i, (style, label) ->
                        SegmentedButton(
                            selected = draft.style == style,
                            onClick = { vm.setStyle(style) },
                            shape = SegmentedButtonDefaults.itemShape(i, styles.size),
                            // No check glyph (house rule): selection reads from the fill alone.
                            icon = {},
                        ) { Text(label) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // Swatches: the profile's DEFAULT colour leads (selected when nothing is chosen),
            // then the curated set. No "automatic" wording — the default is just the first circle.
            val defaultArgb = avatarColorArgb(vm.profile)
            val currentArgb = draft.colorArgb ?: defaultArgb
            val cells = listOf(defaultArgb) + AVATAR_SWATCHES
            cells.withIndex().chunked(7).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    row.forEach { (index, argb) ->
                        val isDefaultCell = index == 0
                        val selected = if (isDefaultCell) draft.colorArgb == null else draft.colorArgb == argb
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(Color(argb))
                                .clickable { vm.setColor(if (isDefaultCell) null else argb) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    repeat(7 - row.size) { Spacer(Modifier.size(40.dp)) }
                }
            }

            // Hue slider: saturation and lightness stay locked (white initials keep their
            // contrast on every hue); the thumb rides a gradient of the reachable colours.
            val hueStops = remember { (0..360 step 30).map { Color(avatarColorForHue(it.toFloat())) } }
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.horizontalGradient(hueStops)),
                )
                Slider(
                    value = avatarHueOf(currentArgb),
                    onValueChange = vm::setHue,
                    valueRange = 0f..360f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (discardDialog) {
        AlertDialog(
            onDismissRequest = { discardDialog = false },
            title = { Text(localized(language, "放弃更改？", "Discard changes?")) },
            text = { Text(localized(language, "头像和显示名的修改还没有保存。", "Your avatar and display name changes are not saved.")) },
            confirmButton = {
                TextButton(onClick = { discardDialog = false; vm.discard(); onBack() }) {
                    Text(localized(language, "放弃", "Discard"))
                }
            },
            dismissButton = {
                TextButton(onClick = { discardDialog = false }) {
                    Text(localized(language, "继续编辑", "Keep editing"))
                }
            },
        )
    }
}
