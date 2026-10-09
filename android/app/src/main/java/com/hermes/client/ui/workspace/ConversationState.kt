package com.hermes.client.ui.workspace

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*

/** Session-owned state uses semantic names; Navigation changes generated hashes for each entry. */
@Composable
@Suppress("UNCHECKED_CAST")
fun <T : Any, S : Any> rememberConversationValue(name: String, saver: Saver<T, S>, initial: () -> T): T {
    val registry = LocalSaveableStateRegistry.current
    val value = remember(registry, name) { registry?.consumeRestored(name)?.let { runCatching { saver.restore(it as S) }.getOrNull() } ?: initial() }
    DisposableEffect(registry, name, value) {
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = registry?.canBeSaved(value) ?: true }
        val entry = registry?.registerProvider(name) { with(saver) { scope.save(value) } }
        onDispose { entry?.unregister() }
    }
    return value
}

@Composable
fun <T : Any> rememberConversationState(name: String, initial: () -> T): MutableState<T> =
    rememberConversationValue(name, Saver<MutableState<T>, T>(save = { it.value }, restore = { mutableStateOf(it) })) { mutableStateOf(initial()) }
