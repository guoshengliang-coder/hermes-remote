package com.hermes.client.data

import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProjectsRepository
import com.hermes.client.ui.chat.ApprovalChoice
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The params keys every WebSocket RPC may carry, pinned per method.
 *
 * Hermes 17b5df02 validates every call against a pydantic contract whose base `Params` is
 * `extra="forbid"` (`tui_gateway/contracts/base.py`, `registry.py::validate_params`): ONE key it does
 * not declare turns the whole call into error 4000. f159e581 validated nothing, so an unused key was
 * harmless there — which is how `inline_images`, `mime_type` and `approved` got in unnoticed.
 *
 * The allowlist is `docs/hermes-rpc-params.json`: the 17b5df02 params model of each method, inherited
 * `SessionParams`/`ProfileParams` fields included, generated from `tui_gateway/contracts/` and shown
 * as a table in docs/HERMES_CONTRACT.md section 3. The two methods 17b5df02 does not have at all are
 * listed with the keys their f159e581-era handler reads (they only ever reach a Hermes that has
 * them). The dev mock rejects the same keys in strict mode. A key that is not in it fails the
 * build, not a user's conversation.
 */
class RpcParamContractTest {

    private companion object {
        /** Working directory of the unit tests is `android/app`. */
        val PARAMS_FILE = File("../../docs/hermes-rpc-params.json")
        val CONTRACT_DOC = File("../../docs/HERMES_CONTRACT.md")

        private fun keysOf(section: JsonObject?): Map<String, Set<String>> =
            section.orEmpty().mapValues { (_, entry) ->
                entry.jsonObject.getValue("keys").jsonArray.map { it.jsonPrimitive.content }.toSet()
            }

        /** 17b5df02's params model per method, plus the two methods it no longer has at all. */
        val UPSTREAM: Map<String, Set<String>> by lazy {
            keysOf(Json.parseToJsonElement(PARAMS_FILE.readText()).jsonObject["methods"]?.jsonObject)
        }
        val ABSENT_UPSTREAM: Map<String, Set<String>> by lazy {
            keysOf(Json.parseToJsonElement(PARAMS_FILE.readText()).jsonObject["absent_upstream"]?.jsonObject)
        }
        val ALLOWED: Map<String, Set<String>> by lazy { UPSTREAM + ABSENT_UPSTREAM }
    }

    private val sent = mutableListOf<Pair<String, JsonObject>>()

