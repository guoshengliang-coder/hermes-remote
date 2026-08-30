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

        val id = repo.createSession(profile = "acme")

        assertEquals("abc", id)
        coVerify { client.call("session.create", match { it["profile"]?.jsonPrimitive?.content == "acme" }) }
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

        val id = ChatRepository(client).createSession(profile = "personal")

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
}
