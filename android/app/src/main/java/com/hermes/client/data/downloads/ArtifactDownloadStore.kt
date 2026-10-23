package com.hermes.client.data.downloads

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ArtifactDownloadStatus { QUEUED, RUNNING, WAITING, PAUSED, FAILED, COMPLETE, CANCELLED }

@Serializable
data class ArtifactDownloadTask(
    val id: String,
    val owner: String,
    val deviceId: String?,
    val remotePath: String,
    val name: String,
    val mimeType: String?,
    val status: ArtifactDownloadStatus = ArtifactDownloadStatus.QUEUED,
    val sizeBytes: Long? = null,
    val downloaded: Long = 0,
    @kotlinx.serialization.Transient val progressBytes: Long? = null,
    @kotlinx.serialization.Transient val waitingForPlayback: Boolean = false,
    val etag: String? = null,
    val errorCode: String? = null,
)

/** Transactional task records contain no bearer or return capability. Bytes live in filesDir. */
class ArtifactDownloadStore(context: Context) : SQLiteOpenHelper(context, "artifact-downloads.db", null, 1) {
    private val json = Json { ignoreUnknownKeys = true }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE downloads (id TEXT PRIMARY KEY, record TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun all(): List<ArtifactDownloadTask> = readableDatabase.query(
        "downloads", arrayOf("record"), null, null, null, null, "rowid ASC",
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(json.decodeFromString<ArtifactDownloadTask>(cursor.getString(0)))
    } }
    @Synchronized fun put(task: ArtifactDownloadTask) {
        writableDatabase.insertWithOnConflict("downloads", null, ContentValues().apply {
            put("id", task.id); put("record", json.encodeToString(ArtifactDownloadTask.serializer(), task))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it >= 0) }
    }
}
