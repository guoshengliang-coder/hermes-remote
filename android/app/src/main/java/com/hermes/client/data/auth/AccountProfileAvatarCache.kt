package com.hermes.client.data.auth

import java.io.File
import java.security.MessageDigest

/** Private cache owned solely by account sync; do not touch files/avatars (Hermes identities). */
class AccountProfileAvatarCache(private val root: File) {
    fun write(origin: String, accountId: String, revision: String, bytes: ByteArray): String {
        require(bytes.isNotEmpty() && bytes.size <= 4 * 1024 * 1024)
        val key = MessageDigest.getInstance("SHA-256").digest("$origin\u0000$accountId".toByteArray())
            .joinToString("") { "%02x".format(it) }
        root.mkdirs()
        val target = File(root, "$key-$revision")
        val temporary = File(root, "$key-pending")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(target))
        root.listFiles()?.filter { it != target }?.forEach { it.delete() }
        return target.absolutePath
    }
    fun clear() { root.listFiles()?.forEach { it.delete() } }
}
