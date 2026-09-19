package com.newoether.agora.wear

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The offline queue's exactly-once contract.
 *
 * A watch asks a question on a train with no signal. The question must survive, be sent once when the
 * signal returns, and never be silently dropped — and a question that keeps failing must not block
 * every question behind it forever.
 */
class WearOfflineQueueTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var queue: WearOfflineQueue
    private lateinit var queueDir: java.io.File

    private fun context(filesDir: java.io.File): android.content.Context {
        val context = io.mockk.mockk<android.content.Context>(relaxed = true)
        io.mockk.every { context.filesDir } returns filesDir
        return context
    }

    /**
     * `enqueue` returns null when the queue cannot be persisted (Session 5). Every test here writes
     * into a writable temp dir, so a null is a failure of the test's own premise — asserted once,
     * here, instead of scattering `!!` through the suite.
     */
    private suspend fun WearOfflineQueue.enqueueOrFail(
        text: String,
        now: Long = System.currentTimeMillis(),
    ): WearOfflineQueue.Entry =
        enqueue(text, now) ?: error("enqueue failed to persist into a writable directory")

    @Before
    fun setUp() {
        queueDir = temporaryFolder.newFolder("files")
        queue = WearOfflineQueue(context(queueDir))
    }

    @Test
    fun `a queued question survives a new queue instance`() = runTest {
        val entry = queue.enqueueOrFail("what is my project?", now = 1000L)

        val reopened = WearOfflineQueue(context(queueDir))
        assertEquals(1, reopened.size())
        assertEquals("what is my project?", reopened.all().single().text)
        assertEquals(entry.id, reopened.all().single().id)
        assertEquals(1000L, reopened.all().single().createdAt)
    }

    @Test
    fun `ids are monotonic and stable across restarts`() = runTest {
        val first = queue.enqueueOrFail("one")
        val second = queue.enqueueOrFail("two")
        assertTrue(second.id > first.id)
        assertEquals(listOf(first.id, second.id), queue.all().map { it.id })
    }

    @Test
    fun `completing removes exactly the sent entry`() = runTest {
        val first = queue.enqueueOrFail("one")
        val second = queue.enqueueOrFail("two")

        assertTrue(queue.complete(first.id))
        assertEquals(listOf("two"), queue.all().map { it.text })
        assertFalse("completing twice must not report success", queue.complete(first.id))
        assertEquals(1, queue.size())
        assertTrue(queue.all().any { it.id == second.id })
    }

    @Test
    fun `a failing entry is dropped only after the attempt ceiling`() = runTest {
        val entry = queue.enqueueOrFail("flaky")

        repeat(WearOfflineQueue.MAX_ATTEMPTS - 1) {
            assertFalse("dropped too early", queue.recordFailure(entry.id))
            assertEquals(1, queue.size())
        }
        assertTrue("must drop at the ceiling", queue.recordFailure(entry.id))
        assertEquals(0, queue.size())
    }

    @Test
    fun `recordFailure on an unknown id is a no-op`() = runTest {
        queue.enqueueOrFail("one")
        assertFalse(queue.recordFailure(999L))
        assertEquals(1, queue.size())
    }

    @Test
    fun `a corrupt queue file reads as empty instead of throwing`() = runTest {
        java.io.File(queueDir, WearOfflineQueue.FILE_NAME).writeText("{not json")
        val broken = WearOfflineQueue(context(queueDir))
        assertEquals(0, broken.size())
        // And it must still be usable afterwards.
        broken.enqueueOrFail("recovered")
        assertEquals(1, broken.size())
    }

    @Test
    fun `a corrupt queue file is quarantined rather than overwritten`() = runTest {
        // The first version read a corrupt file as "empty" and left it in place, so the next enqueue
        // wrote a one-entry queue over it and every held question was gone. Unreadable is not empty.
        val queueFile = java.io.File(queueDir, WearOfflineQueue.FILE_NAME)
        queueFile.writeText("[{\"id\":1,\"text\":\"held\",\"createdAt\":1}")   // truncated mid-array
        val broken = WearOfflineQueue(context(queueDir))

        assertEquals(0, broken.size())

        val quarantined = java.io.File(queueDir, WearOfflineQueue.FILE_NAME + ".corrupt")
        assertTrue("the unreadable bytes must be kept, not deleted", quarantined.isFile)
        assertEquals("[{\"id\":1,\"text\":\"held\",\"createdAt\":1}", quarantined.readText())

        broken.enqueueOrFail("recovered")
        assertEquals(1, broken.size())
    }

    @Test
    fun `a dropped question is kept in the dead letter rather than deleted`() = runTest {
        // Before this, `recordFailure` at the ceiling removed the entry and the text went with it: a
        // user whose key expired lost every question they had asked offline, with one error line as
        // the only trace.
        val entry = queue.enqueueOrFail("please do not lose me")
        repeat(WearOfflineQueue.MAX_ATTEMPTS - 1) { queue.recordFailure(entry.id) }

        assertTrue(queue.recordFailure(entry.id))
        assertEquals(0, queue.size())
        assertEquals(listOf("please do not lose me"), queue.deadLetters().map { it.text })
        assertEquals(entry.id, queue.deadLetters().single().id)
    }

    @Test
    fun `a permanent failure dead-letters immediately instead of spending attempts`() = runTest {
        val entry = queue.enqueueOrFail("revoked key")

        assertTrue("a permanent failure leaves at once", queue.recordFailure(entry.id, permanent = true))
        assertEquals(0, queue.size())
        assertEquals(1, queue.deadLetters().single().attempts)
    }

    @Test
    fun `the dead letter is bounded so it cannot fill the watch`() = runTest {
        repeat(WearOfflineQueue.MAX_DEAD_LETTER + 5) { index ->
            val entry = queue.enqueueOrFail("question $index")
            queue.recordFailure(entry.id, permanent = true)
        }

        assertEquals(WearOfflineQueue.MAX_DEAD_LETTER, queue.deadLetters().size)
        // The newest is kept, the oldest is evicted.
        assertEquals(
            "question ${WearOfflineQueue.MAX_DEAD_LETTER + 4}",
            queue.deadLetters().last().text,
        )
    }

    @Test
    fun `the queue itself is bounded and keeps the newest questions`() = runTest {
        // A queue that grows without a ceiling is a queue that eventually fails to write — and then
        // loses everything. The user is waiting on the newest question, so the oldest is evicted.
        repeat(WearOfflineQueue.MAX_ENTRIES + 3) { index -> queue.enqueueOrFail("q$index") }

        assertEquals(WearOfflineQueue.MAX_ENTRIES, queue.size())
        assertEquals("q${WearOfflineQueue.MAX_ENTRIES + 2}", queue.all().last().text)
        assertTrue(queue.all().none { it.text == "q0" })
    }

    @Test
    fun `a write leaves no temp file behind`() = runTest {
        // The atomic write is temp-then-rename; a leftover temp file would be re-read on the next
        // launch by anything that globs the directory.
        queue.enqueueOrFail("one")
        queue.complete(queue.all().single().id)

        val leftovers = queueDir.listFiles().orEmpty().map { it.name }
        assertTrue("temp file left behind: $leftovers", leftovers.none { it.endsWith(".tmp") })
        assertTrue(
            "the queue file itself must still exist: $leftovers",
            leftovers.contains(WearOfflineQueue.FILE_NAME),
        )
    }

}
