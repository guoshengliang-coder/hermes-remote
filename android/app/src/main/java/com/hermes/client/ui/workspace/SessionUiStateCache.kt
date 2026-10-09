package com.hermes.client.ui.workspace

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*

/**
 * Per-conversation UI state survives canonical navigation without retaining a ViewModel/socket.
 * Unlike SaveableStateHolder, overlapping exit/enter animations may lease the same key: the newest
 * screen snapshots the live predecessor, and a late disposal cannot overwrite that newer state.
 */
class SessionUiStateCache(saved: Map<String, Map<String, List<Any?>>> = emptyMap()) {
    private val saved = saved.toMutableMap()
    private val active = mutableMapOf<String, SaveableStateRegistry>()
    private val leaving = mutableSetOf<SaveableStateRegistry>()

    fun acquire(key: String, canSave: (Any) -> Boolean): SaveableStateRegistry {
        val values = active[key]?.takeUnless { it in leaving }?.performSave() ?: saved[key]
        return SaveableStateRegistry(values, canSave).also { active[key] = it }
    }

    fun release(key: String, registry: SaveableStateRegistry) {
        val wasLeaving = leaving.remove(registry)
        if (active[key] !== registry) return
        if (!wasLeaving) saved[key] = registry.performSave()
        active.remove(key)
    }

    /** Capture before Navigation changes destination state, rather than after its exit animation. */
    fun captureBeforeNavigation() {
        active.forEach { (key, registry) ->
            if (leaving.add(registry)) saved[key] = registry.performSave()
        }
    }

    fun snapshot(): Map<String, Map<String, List<Any?>>> = saved + active.filterValues { it !in leaving }.mapValues { it.value.performSave() }

    companion object {
        val Saver = Saver<SessionUiStateCache, HashMap<String, Map<String, List<Any?>>>>(
            save = { HashMap(it.snapshot()) }, restore = { SessionUiStateCache(it) },
        )
    }
}

@Composable
fun SessionUiStateCache.SessionState(key: String, content: @Composable () -> Unit) {
    androidx.compose.runtime.key(key) {
        val parent = LocalSaveableStateRegistry.current
        val registry = remember(this, key) { acquire(key) { parent?.canBeSaved(it) ?: true } }
        CompositionLocalProvider(LocalSaveableStateRegistry provides registry, content = content)
        // Registered AFTER the content, so disposal snapshots before its rememberSaveable providers
        // unregister. Saving after child disposal produces an empty registry and loses the anchor.
        DisposableEffect(this, key, registry) { onDispose { release(key, registry) } }
    }
}
