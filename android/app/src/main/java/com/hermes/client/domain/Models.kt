package com.hermes.client.domain

enum class Role { USER, ASSISTANT, SYSTEM }
enum class ToolStatus { RUNNING, DONE }
enum class ImageTransferState { READY, UPLOADING, FAILED }
enum class FileTransferState { READY, UPLOADING, FAILED }

data class Session(
    val id: String,
    val title: String,
    val model: String?,
    val provider: String?,
    val messageCount: Int,
    // The profile this session belongs to. From the cross-profile list this is always set;
    // the default profile is normalized to "default" so grouping, pin tokens, and profile
    // switching share one stable key. Used as the top grouping tier.
    val profile: String?,
    // Workspace = basename of the session's cwd ("No workspace" when none), used for grouping.
    val workspace: String = "No workspace",
    val archived: Boolean = false,
    val source: String? = null,
    // Epoch millis of last activity (from the gateway's last_active seconds), for recency sorting
    // and the Mission Control feed. Null when the gateway omits it.
    val lastActive: Long? = null,
    // Full working directory (not just the basename in [workspace]); null when the session has none.
    val cwd: String? = null,
    // Git context resolved server-side, present on project-tree session rows; null otherwise.
    val gitBranch: String? = null,
    val gitRepoRoot: String? = null,
)

data class ToolCall(
    val id: String,
    val name: String,
    val status: ToolStatus,
    val output: String = "",
    // Semantic metadata parsed from the RAW payload before display normalization unwraps it:
    // command-shaped tools render `$ command`, exit codes drive the failure state, and the
    // duration feeds the card header. Null for tools without the command shape.
    val command: String? = null,
    val exitCode: Int? = null,
    val durationMs: Long? = null,
    // A task-list payload (the gateway sends the full list on tool.complete) renders as a
    // checklist card with progress instead of a generic tool card.
    val todos: List<TodoItem> = emptyList(),
)

/** One entry of an agent task list; status is completed / in_progress / pending / cancelled. */
data class TodoItem(
    val content: String,
    val status: String,
)

/** A chat image is stored as a small reference; full image bytes live in the app cache. */
data class ChatImage(
    val id: String,
    val mimeType: String? = null,
    val localPath: String? = null,
    val remotePath: String? = null,
    val sourceUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val state: ImageTransferState = ImageTransferState.READY,
)

/** A non-image artifact. Bytes are fetched only when the user opens or downloads it. */
data class ChatFile(
    val id: String,
    val name: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val localPath: String? = null,
    val remotePath: String? = null,
    val state: FileTransferState = FileTransferState.READY,
)

data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    // Epoch millis. Live messages are stamped locally; REST history maps created_at when the
    // gateway provides it and inherits the live stamp during reconciliation otherwise.
    val timestamp: Long? = null,
    val images: List<ChatImage> = emptyList(),
    val files: List<ChatFile> = emptyList(),
    val tools: List<ToolCall> = emptyList(),
    val thinking: String = "",
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val interrupted: Boolean = false,
    // Server timeline marker (display_kind) for injected turns; null for real conversation turns.
    val displayKind: String? = null,
    // From display_metadata of async_delegation_complete: how many subtasks ran / failed.
    val displayTaskCount: Int? = null,
    val displayFailedCount: Int? = null,
)

/** A server-authoritative project (explicit user project or an auto git-repo/discovered project). */
data class Project(
    val id: String,
    val label: String,
    val path: String?,
    // Hex string like "#RRGGBB" for explicit projects; null for auto/discovered (render with accent).
    val color: String?,
    val isAuto: Boolean,
    val sessionCount: Int,
    val lastActive: Long?,
    val repos: List<ProjectRepo>,
    // Newest sessions for the overview card; empty after drill-in (lanes carry the full set then).
    val previewSessions: List<Session>,
)

data class ProjectRepo(
    val id: String,
    val label: String,
    val path: String?,
    val sessionCount: Int,
    val lanes: List<ProjectLane>,
)

data class ProjectLane(
    val id: String,
    val label: String,
    val path: String?,
    val isMain: Boolean,
    val sessions: List<Session>,
)

data class ProjectTree(
    val projects: List<Project>,
    val activeId: String?,
)
