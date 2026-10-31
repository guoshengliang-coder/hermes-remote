package com.hermes.client.data.network

import com.hermes.client.data.network.testAccountConnection
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class HermesRestApiContractTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val json = Json { ignoreUnknownKeys = true }

    private fun api(server: MockWebServer, token: String = "secret-token") = HermesRestApi(testHttpClient(), json) {
        testAccountConnection(baseUrl = server.url("/").toString().trimEnd('/'), token = token)
    }

    @Test fun the_report_is_read_from_the_connector_route() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body(
            """{"schema":1,"status":"degraded","code":"HR-COMPAT-002","missing":[]}""",
        ).build())

        val report = api(serverRule.server).hermesContract()

        assertEquals("degraded", report.status)
        assertEquals(HERMES_CONTRACT_REPORT_PATH, serverRule.server.takeRequest().target)
    }

    /**
     * Review D4: the report has its own short call deadline. `withTimeout` in the monitor cannot
     * interrupt OkHttp's blocking execute(), so the 20 s REST default is what used to apply.
     */
    @Test fun a_stalled_report_gives_up_on_its_own_deadline() = runTest {
        serverRule.server.enqueue(
            MockResponse.Builder().code(200).headersDelay(4, TimeUnit.SECONDS).body("{}").build(),
        )
        val started = System.nanoTime()
        try {
            api(serverRule.server).hermesContract(timeoutSeconds = 1)
            fail("a stalled report must time out")
        } catch (expected: java.io.IOException) {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("gave up after $elapsedMs ms", elapsedMs < 3_500)
        }
    }

    /** Review D1: the verdict is keyed on the Mac it describes, and the key carries no secret. */
    @Test fun the_target_key_names_the_gateway_and_token_without_revealing_the_token() = runTest {
        val first = api(serverRule.server, token = "secret-token").contractTargetKey()!!
        val otherToken = api(serverRule.server, token = "another-token").contractTargetKey()!!
        val otherGateway = HermesRestApi(testHttpClient(), json) {
            testAccountConnection(baseUrl = "https://other.example", token = "secret-token")
        }.contractTargetKey()!!

        assertFalse(first, first.contains("secret-token"))
        assertEquals(first, api(serverRule.server, token = "secret-token").contractTargetKey())
        assertEquals(first, otherToken)
        assertNotEquals(first, otherGateway)
        assertNull(HermesRestApi(testHttpClient(), json) { null }.contractTargetKey())
    }

    @Test fun in_account_mode_the_target_key_names_the_device_not_the_bearer() = runTest {
        var device = "mac-a"
        val manager = io.mockk.mockk<com.hermes.client.data.auth.AccountSessionManager>(relaxed = true)
        io.mockk.coEvery { manager.connection(null) } answers {
            com.hermes.client.data.auth.AccountConnection(
                baseUrl = "https://relay.example/",
                bearer = "bearer-secret",
                deviceId = device,
                deviceRouteMode = com.hermes.client.data.auth.AccountDeviceRouteMode.EXPLICIT_DEVICE,
            )
        }
        io.mockk.every { manager.session } returns kotlinx.coroutines.flow.MutableStateFlow(null)
        val api = HermesRestApi(testHttpClient(), json, manager, testHttpClient()) { null }

        val macA = api.contractTargetKey()!!
        device = "mac-b"
        val macB = api.contractTargetKey()!!

        assertNotEquals(macA, macB)
        assertFalse(macA, macA.contains("bearer-secret"))
    }

    /**
     * Hermes ≥ 0.22.5 takes PUT /api/cron/jobs/{id} as `CronJobUpdate` — one required `updates`
     * object holding the fields. A flat body answers 422 `Field required: body.updates` and the
     * edit never saves (the 0.22.5 audit's second silent break, after HG-250's fence). Create
     * (`POST`) is a different, flat model and must stay untouched.
     */
    @Test fun cron_update_nests_its_fields_under_updates_while_create_stays_flat() = runTest {
        val server = serverRule.server
        server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true}""").build())

        val rest = api(server)
        rest.updateCron("job-1", "p", "0 8 * * *", "daily", "local")
        rest.createCron("p", "0 9 * * *", "morning", "local")

        val put = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        val post = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(setOf("updates"), put.keys)
        assertEquals(
            setOf("prompt", "schedule", "name", "deliver"),
            put["updates"]!!.jsonObject.keys,
        )
        assertEquals(setOf("prompt", "schedule", "name", "deliver"), post.keys)
    }
}
