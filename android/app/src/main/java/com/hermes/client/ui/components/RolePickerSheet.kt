package com.hermes.client.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import com.hermes.client.ui.localization.l10n

val RoleFilterIcon: ImageVector by lazy {
    ImageVector.Builder("RoleFilter", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = androidx.compose.ui.graphics.SolidColor(Color.Black), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4f, 6f); lineTo(20f, 6f)
            moveTo(7f, 12f); lineTo(17f, 12f)
            moveTo(10f, 18f); lineTo(14f, 18f)
        }
    }.build()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RolePickerSheet(names: List<String>, title: String, selected: Set<String>, multiple: Boolean = false,
    onAll: (() -> Unit)? = null, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = hermesSheetState()) {
        Text(title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
        LazyColumn {
            if (onAll != null) item {
                TextButton(onClick = onAll, modifier = Modifier.padding(horizontal = 16.dp)) { Text(l10n("全部角色", "All roles")) }
            }
            items(names, key = { it }) { name ->
                ListItem(headlineContent = { Text(roleLabel(name)) }, leadingContent = { ProfileAvatar(name, size = 36.dp) },
                    trailingContent = {
                        if (multiple) Checkbox(checked = name in selected, onCheckedChange = null)
                        else if (name in selected) Icon(Icons.Rounded.Check, l10n("已选择", "Selected"))
                    }, modifier = Modifier.clickable { onPick(name) })
            }
        }
    }
}
