package com.hermes.client.data.diagnostics

import com.hermes.client.data.error.redactSecrets
import java.io.File
import java.util.Base64

/**
 * The few connection events worth remembering whether or not anyone turned diagnostics on.
 *
 * [DebugLog] is off by default and holds 500 entries, which is the right trade for a rolling trace
 * and the wrong one for this: HG-27 and HG-42 were both connection stalls that the user noticed
 * long before anyone thought to enable logging, so the evidence for the moment that mattered was
 * never captured. By then the app had already detected the fault — it simply had nowhere durable
 * to say so.
 *
 * This is deliberately tiny. It records stalls the client repaired and timeouts with an uncertain
 * outcome, keeps the last [CAPACITY] in a private seven-day file, and restores them as feedback
 * context after process death. Legacy feedback field names retain the `selfHeal` prefix.
 *
 * Not telemetry: nothing leaves the device except inside a report the user chose to send.
 */
object ConnectionIncidents {
    /** Small on purpose. The question is "has this been happening", not "how many times exactly". */
    const val CAPACITY = 5

    data class Incident(val atMillis: Long, val kind: String, val detail: String)

    private val lock = Any()
    private val recent = ArrayDeque<Incident>(CAPACITY)
    private var total = 0
    private var storage: File? = null

    /** Restore the small failure-only record, independent of the opt-in verbose log. */
    fun init(dir: File, nowMillis: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            storage = File(dir, "connection-incidents.txt")
            recent.clear()
            total = 0
            val saved = runCatching { storage?.readLines().orEmpty() }.getOrDefault(emptyList())
            total = saved.firstOrNull()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            saved.drop(1).takeLast(CAPACITY).forEach { line ->
                val parts = line.split('|', limit = 3)
                if (parts.size != 3) return@forEach
                val at = parts[0].toLongOrNull() ?: return@forEach
                if (at > nowMillis || nowMillis - at > RETENTION_MILLIS) return@forEach
                val kind = decode(parts[1]) ?: return@forEach
                val detail = decode(parts[2]) ?: return@forEach
                recent.addLast(Incident(at, kind, redactSecrets(detail).take(MAX_DETAIL_LENGTH)))
            }
            if (recent.isEmpty()) total = 0
            persist()
        }
    }

    /**
     * [detail] is a connection snapshot. It is redacted on the way in rather than on the way out,
     * so a future reader of [recent] cannot reintroduce the leak by forgetting.
     */
    fun record(kind: String, detail: String, atMillis: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            total++
            if (recent.size == CAPACITY) recent.removeFirst()
            recent.addLast(Incident(atMillis, kind.take(64), redactSecrets(detail).take(MAX_DETAIL_LENGTH)))
            persist()
        }
    }

    /** Feedback context. Empty when nothing has happened, so a healthy report carries no noise. */
    fun feedbackContext(): Map<String, String> = synchronized(lock) {
        if (recent.isEmpty()) return emptyMap()
        buildMap {
            put("selfHealCount", total.toString())
            recent.forEachIndexed { index, incident ->
                put(
                    "selfHeal${index + 1}",
                    "${DebugLog.formatTimestamp(incident.atMillis)} ${incident.kind} ${incident.detail}",
                )
            }
        }
    }

    /** Visible for tests. */
    fun snapshot(): List<Incident> = synchronized(lock) { recent.toList() }

    /** Visible for tests. */
    fun clear() {
        synchronized(lock) {
            recent.clear()
            total = 0
            storage?.delete()
            storage = null
        }
    }

    /** User-facing clear keeps the store attached so later failures are still retained. */
    fun clearAndRetainStorage() {
        synchronized(lock) {
            recent.clear()
            total = 0
            storage?.delete()
        }
    }

    private fun persist() {
        val target = storage ?: return
        runCatching {
            val dir = target.parentFile ?: return
            if (!dir.isDirectory && !dir.mkdirs()) return
            val temporary = File(dir, "connection-incidents.tmp")
            val value = buildString {
                append(total).append('\n')
                recent.forEach { incident ->
                    append(incident.atMillis).append('|')
                    append(encode(incident.kind)).append('|')
                    append(encode(incident.detail)).append('\n')
                }
            }
            temporary.writeText(value)
            if (!temporary.renameTo(target)) temporary.delete()
        }
    }

    private fun encode(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String? = runCatching {
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    }.getOrNull()

    private const val MAX_DETAIL_LENGTH = 512
    private const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
}
