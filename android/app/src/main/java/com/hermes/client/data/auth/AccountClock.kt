package com.hermes.client.data.auth

import java.time.Instant

/** Injectable wall clock for persisted account challenge deadlines. */
fun interface AccountClock {
    fun now(): Instant

    companion object {
        val SYSTEM = AccountClock { Instant.now() }
    }
}
