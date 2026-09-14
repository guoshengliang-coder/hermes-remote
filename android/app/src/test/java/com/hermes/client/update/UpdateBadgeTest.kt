package com.hermes.client.update

import com.hermes.client.BuildConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import java.io.File
import org.junit.Test

/**
 * The card page's 检查更新 row draws three different things (nothing / green dot / amber dot), so
 * the badge has to tell three states apart. Before the design's second pull it had two, and
 * "never checked" was indistinguishable from "you are up to date" — a green dot on that flow would
 * have claimed a check that never happened.
 */
class UpdateBadgeTest {

    private val cert = "b".repeat(64)

    private fun version(code: Int, name: String) = UpdateVersion(
        name, code, "com.hermes.remote", "internal", "2026-01-01T00:00:00Z",
        "Hermes-Remote-$name-debug.apk", "https://mrlgs.net/releases/Hermes-Remote-$name-debug.apk",
        3, "a".repeat(64), cert, 26, listOf("note"), "abcdef1",
    )

    private fun index(vararg versions: UpdateVersion) = UpdateIndex(
        schemaVersion = 1,
        channel = "internal",
        latestVersionCode = versions.maxOf { it.versionCode },
        generatedAt = "2026-01-01T00:00:00Z",
        versions = versions.toList(),
    )

    /** Answers `fetch` from a script; every other member is unreachable from the badge. */
    private class FakeRepo(private val answers: MutableList<Result<UpdateIndex>>) : UpdateRepositoryContract {
        var fetches = 0
        override suspend fun fetch(): UpdateIndex {
            fetches++
            val next = if (answers.size > 1) answers.removeAt(0) else answers.first()
            return next.getOrThrow()
        }
        override suspend fun enqueue(version: UpdateVersion): Long = error("unused")
        override suspend fun saved(): Pair<Long, UpdateVersion>? = error("unused")
        override suspend fun query(id: Long): DownloadSnapshot? = error("unused")
        override suspend fun verify(version: UpdateVersion, localUri: String): File = error("unused")
        override suspend fun cancel() = error("unused")
        override fun install(file: File): InstallResult = error("unused")
        override suspend fun localApkVersions(candidates: List<UpdateVersion>): Set<Int> = error("unused")
        override suspend fun pruneDownloads(keepFileNames: Set<String>) = error("unused")
        override suspend fun exportApk(version: UpdateVersion): ExportResult = error("unused")
    }

    private val newer = BuildConfig.VERSION_CODE + 1
    private val older = BuildConfig.VERSION_CODE - 1

    @Test fun `a newer release is offered by name`() = runTest {
        val badge = UpdateBadge(FakeRepo(mutableListOf(Result.success(index(version(newer, "9.9.9"))))))
        badge.refreshIfStale(nowMs = 1)
        assertEquals(UpdateBadgeState.Available("9.9.9"), badge.state.value)
    }

    @Test fun `the same version reads as up to date`() = runTest {
        val current = version(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
        val badge = UpdateBadge(FakeRepo(mutableListOf(Result.success(index(current)))))
        badge.refreshIfStale(nowMs = 1)
        assertEquals(UpdateBadgeState.UpToDate, badge.state.value)
    }

    /** A local build ahead of the index is still "nothing to install", not "update available". */
    @Test fun `an index behind this build reads as up to date`() = runTest {
        val badge = UpdateBadge(FakeRepo(mutableListOf(Result.success(index(version(older, "0.0.1"))))))
        badge.refreshIfStale(nowMs = 1)
        assertEquals(UpdateBadgeState.UpToDate, badge.state.value)
    }

    @Test fun `before any check the state is unknown, and a failed check leaves it there`() = runTest {
        val badge = UpdateBadge(FakeRepo(mutableListOf(Result.failure(IllegalStateException("offline")))))
        assertEquals(UpdateBadgeState.Unknown, badge.state.value)
        badge.refreshIfStale(nowMs = 1)
        assertEquals(UpdateBadgeState.Unknown, badge.state.value)
    }

    /** A later failure must not downgrade a good answer — the row keeps the last real conclusion. */
    @Test fun `a failure after a success keeps the previous conclusion`() = runTest {
        val repo = FakeRepo(
            mutableListOf(
                Result.success(index(version(newer, "9.9.9"))),
                Result.failure(IllegalStateException("offline")),
            ),
        )
        val badge = UpdateBadge(repo)
        badge.refreshIfStale(nowMs = 1)
        badge.refreshIfStale(nowMs = 1 + 2 * 60 * 60 * 1000)
        assertEquals(2, repo.fetches)
        assertEquals(UpdateBadgeState.Available("9.9.9"), badge.state.value)
    }

    @Test fun `a second check inside the throttle window does not refetch`() = runTest {
        val repo = FakeRepo(mutableListOf(Result.success(index(version(newer, "9.9.9")))))
        val badge = UpdateBadge(repo)
        badge.refreshIfStale(nowMs = 1)
        badge.refreshIfStale(nowMs = 2)
        assertEquals(1, repo.fetches)
    }
}
