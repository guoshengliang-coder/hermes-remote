package com.hermes.client.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/**
 * Structured decision card for clarify.request — the counterpart of the approval card.
 *
 * Renders ONE question at a time (batch requests advance per answer via [onAnswer]):
 * - single-select: tapping a choice submits it immediately
 * - multi-select: checkboxes + a confirm button (answer joins selections with ", ",
 *   which the upstream gateway parser accepts as a label list)
 * - the "Other" free-text path is always available and expands into a multiline field
 * - Skip is an EXPLICIT action ([onSkip], empty-answer semantics upstream); the dismiss
 *   gesture only collapses the sheet ([onCollapse]) and never submits anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClarifySheet(
    request: ClarifyRequest,
    onAnswer: (String) -> Unit,
    onSkip: () -> Unit,
    onCollapse: () -> Unit,
) {
    if (request.currentQuestion == null) return
    ModalBottomSheet(onDismissRequest = onCollapse) {
        ClarifySheetContent(request, onAnswer, onSkip)
    }
}

/** Sheet body without the ModalBottomSheet window — reused by screenshots and the gallery. */
@Composable
internal fun ClarifySheetContent(
    request: ClarifyRequest,
    onAnswer: (String) -> Unit,
    onSkip: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val question = request.currentQuestion ?: return
    run {
        // Reset per question so a batch's next question starts clean.
        var otherOpen by remember(request.requestId, question.qid) { mutableStateOf(false) }
        var otherText by remember(request.requestId, question.qid) { mutableStateOf("") }
        var checked by remember(request.requestId, question.qid) { mutableStateOf(setOf<Int>()) }
        var picked by remember(request.requestId, question.qid) { mutableStateOf(-1) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    localized(language, "HERMES 需要你决定", "HERMES NEEDS YOUR DECISION") +
                        if (question.multiSelect) localized(language, " · 可多选", " · MULTI-SELECT") else "",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.2.sp,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
                if (request.isBatch) {
                    Spacer(Modifier.weight(1f))
                    val answered = request.lockedAnswers.size
                    Text(
                        "${answered + 1} / ${request.questions.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Receipt of the previously answered batch question, so context isn't lost.
            if (request.isBatch && request.lockedAnswers.isNotEmpty()) {
                val lastQid = request.questions.lastOrNull { it.qid in request.lockedAnswers }
                if (lastQid != null) {
                    Row(
                        Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("✓ ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) // l10n-allow: checkmark glyph
                        Text(
                            "${lastQid.question} — ${request.lockedAnswers[lastQid.qid]}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            Text(
                question.question,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 10.dp, bottom = 14.dp),
            )

            if (otherOpen) {
                // Choices collapse to reminder chips; focus goes to the multiline field.
                if (question.choices.isNotEmpty()) {
                    Row(
                        Modifier.padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        question.choices.take(3).forEach { choice ->
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            ) {
                                Text(
                                    choice,
                                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = otherText,
                    onValueChange = { otherText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                    placeholder = { Text(localized(language, "输入你的回答…", "Type your answer…")) },
                    shape = RoundedCornerShape(14.dp),
                )
                Button(
                    onClick = { if (otherText.isNotBlank()) onAnswer(otherText.trim()) },
                    enabled = otherText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(46.dp),
                ) { Text(localized(language, "发送回答", "Send answer")) }
                TextButton(
                    onClick = { otherOpen = false },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(
                        if (question.choices.isEmpty()) localized(language, "收起", "Collapse")
                        else localized(language, "返回选项", "Back to choices"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                question.choices.forEachIndexed { index, choice ->
                    val selected = if (question.multiSelect) index in checked else index == picked
                    Surface(
                        onClick = {
                            if (question.multiSelect) {
                                checked = if (selected) checked - index else checked + index
                            } else {
                                // Two-step on purpose: tap selects, the confirm button submits.
                                // One-tap submit felt like the card vanished out from under the
                                // user's finger (device feedback) and left no room to reconsider.
                                picked = if (picked == index) -1 else index
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(
                            1.5.dp,
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (question.multiSelect) {
                                Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.size(10.dp))
                            } else {
                                RadioButton(selected = selected, onClick = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.size(10.dp))
                            }
                            Text(choice, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                }
                // The always-available free-text path (upstream's constant 5th "Other" option).
                Surface(
                    onClick = { otherOpen = true },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("✎  ", color = MaterialTheme.colorScheme.onSurfaceVariant) // l10n-allow: pencil glyph
                        Text(
                            if (question.choices.isEmpty()) localized(language, "输入回答", "Type an answer")
                            else localized(language, "其他（自行输入）", "Other (type your answer)"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (question.choices.isNotEmpty()) {
                    Button(
                        onClick = {
                            if (question.multiSelect) {
                                val labels = checked.sorted().map { question.choices[it] }
                                if (labels.isNotEmpty()) onAnswer(labels.joinToString(", "))
                            } else if (picked >= 0) {
                                onAnswer(question.choices[picked])
                            }
                        },
                        enabled = if (question.multiSelect) checked.isNotEmpty() else picked >= 0,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(46.dp),
                    ) {
                        Text(
                            if (question.multiSelect) {
                                localized(language, "确认选择（${checked.size} 项）", "Confirm (${checked.size})")
                            } else {
                                localized(language, "确认选择", "Confirm choice")
                            },
                        )
                    }
                }
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
                ) {
                    Text(
                        localized(language, "跳过，让 agent 自行判断", "Skip — let the agent decide"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.height(6.dp))
        }
    }
}
