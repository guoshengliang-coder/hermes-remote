package com.hermes.client.update

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * HG-223: the transfer must keep its bytes across interruptions, never splice two builds, and
 * use the four planned ranges. The release-server side of the contract (ETag, If-Match,
 * If-Range) is asserted in release-server/test/server.test.mjs.
 */
class UpdateDownloadTransferTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: File

    private val version = UpdateVersion(
        versionName = "0.1.180",
        versionCode = 181,
        applicationId = "com.hermes.remote",
        channel = "internal",
        publishedAt = "2026-10-22T00:00:00Z",
        fileName = "Hermes-Remote-0.1.180-debug.apk",
        downloadUrl = "https://mrlgs.net/releases/Hermes-Remote-0.1.180-debug.apk",
        sizeBytes = 10,
        sha256 = "a".repeat(64),
        certificateSha256 = "06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5",
        minSdk = 26,
        releaseNotes = listOf("note"),
        sourceCommit = "abcdef1",
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        directory = Files.createTempDirectory("update-transfer-").toFile()
    }

    @After
    fun tearDown() {
        server.close()
        directory.deleteRecursively()
    }

    private val body = "0123456789".toByteArray()
    private val etag = "\"${MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }}\""

    private fun versionFor(bodyBytes: ByteArray = body) = version.copy(
        downloadUrl = server.url("/apk").toString(),
        sizeBytes = bodyBytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bodyBytes).joinToString("") { "%02x".format(it) },
    )

    private fun serve(bodyBytes: ByteArray = body, etagValue: String = etag) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "HEAD") {
                    return MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Length", bodyBytes.size)
                        .addHeader("ETag", etagValue)
                        .build()
                }
                val range = request.headers["Range"]
                val ifMatch = request.headers["If-Match"]
                val ifRange = request.headers["If-Range"]
                if (ifMatch != null && ifMatch != etagValue) {
                    return MockResponse.Builder().code(412).addHeader("ETag", etagValue).build()
                }
                if (ifRange != null && ifRange != etagValue) {
                    return MockResponse.Builder().code(200)
                        .addHeader("Content-Length", bodyBytes.size)
                        .addHeader("ETag", etagValue)
                        .body(Buffer().write(bodyBytes)).build()
                }
                val match = requireNotNull(range)
                val (from, to) = Regex("bytes=(\\d+)-(\\d+)").find(match)!!.destructured
                val start = from.toInt()
                val end = to.toInt()
                bytesToReturn += (start to end)
                return MockResponse.Builder()
                    .code(206)
                    .addHeader("Content-Length", end - start + 1)
                    .addHeader("Content-Range", "bytes $start-$end/${bodyBytes.size}")
                    .addHeader("ETag", etagValue)
                    .body(Buffer().write(bodyBytes, start, end - start + 1))
                    .build()
            }
        }
    }

    private val bytesToReturn = mutableListOf<Pair<Int, Int>>()

    private suspend fun transfer(record: UpdateDownloadRecord, progress: MutableList<UpdateDownloadRecord> = mutableListOf()):
        UpdateDownloadRecord {
        val committed = mutableListOf<UpdateDownloadRecord>()
        val client = OkHttpClient()
        return UpdateDownloadTransfer(
            callFactory = { request: Request -> client.newCall(request) },
            directory = directory,
            commit = { committed += it },
            progress = { progress += it },
        ).run(record)
    }

    @Test fun `plans contiguous ranges that cover the file exactly`() {
        for (size in listOf(1L, 3L, 10L, 11L, 32L * 1024 * 1024)) {
            val segments = planUpdateSegments(size, 4)
            assertEquals(size, segments.sumOf { it.length })
            assertEquals(0L, segments.first().start)
            assertEquals(size - 1, segments.last().end)
            segments.zipWithNext().forEach { (left, right) -> assertEquals(left.end + 1, right.start) }
            assertEquals(minOf(4L, size), segments.size.toLong())
        }
        assertEquals(2, planUpdateSegments(2, 4).size)
    }

    @Test fun `a chunk never crosses its segment`() {
        val segment = UpdateDownloadSegment(100, 150)
        assertEquals(100L..(100L + UPDATE_DOWNLOAD_CHUNK_BYTES - 1).coerceAtMost(150), segment.nextRange())
        assertEquals(120L..150L, segment.copy(downloaded = 20).nextRange(chunkBytes = 1000))
        assertNull(segment.copy(downloaded = 51).nextRange())
        assertThrows(IllegalArgumentException::class.java) { segment.advance(101L..150L, 10) }
    }

    @Test fun `downloads four ranges and refuses to publish the file before every byte`() = runTest {
        serve()
        val record = UpdateDownloadRecord(versionFor(), body.size.toLong(), planUpdateSegments(body.size.toLong()))
        val progress = mutableListOf<UpdateDownloadRecord>()
        val done = transfer(record, progress)

        assertEquals(UpdateDownloadStatus.COMPLETE, done.status)
        assertEquals(body.size.toLong(), done.downloadedBytes)
        val published = updateDownloadTarget(directory, record.version)
        assertTrue(published.isFile)
        assertArrayEquals(body, published.readBytes())
        assertFalse(updateDownloadPartial(directory, record.version).exists())
        assertEquals(4, bytesToReturn.size)
        assertTrue(progress.any { it.status == UpdateDownloadStatus.RUNNING })
    }

    @Test fun `resume requests only the missing bytes of each segment`() = runTest {
        serve()
        val latest = versionFor()
        val record = UpdateDownloadRecord(
            version = latest,
            totalBytes = body.size.toLong(),
            segments = planUpdateSegments(body.size.toLong()).mapIndexed { index, segment ->
                if (index == 0) segment.copy(downloaded = 2) else segment
            },
        )
        val partial = updateDownloadPartial(directory, latest)
        partial.writeBytes(body.copyOfRange(0, 2))
        java.io.RandomAccessFile(partial, "rw").use { it.setLength(body.size.toLong()) }

        val done = transfer(record)

        assertEquals(UpdateDownloadStatus.COMPLETE, done.status)
        assertArrayEquals(body, updateDownloadTarget(directory, latest).readBytes())
        // Services usually deliver descending requests; compare as a set instead of by arrival.
        assertTrue(bytesToReturn.toString(), bytesToReturn.contains(2 to 2))
        assertFalse(bytesToReturn.any { it.first == 0 })
    }

    @Test fun `a changed validator restarts once instead of splicing builds`() = runTest {
        serve(body, etagValue = etag)
        val latest = versionFor()
        // The journal was written by an older process that saw a different entity.
        val stale = UpdateDownloadRecord(
            version = latest,
            totalBytes = body.size.toLong(),
            segments = planUpdateSegments(body.size.toLong()).map { it.copy(downloaded = it.length / 2) },
            etag = "\"stale\"",
        )
        updateDownloadPartial(directory, latest).writeBytes(ByteArray(body.size))

        val done = transfer(stale)

        assertEquals(UpdateDownloadStatus.COMPLETE, done.status)
        assertEquals(etag, done.etag)
        assertArrayEquals(body, updateDownloadTarget(directory, latest).readBytes())
        assertEquals(body.size.toLong(), done.downloadedBytes)
    }

    @Test fun `permanent HTTP failures stay failed instead of retrying forever`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "HEAD") {
                    return MockResponse.Builder().code(200).addHeader("Content-Length", body.size).addHeader("ETag", etag).build()
                }
                return MockResponse.Builder().code(403).build()
            }
        }
        val record = UpdateDownloadRecord(versionFor(), body.size.toLong(), planUpdateSegments(body.size.toLong()))
        val failure = runCatching { transfer(record) }.exceptionOrNull()
        assertTrue(failure is UpdateDownloadFailure)
        assertFalse((failure as UpdateDownloadFailure).retryable)
        assertFalse(updateDownloadTarget(directory, record.version).exists())
    }

    @Test fun `saved decisions never resurrect an installed build`() {
        val latest = versionFor()
        val record = UpdateDownloadRecord(latest, body.size.toLong(), planUpdateSegments(body.size.toLong()))
        assertEquals(SavedDownloadDecision.Resume(latest), decideSavedDownload(record, latest.versionCode - 1))
        assertEquals(SavedDownloadDecision.AlreadyInstalled, decideSavedDownload(record, latest.versionCode))
        assertEquals(SavedDownloadDecision.AlreadyInstalled, decideSavedDownload(record, latest.versionCode + 1))
    }
}
