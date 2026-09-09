package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.data.network.ProjectMutationResultDto
import com.hermes.client.data.network.ProjectSessionsResultDto
import com.hermes.client.data.network.ProjectTreeDto
import com.hermes.client.domain.Project
import com.hermes.client.domain.ProjectRecord
import com.hermes.client.domain.ProjectTree
import com.hermes.client.domain.toDomain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The gateway's server-authoritative projects, over WS JSON-RPC.
 *
 * **Single-profile.** Upstream resolves `projects.db` from the gateway process's own HERMES_HOME
 * and none of the `projects.*` methods accept a `profile` param, so everything here reads and
 * writes the gateway's LAUNCH profile no matter which profile the app has selected. The Projects
 * page therefore only uses this repository while those two agree; otherwise it falls back to the
 * client-side derivation (see `ProjectDerivation.kt`).
 *
 * Piggybacks on the socket the sessions screen already opens via [ChatRepository.connect].
 */
class ProjectsRepository(
    private val client: HermesGatewayClient,
    private val json: Json,
) {
    /** Fetch the project overview (nodes with counts + up to [previewLimit] preview sessions). */
    suspend fun tree(previewLimit: Int = 3): ProjectTree {
        val result = client.call("projects.tree", buildJsonObject { put("preview_limit", previewLimit) })
        return json.decodeFromJsonElement(ProjectTreeDto.serializer(), result).toDomain()
    }

    /** Hydrate one project's sessions for drill-in. [projectId] must equal a tree node id. */
    suspend fun projectSessions(projectId: String): Project? {
        val result = client.call("projects.project_sessions", buildJsonObject { put("project_id", projectId) })
        return json.decodeFromJsonElement(ProjectSessionsResultDto.serializer(), result).project?.toDomain()
    }

    /**
     * Create an explicit project. [folders] may be empty upstream, but a folder-less project can
     * never own a session (membership is a path match), so the UI always supplies one.
     *
     * The slug is derived from the name and uniquified by the gateway; it is immutable afterwards.
     */
    suspend fun create(name: String, folder: String?, icon: String?, color: String?): ProjectRecord =
        mutate("projects.create", buildJsonObject {
            put("name", name)
            if (folder != null) put("folders", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(folder)) })
            if (icon != null) put("icon", icon)
            if (color != null) put("color", color)
        })

    /**
     * Patch a project's presentation. Only non-null fields are sent, and upstream only writes the
     * fields it receives — so renaming cannot clobber an icon set from the desktop.
     */
    suspend fun update(id: String, name: String? = null, icon: String? = null, color: String? = null): ProjectRecord =
        mutate("projects.update", buildJsonObject {
            put("id", id)
            if (name != null) put("name", name)
            if (icon != null) put("icon", icon)
            if (color != null) put("color", color)
        })

    /** Add a folder. The first folder of a folder-less project becomes its primary automatically. */
    suspend fun addFolder(id: String, path: String): ProjectRecord =
        mutate("projects.add_folder", buildJsonObject { put("id", id); put("path", path) })

    /** Remove a folder. Sessions living there fall back to an auto project; nothing on disk moves. */
    suspend fun removeFolder(id: String, path: String): ProjectRecord =
        mutate("projects.remove_folder", buildJsonObject { put("id", id); put("path", path) })

    /** Promote an already-added folder to primary (the path new chats are created in). */
    suspend fun setPrimary(id: String, path: String): ProjectRecord =
        mutate("projects.set_primary", buildJsonObject { put("id", id); put("path", path) })

    /**
     * Delete the project ROW. Upstream cascades only its folder rows: sessions are untouched, and
     * nothing on disk is removed — the folder immediately comes back as an auto project. The UI
     * calls this "remove grouping" for exactly that reason.
     */
    suspend fun delete(id: String) {
        client.call("projects.delete", buildJsonObject { put("id", id) })
    }

    private suspend fun mutate(method: String, params: JsonObject): ProjectRecord {
        val result = client.call(method, params)
        val row = json.decodeFromJsonElement(ProjectMutationResultDto.serializer(), result).project
            ?: throw IllegalStateException("$method returned no project")
        return row.toDomain()
    }
}
