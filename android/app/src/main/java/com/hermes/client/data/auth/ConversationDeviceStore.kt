package com.hermes.client.data.auth

/**
 * Account-scoped affinity between one durable Hermes conversation and the Mac that owns it.
 * Device IDs are opaque routing identifiers; callers must never infer ownership from them.
 */
interface ConversationDeviceStore {
    fun bind(accountId: String, profile: String?, sessionId: String, deviceId: String)
    fun resolve(accountId: String, profile: String?, sessionId: String): String?
    fun remove(accountId: String, profile: String?, sessionId: String)
}
