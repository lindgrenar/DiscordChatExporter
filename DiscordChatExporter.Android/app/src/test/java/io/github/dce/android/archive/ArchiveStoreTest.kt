package io.github.dce.android.archive

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class ArchiveStoreTest {
    private val root = Files.createTempDirectory("dce-archive").toFile()
    private val store = ArchiveStore(root)

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun entryFor(file: java.io.File, createdAt: Instant) = ArchiveEntry(
        id = file.parentFile!!.name,
        channelId = "100",
        channelName = "alice",
        fileName = file.name,
        mimeType = "text/html",
        createdAt = createdAt,
        messages = 10,
        assets = 2,
        ownerId = "1",
        ownMessages = 5,
        lastMessageId = "123456789012345678",
    )

    @Test
    fun committedExportsAreListedNewestFirst() {
        val first = store.begin("a.html", Instant.parse("2024-01-01T00:00:00Z")).apply { writeText("<html>a") }
        val second = store.begin("b.html", Instant.parse("2024-01-01T00:00:00Z")).apply { writeText("<html>b") }
        // Same timestamp: still gets its own folder
        assertTrue(first.parentFile != second.parentFile)

        store.commit(entryFor(first, Instant.parse("2024-01-01T00:00:00Z")))
        store.commit(entryFor(second, Instant.parse("2024-02-01T00:00:00Z")))

        val listed = store.list()
        assertEquals(listOf(second.parentFile!!.name, first.parentFile!!.name), listed.map { it.id })
        assertEquals(entryFor(first, Instant.parse("2024-01-01T00:00:00Z")), store.get(first.parentFile!!.name))
        assertEquals("<html>b", store.file(listed[0]).readText())
    }

    @Test
    fun incompleteExportsAreHiddenAndCleanedUp() {
        val partial = store.begin("partial.html").apply { writeText("half") }
        assertTrue(store.list().isEmpty())
        store.removeIncomplete()
        assertFalse(partial.parentFile!!.exists())

        val aborted = store.begin("aborted.html").apply { writeText("half") }
        store.abort(aborted)
        assertFalse(aborted.parentFile!!.exists())
    }

    @Test
    fun updateAndDelete() {
        val file = store.begin("a.html").apply { writeText("x") }
        val entry = entryFor(file, Instant.now())
        store.commit(entry)

        val completedAt = Instant.parse("2024-03-01T00:00:00Z")
        store.update(entry.id) { it.copy(deletedMessages = 5, deletionCompletedAt = completedAt) }
        assertEquals(5L, store.get(entry.id)!!.deletedMessages)
        assertEquals(completedAt, store.get(entry.id)!!.deletionCompletedAt)

        store.delete(entry.id)
        assertNull(store.get(entry.id))
        assertNull(store.update(entry.id) { it })
    }

    @Test
    fun resolveOnlyServesArchivedExports() {
        val file = store.begin("a.html").apply { writeText("x") }
        store.commit(entryFor(file, Instant.now()))
        val id = file.parentFile!!.name

        assertEquals(file.canonicalFile, store.resolve(id, "a.html"))
        assertNull(store.resolve(id, "meta.json"))
        java.io.File(root, "outside.txt").writeText("secret")
        assertNull(store.resolve(id, "../outside.txt"))
        assertNull(store.resolve("..", "a.html"))
        assertNull(store.resolve(id, "missing.html"))
    }
}
