package com.hermes.client.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.components.SlideToConfirm
import com.hermes.client.ui.localization.l10n

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(req: ApprovalRequest, onRespond: (ApprovalChoice) -> Unit, onDismiss: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val tier = tierFor(req.allowPermanent)
    val error = MaterialTheme.colorScheme.error
    val badge = if (tier == ApprovalTier.ELEVATED) error else accent
    val label = req.patternKeys.firstOrNull()?.let { " · $it" } ?: ""
    val allowOnceText = l10n("仅允许一次", "Allow once")
    val allowRunText = l10n("本次运行允许", "Allow this run")
    val allowAlwaysText = l10n("始终允许", "Always allow")
    val denyText = l10n("拒绝", "Deny")
    val toggleScopeText = l10n("切换允许范围", "Toggle allow scope")
    val slideToAllowText = l10n("滑动以允许", "Slide to allow")

    // An approval must be an explicit choice: veto the Hidden transition so a swipe / scrim-tap
    // can't dismiss the sheet while pendingApproval is still set (which would desync — sheet gone
    // but state still blocked). The sheet leaves composition only when a choice clears the pending.
    val sheetState = com.hermes.client.ui.components.hermesSheetState(confirmValueChange = { it != SheetValue.Hidden })

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                (if (tier == ApprovalTier.ELEVATED) l10n("高风险审批", "Elevated approval") else l10n("需要审批", "Approval needed")) + label,
                style = MaterialTheme.typography.titleMedium,
                color = badge,
            )
            if (req.command.isNotBlank()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                    Text(req.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (req.description.isNotBlank()) {
                Text(req.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp))
            }

            if (req.patternKeys.isNotEmpty()) {
                Text(
                    l10n("授权范围：${req.patternKeys.joinToString(", ")}", "Grants: ${req.patternKeys.joinToString(", ")}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (req.smartDenied) {
                Text(
                    l10n("所有者已阻止自动执行——仅在确认本次操作安全时批准。", "Owner override — approve only this one operation."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (tier == ApprovalTier.STANDARD) {
                Button(
                    onClick = { onRespond(ApprovalChoice.ONCE) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .semantics { contentDescription = allowOnceText },
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = MaterialTheme.colorScheme.onPrimary),
                ) { Text(allowOnceText) }
                OutlinedButton(
                    onClick = { onRespond(ApprovalChoice.SESSION) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .semantics { contentDescription = allowRunText },
                ) { Text(allowRunText) }
                OutlinedButton(
                    onClick = { onRespond(ApprovalChoice.ALWAYS) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .semantics { contentDescription = allowAlwaysText },
                ) { Text(allowAlwaysText) }
                TextButton(
                    onClick = { onRespond(ApprovalChoice.DENY) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = denyText },
                ) { Text(denyText) }
            } else {
                // Elevated: Deny is prominent; Allow requires a deliberate slide.
                var thisRun by remember { mutableStateOf(false) }
                Button(
                    onClick = { onRespond(ApprovalChoice.DENY) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .semantics { contentDescription = denyText },
                    colors = ButtonDefaults.buttonColors(containerColor = error),
                ) { Text(denyText) }
                TextButton(
                    onClick = { thisRun = !thisRun },
                    modifier = Modifier.fillMaxWidth()
                        .semantics { contentDescription = toggleScopeText },
                ) {
                    Text(if (thisRun) l10n("范围：本次运行", "Scope: allow for this run") else l10n("范围：仅本次操作", "Scope: allow once"))
                }
                SlideToConfirm(
                    label = if (thisRun) l10n("  → 滑动以允许本次运行", "  → slide to allow this run") else l10n("  → 滑动以允许一次", "  → slide to allow once"),
                    accent = accent,
                    onConfirm = { onRespond(if (thisRun) ApprovalChoice.SESSION else ApprovalChoice.ONCE) },
                    modifier = Modifier.padding(vertical = 8.dp)
                        .semantics { contentDescription = slideToAllowText },
                )
            }
        }
    }
}
