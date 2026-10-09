package com.hermes.client.ui.workspace

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private fun Context.activity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Composable
internal fun foldingFeature(): FoldingFeature? {
    val context = LocalContext.current
    val features = remember(context) {
        val activity = context.activity()
        if (activity == null) flowOf(null) else WindowInfoTracker.getOrCreate(activity)
            .windowLayoutInfo(activity).map { info ->
                info.displayFeatures.filterIsInstance<FoldingFeature>()
                    .firstOrNull { it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL }
            }.catch { emit(null) }
    }
    val feature by features.collectAsStateWithLifecycle(initialValue = null)
    return feature
}
