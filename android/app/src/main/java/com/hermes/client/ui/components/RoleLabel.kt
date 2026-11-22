package com.hermes.client.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

val LocalRoleLabels = compositionLocalOf<Map<String, String>> { emptyMap() }

@Composable
fun roleLabel(id: String): String {
    val aliases = LocalProfileIdentities.current
    val labels = LocalRoleLabels.current
    fun label(key: String) = aliases[key]?.displayName ?: labels[key] ?: key
    val name = label(id)
    return if (labels.keys.any { it != id && label(it) == name }) "$name · $id" else name
}
