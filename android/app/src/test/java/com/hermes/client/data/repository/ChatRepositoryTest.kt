package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesGatewayClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRepositoryTest {
    @Test fun submit_sends_prompt_submit_with_text_and_session() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns JsonPrimitive("ok")
        val repo = ChatRepository(client)

        repo.submit(sessionId = "s1", text = "hello")

        coVerify {
            client.call("prompt.submit", match { it["text"]!!.toString().contains("hello") })
        }
    }

    // Regression: a new chat created with no profile is bound to the gateway's DEFAULT profile,
    // so its messages land in a db the (active-profile-scoped) session list never scans → the chat
    // is invisible in both the Android and Desktop apps. session.create MUST carry the active profile.
    @Test fun createSession_passes_active_profile() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns buildJsonObject { put("session_id", "abc") }
        val repo = ChatRepository(client)

        val created = repo.createSession(profile = "acme")

        assertEquals("abc", created.id)
        coVerify { client.call("session.create", match { it["profile"]?.jsonPrimitive?.content == "acme" }) }
    }

    // Projects: drilled into a project, the FAB creates IN that folder. Only an explicit cwd is
    // persisted as the workspace upstream; omitted, the chat lands in the launch dir (default project).
    @Test fun createSession_passes_project_cwd_and_reports_resolved_cwd() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns buildJsonObject {
            put("session_id", "abc")
            put("info", buildJsonObject { put("cwd", "/Users/me/proj") })
        }
        val repo = ChatRepository(client)

        val created = repo.createSession(profile = "acme", cwd = "/Users/me/proj")

        assertEquals("/Users/me/proj", created.cwd)
        coVerify { client.call("session.create", match { it["cwd"]?.jsonPrimitive?.content == "/Users/me/proj" }) }
    }

    @Test fun createSession_omits_cwd_when_null_and_tolerates_missing_info() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns buildJsonObject { put("session_id", "abc") }
        val repo = ChatRepository(client)

        val created = repo.createSession(profile = "acme", cwd = null)

        assertEquals(null, created.cwd)
        coVerify { client.call("session.create", match { !it.containsKey("cwd") }) }
    }

    @Test fun moveWorkspace_targets_the_stored_key_with_profile_and_parses_the_answer() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns buildJsonObject {
            put("cwd", "/Users/me/proj")
            put("branch", "main")
            put("git_repo_root", "/Users/me/proj")
        }
        val repo = ChatRepository(client)

        val info = repo.moveWorkspace(sessionKey = "stored-1", cwd = "/Users/me/proj", profile = "work")

        assertEquals("main", info.branch)
        assertEquals("/Users/me/proj", info.gitRepoRoot)
        coVerify {
            client.call(
                "session.workspace.move",
                match {
                    it["session_key"]?.jsonPrimitive?.content == "stored-1" &&
                        it["cwd"]?.jsonPrimitive?.content == "/Users/me/proj" &&
                        it["profile"]?.jsonPrimitive?.content == "work"
                },
            )
        }
    }

    @Test fun createSession_omits_blank_profile() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns buildJsonObject { put("session_id", "abc") }
        val repo = ChatRepository(client)

        repo.createSession(profile = null)

        coVerify { client.call("session.create", match { !it.containsKey("profile") }) }
    }

    @Test fun createSession_returns_stored_id_instead_of_ephemeral_live_handle() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns buildJsonObject {
            put("session_id", "live-1")
            put("stored_session_id", "stored-1")
        }

        val id = ChatRepository(client).createSession(profile = "personal").id

        assertEquals("stored-1", id)
    }

    @Test fun attach_image_uses_current_contract_and_returns_remote_reference() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns buildJsonObject {
            put("path", "/tmp/image.png")
            put("width", 640)
            put("height", 480)
        }
        val attached = ChatRepository(client).attachImageBytes("live-1", "YWJj", "image/png")

        assertEquals("/tmp/image.png", attached.path)
        assertEquals(640, attached.width)
        coVerify {
            client.call("image.attach_bytes", match {
                it["content_base64"]?.jsonPrimitive?.content == "YWJj" && !it.containsKey("data")
            })
        }
    }

    @Test fun attach_pdf_uses_remote_bytes_contract() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns buildJsonObject { put("attached", true) }

        ChatRepository(client).attachPdfBytes("live-1", "JVBERg==", "report.pdf")

        coVerify {
            client.call("pdf.attach", match {
                it["content_base64"]?.jsonPrimitive?.content == "JVBERg==" &&
                    it["filename"]?.jsonPrimitive?.content == "report.pdf"
            })
        }
    }

    @Test fun attach_file_builds_data_url_and_returns_prompt_reference() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns buildJsonObject {
            put("name", "notes.txt")
            put("path", "/tmp/notes.txt")
            put("ref_text", "@file:.hermes/notes.txt")
        }

        val attached = ChatRepository(client).attachFileBytes(
            "live-1", "YWJj", "text/plain", "notes.txt",
        )

        assertEquals("@file:.hermes/notes.txt", attached.refText)
        coVerify {
            client.call("file.attach", match {
                it["data_url"]?.jsonPrimitive?.content == "data:text/plain;base64,YWJj" &&
                    it["name"]?.jsonPrimitive?.content == "notes.txt"
            })
        }
    }

    @Test fun process_list_maps_running_process_and_output_tail() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call("process.list", any()) } returns buildJsonObject {
            put("processes", buildJsonArray {
                add(buildJsonObject {
                    put("session_id", "proc-1")
                    put("command", "python worker.py")
                    put("status", "running")
                    put("output_tail", "working")
                })
            })
        }
        val process = ChatRepository(client).listProcesses("live-1").single()

        assertEquals("proc-1", process.id)
        assertEquals(true, process.running)
        assertEquals("working", process.outputTail)
    }

    // Hermes derives the agent's platform — and therefore its system-prompt capability block —
    // from the session's `source`. Sending nothing let Hermes fall back to its environment guess,
    // `tui`, whose prompt block claims there is no attachment channel and that MEDIA: tags are not
    // intercepted. Both are false for this app, and the agent obeyed the prompt: it withheld file
    // deliveries and printed local paths instead. The matching capability text lives in the Mac's
    // config under platform_hints.hermes_remote (docs/HERMES_CONTRACT.md).
    @Test fun createSession_identifies_this_client_to_hermes() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns buildJsonObject { put("session_id", "abc") }
        val repo = ChatRepository(client)

        repo.createSession()

        coVerify {
            client.call("session.create", match { it["source"]?.jsonPrimitive?.content == "hermes_remote" })
        }
    }

    // Resume carries it too: upstream resolves the runtime source from the resume params, so a
    // session stored before this shipped (source=tui) still gets this client's platform block.
    @Test fun resumeSession_identifies_this_client_to_hermes() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns buildJsonObject { put("session_id", "live-1") }
        val repo = ChatRepository(client)

        repo.resume("stored-1", profile = "acme")

        coVerify {
            client.call("session.resume", match { it["source"]?.jsonPrimitive?.content == "hermes_remote" })
        }
    }
}
