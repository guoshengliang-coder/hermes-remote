package com.hermes.client.data.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.hermes.client.data.network.ModelPreferenceDto
import com.hermes.client.data.network.ModelPreferenceOperationDto
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PendingModelPreference(
    val operation: ModelPreferenceOperationDto,
    val errorCode: String? = null,
    val migrationId: String? = null,
)

@Serializable
data class ModelPreferenceCache(
    val generation: String,
    val revision: String = "0",
    val cursor: String = "",
    val lease: String = "",
    val rows: List<ModelPreferenceDto> = emptyList(),
    val pending: List<PendingModelPreference> = emptyList(),
    val completedOps: List<String> = emptyList(),
)

interface ModelPreferenceCacheStore {
    /** Snapshot and outbox commit together. The owner guard is checked inside the transaction. */
    fun update(scope: String, active: () -> Boolean, transform: (ModelPreferenceCache?) -> ModelPreferenceCache?): ModelPreferenceCache?
    fun clear()
}

/** App-private SQLite, without access/refresh tokens or Hermes configuration. */
class SqliteModelPreferenceCacheStore(context: Context, name: String = "account-model-preferences.db") : ModelPreferenceCacheStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val file = context.getDatabasePath(name)
    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE accounts(scope TEXT PRIMARY KEY NOT NULL,payload TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    override fun update(scope: String, active: () -> Boolean, transform: (ModelPreferenceCache?) -> ModelPreferenceCache?): ModelPreferenceCache? = synchronized(lock) {
        if (!active()) throw CancellationException("model owner changed")
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val previous = db.query("accounts", arrayOf("payload"), "scope=?", arrayOf(scope), null, null, null).use { cursor ->
                if (cursor.moveToFirst()) json.decodeFromString(ModelPreferenceCache.serializer(), cursor.getString(0)).also(::validateModelCache) else null
            }
            val result = transform(previous)
            if (!active()) throw CancellationException("model owner changed")
            if (result != null) {
                validateModelCache(result)
                val values = ContentValues().apply { put("scope", scope); put("payload", json.encodeToString(ModelPreferenceCache.serializer(), result)) }
                check(db.insertWithOnConflict("accounts", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            }
            db.setTransactionSuccessful()
            result
        } finally { db.endTransaction() }
    }

    fun close() = synchronized(lock) { helper.close() }

    override fun clear() = synchronized(lock) {
        // No DB/keystore opens during singleton construction. An unused cache needs no cleanup.
        if (file.exists()) helper.writableDatabase.delete("accounts", null, null)
        Unit
    }
}

/** Treat malformed disk data as a storage failure; never invent a base or send corrupt operations. */
internal fun validateModelCache(record: ModelPreferenceCache) {
    check(modelSyncRevision(record.revision) != null && record.rows.size <= 500 && record.pending.size <= 1000 && record.completedOps.size <= 10_000)
    check(record.cursor.length <= 512 && record.lease.length <= 512 && record.generation.isNotBlank())
    check(record.rows.all { validModelPreference(it, record.revision) })
    check(record.rows.map { modelTuple(it.provider,it.model) }.distinct().size == record.rows.size)
    for (pending in record.pending) {
        val op=pending.operation
        check(runCatching { java.util.UUID.fromString(op.opId) }.isSuccess && op.lease.isNotBlank() && op.lease.length<=512 && modelSyncRevision(op.baseRevision)!=null)
        check(op.provider.isNotBlank() && op.provider.length<=256 && op.model.isNotBlank() && op.model.length<=1024)
        check((op.provider+op.model).none{it.code<32||it.code==127})
        check(if(op.field=="favorite") (op.value as? kotlinx.serialization.json.JsonPrimitive)?.isString==false && op.value.toString() in setOf("true","false")
            else op.field=="reasoningPreset" && (op.value==kotlinx.serialization.json.JsonNull || (op.value as? kotlinx.serialization.json.JsonPrimitive)?.let{it.isString && it.content in MODEL_REASONING_PRESETS}==true))
    }
}
