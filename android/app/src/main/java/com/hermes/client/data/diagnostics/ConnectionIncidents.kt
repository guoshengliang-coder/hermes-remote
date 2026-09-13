package com.hermes.client.data.diagnostics

import com.hermes.client.data.error.redactSecrets

/**
 * The few connection events worth remembering whether or not anyone turned diagnostics on.
 *
 * [DebugLog] is off by default and holds 500 entries, which is the right trade for a rolling trace
 * and the wrong one for this: HG-27 and HG-42 were both connection stalls that the user noticed
 * long before anyone thought to enable logging, so the evidence for the moment that mattered was
 * never captured. By then the app had already detected the fault — it simply had nowhere durable
 * to say so.
 *
 * This is deliberately tiny. It records only events the client has *acted* on (a stall it repaired
 * by itself), keeps the last [CAPACITY], and is read back as feedback context so every report
 * carries them. A user who files "连不上" gets the self-heal history attached without having to
 * know what a diagnostic log is.
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

    /**
     * [detail] is a connection snapshot. It is redacted on the way in rather than on the way out,
     * so a future reader of [recent] cannot reintroduce the leak by forgetting.
     */
    fun record(kind: String, detail: String, atMillis: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            total++
            if (recent.size == CAPACITY) recent.removeFirst()
            recent.addLast(Incident(atMillis, kind, redactSecrets(detail)))
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
        }
    }
}
