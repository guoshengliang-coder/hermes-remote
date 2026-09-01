package com.hermes.client.data.network

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * The OkHttp client for MockWebServer-backed REST tests.
 *
 * These tests drive a REAL client over a loopback socket, so a bare `OkHttpClient()` inherits the
 * default 10s read timeout. When the full suite runs the machine is loaded enough that the window
 * can genuinely expire before the mock replies — it surfaces as a SocketTimeoutException in
 * whichever test happened to be in flight (observed once on
 * `HermesRestApiTranscribeTest.transcribe_error_throws`, which passed on every isolated re-run).
 *
 * The timeout is raised, not lowered. Nothing here tests timeout behaviour, so the only job of
 * these values is to stop machine load deciding the result; 30s keeps them under `runTest`'s own
 * 60s ceiling, so a test that truly hangs still fails here first with the clearer message.
 */
internal fun testHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .build()
