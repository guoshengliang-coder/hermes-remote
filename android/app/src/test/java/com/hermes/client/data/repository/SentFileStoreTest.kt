package com.hermes.client.data.repository

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SentFileStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val record = SentFileRecord(listOf("/Users/test/report.pdf", "report.pdf"),
        "/Users/test/report.pdf", "报告.pdf", "application/pdf", "hash-file.pdf")

    @Test fun recreation_preserves_names_and_aliases_without_using_scope_as_a_path() {
        val directory = temporary.newFolder("index")
        SentFileStore(directory).put("account/../../profile/session", record)
        assertEquals(listOf(record), SentFileStore(directory).read("account/../../profile/session"))
        assertTrue(SentFileStore(directory).read("another-account").isEmpty())
        assertTrue(directory.listFiles()!!.single().name.matches(Regex("[0-9a-f]{64}\\.json")))
    }

    @Test fun corrupt_index_and_unavailable_storage_are_cache_misses() {
        val directory = temporary.newFolder("corrupt")
        val store = SentFileStore(directory)
        store.put("scope", record)
        directory.listFiles()!!.single().writeText("{unfinished")
        assertTrue(store.read("scope").isEmpty())
        val blocked = SentFileStore(temporary.newFile("not-a-directory"))
        blocked.put("scope", record)
        assertTrue(blocked.read("scope").isEmpty())
        assertFalse(File(directory, "../scope.json").exists())
    }
}
