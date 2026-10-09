package com.hermes.client.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun SessionPaneIcon(label: String) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(22.dp).semantics { contentDescription = label }) {
        drawRoundRect(color, topLeft = Offset(size.width * .1f, size.height * .16f), size = androidx.compose.ui.geometry.Size(size.width * .8f, size.height * .68f), cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(1.7.dp.toPx()))
        drawLine(color, Offset(size.width * .4f, size.height * .16f), Offset(size.width * .4f, size.height * .84f), strokeWidth = 1.7.dp.toPx())
    }
}