    private fun recordingClient(): HermesGatewayClient {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } answers {
            sent += firstArg<String>() to secondArg<JsonObject>()
            answerFor(firstArg())
        }
        return client
    }

    /** Just enough of each answer for the call site to return normally. */
    private fun answerFor(method: String): JsonElement = when {
        method == "projects.tree" -> Json.parseToJsonElement("""{"projects":[],"active_id":null}""")
        method == "projects.project_sessions" -> Json.parseToJsonElement("""{"project":null}""")
        method.startsWith("projects.") -> Json.parseToJsonElement(
            """{"project":{"id":"p1","name":"n","slug":"n","folders":[]}}""",
        )
        else -> buildJsonObject {
            put("session_id", "live-1")
            put("stored_session_id", "stored-1")
            put("path", "/tmp/x")
            put("ref_text", "@file:/tmp/x")
            put("state", "available")
            put("status", "ok")
            putJsonObject("info") { put("cwd", "/tmp") }
        }
    }

    /** Every call site, each with every optional argument set so it sends its widest key set. */
    private suspend fun exerciseEveryCallSite() {
        val chat = ChatRepository(recordingClient())
        val projects = ProjectsRepository(recordingClient(), Json { ignoreUnknownKeys = true })
        chat.createSession(profile = "p", cwd = "/w")
        chat.moveWorkspace("stored-1", "/w", profile = "p")
        chat.resume("stored-1", profile = "p")
        chat.sessionAccess("stored-1", profile = "p", liveSessionId = "live-1")
        chat.submit("live-1", "hi")
        chat.slashExec("live-1", "/help")
        chat.completePath("live-1", "@x")
        chat.reasoningGet("live-1")
        chat.reasoningSet("live-1", "high")
        chat.commandsCatalog()
        chat.interrupt("live-1")
        chat.attachImageBytes("live-1", "YWJj", "image/png")
        chat.attachImagePath("live-1", "/tmp/a.png")
        chat.attachPdfBytes("live-1", "JVBERg==", "a.pdf")
        chat.attachPdfPath("live-1", "/tmp/a.pdf")
        chat.attachFileBytes("live-1", "YWJj", "text/plain", "a.txt")
        chat.attachFilePath("live-1", "/tmp/a.txt", "a.txt")
        chat.listProcesses("live-1")
        chat.respondApproval("live-1", ApprovalChoice.ONCE)
        chat.respondClarify("live-1", "clr-1", "a", questionId = "q0")
        chat.respondClarify("live-1", "srq-1", "a", questionId = "q0", serverRequest = true)
        runCatching { projects.tree() }
        runCatching { projects.projectSessions("p1") }
        runCatching { projects.create("n", "/w", "icon", "#fff") }
        runCatching { projects.update("p1", name = "n", icon = "i", color = "#fff") }
        runCatching { projects.addFolder("p1", "/w") }
        runCatching { projects.removeFolder("p1", "/w") }
        runCatching { projects.setPrimary("p1", "/w") }
        runCatching { projects.delete("p1") }
    }

    @Test fun every_call_site_sends_only_keys_its_upstream_contract_declares() = runTest {
        exerciseEveryCallSite()
        val violations = sent.mapNotNull { (method, params) ->
            val allowed = ALLOWED[method] ?: return@mapNotNull "$method has no entry in ALLOWED"
            val extra = params.keys - allowed
            if (extra.isEmpty()) null else "$method sends undeclared $extra (17b5df02 answers 4000)"
        }.distinct()
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    /**
     * The exercise above only sees call sites it knows to call. This reads the sources, so a new
     * `client.call("…")` or `mutate("…")` cannot land without an entry here and a line in the
     * exercise — the place where its keys are actually checked.
     */
    @Test fun every_rpc_method_named_in_the_sources_is_pinned_and_exercised() = runTest {
        exerciseEveryCallSite()
        val exercised = sent.map { it.first }.toSet() + "client.capabilities" // sent by the socket itself
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("source tree not found from ${File(".").absolutePath}", sources.isNotEmpty())
        val literal = Regex("""(?:\.call|mutate)\(\s*"([a-z_]+(?:\.[a-z_]+)+)"""")
        val named = sources.flatMap { file -> literal.findAll(file.readText()).map { it.groupValues[1] }.toList() }
            .toSet() + "clarify.lock" + "client.capabilities" // named through ServerRequests constants
        assertEquals("methods named in sources but not pinned", emptySet<String>(), named - ALLOWED.keys)
        assertEquals("methods named in sources but not exercised", emptySet<String>(), named - exercised)
    }

    @Test fun the_capability_frame_fits_its_contract() {
        val params = com.hermes.client.data.network.ServerRequests.capabilityParams()
        assertTrue(ALLOWED.getValue("client.capabilities").containsAll(params.keys))
    }

    /** The table in HERMES_CONTRACT.md is what a person reads; it must say what the build enforces. */
    @Test fun the_contract_doc_table_matches_the_allowlist() {
        val row = Regex("""^\|\s*`([a-z_.]+)`\s*\|[^|]*\|\s*([^|]*)\|""")
        val table = CONTRACT_DOC.readLines()
            .dropWhile { !it.startsWith("#### WebSocket RPC params") }
            .dropWhile { !it.startsWith("|") }
            .takeWhile { it.startsWith("|") } // the first table under the heading only
            .mapNotNull { line -> row.find(line)?.let { m -> m.groupValues[1] to m.groupValues[2] } }
            .associate { (method, keys) ->
                method to Regex("`([a-z0-9_]+)`").findAll(keys).map { it.groupValues[1] }.toSet()
            }
        assertEquals(ALLOWED, table)
    }
}
