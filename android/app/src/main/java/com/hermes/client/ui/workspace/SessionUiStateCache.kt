package com.hermes.client.ui.workspace

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*

/**
 * Per-conversation UI state survives canonical navigation without retaining a ViewModel/socket.
 * Unlike SaveableStateHolder, overlapping exit/enter animations may lease the same key: the newest
 * screen snapshots the live predecessor, and a late disposal cannot overwrite that newer state.
 *
 * The whole cache is one `rememberSaveable` value, so it rides the Activity's saved instance state
 * as a single Binder parcel. That parcel has a hard ceiling (~500KB shared async-transaction
 * buffer; HG-207 crashed at 556KB after months of accumulating every session ever visited), so the
 * cache evicts: least-recently-used sessions first, then any single session whose snapshot alone
 * is oversized. Evicted sessions simply re-open with default UI state — never worth a crash.
 */
class SessionUiStateCache(saved: Map<String, Map<String, List<Any?>>> = emptyMap()) {
    private val saved = HashMap<String, Map<String, List<Any?>>>()
    /** LRU order: first is least recently used. Keys exist here exactly while they exist somewhere. */
    private val order = ArrayDeque<String>()
    private val active = mutableMapOf<String, SaveableStateRegistry>()
    private val leaving = mutableSetOf<SaveableStateRegistry>()

    init {
        saved.forEach { (key, values) -> store(key, values) }
        trim()
    }

    fun acquire(key: String, canSave: (Any) -> Boolean): SaveableStateRegistry {
        touch(key)
        val values = active[key]?.takeUnless { it in leaving }?.performSave() ?: saved[key]
        return SaveableStateRegistry(values, canSave).also { active[key] = it }
    }

    fun release(key: String, registry: SaveableStateRegistry) {
        val wasLeaving = leaving.remove(registry)
        if (active[key] !== registry) return
        if (!wasLeaving) store(key, registry.performSave())
        active.remove(key)
        trim()
    }

    /** Capture before Navigation changes destination state, rather than after its exit animation. */
    fun captureBeforeNavigation() {
        active.forEach { (key, registry) ->
            if (leaving.add(registry)) store(key, registry.performSave())
        }
        trim()
    }

    fun snapshot(): Map<String, Map<String, List<Any?>>> {
        val merged = LinkedHashMap<String, Map<String, List<Any?>>>()
        for (key in order) {
            merged[key] = active[key]?.takeUnless { it in leaving }?.performSave() ?: saved[key] ?: continue
        }
        val kept = LinkedHashMap<String, Map<String, List<Any?>>>()
        var budget = MAX_SNAPSHOT_BYTES
        for (key in merged.keys.reversed()) { // newest first
            if (kept.size >= MAX_CACHED_SESSIONS) break
            val values = merged.getValue(key)
            val bytes = sessionBytes(values)
            if (bytes > MAX_SESSION_SNAPSHOT_BYTES || bytes > budget) continue
            budget -= bytes
            kept[key] = values
        }
        return kept
    }

    private fun store(key: String, values: Map<String, List<Any?>>) {
        saved[key] = values
        touch(key)
    }

    private fun touch(key: String) {
        order.remove(key)
        order.addLast(key)
    }

    private fun trim() {
        while (saved.isNotEmpty() && (saved.size > MAX_CACHED_SESSIONS || savedBytes() > MAX_SNAPSHOT_BYTES)) {
            val eldest = order.firstOrNull { it in saved } ?: break
            order.remove(eldest)
            saved.remove(eldest)
        }
    }

    private fun savedBytes(): Long = saved.values.sumOf { sessionBytes(it) }

    companion object {
        internal const val MAX_CACHED_SESSIONS = 16
        internal const val MAX_SESSION_SNAPSHOT_BYTES = 64L * 1024
        internal const val MAX_SNAPSHOT_BYTES = 96L * 1024

        private fun sessionBytes(values: Map<String, List<Any?>>): Long {
            var bytes = 48L
            for ((name, list) in values) {
                bytes += 16 + 2L * name.length
                list?.forEach { bytes += valueBytes(it) }
            }
            return bytes
        }

        // Upper-bound guess of the parcel cost; only the relative scale has to be right.
        private fun valueBytes(value: Any?): Long = when (value) {
            null -> 8L
            is String -> 24 + 2L * value.length
            is Boolean, is Int, is Float -> 8L
            is Long, is Double -> 12L
            is List<*> -> 16 + value.sumOf { valueBytes(it) }
            else -> 1_024L
        }

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
