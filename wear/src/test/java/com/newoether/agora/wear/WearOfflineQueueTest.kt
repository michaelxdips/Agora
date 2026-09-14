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

    @Before
    fun setUp() {
        queueDir = temporaryFolder.newFolder("files")
        queue = WearOfflineQueue(context(queueDir))
    }

    @Test
    fun `a queued question survives a new queue instance`() = runTest {
        val entry = queue.enqueue("what is my project?", now = 1000L)

        val reopened = WearOfflineQueue(context(queueDir))
        assertEquals(1, reopened.size())
        assertEquals("what is my project?", reopened.all().single().text)
        assertEquals(entry.id, reopened.all().single().id)
        assertEquals(1000L, reopened.all().single().createdAt)
    }

    @Test
    fun `ids are monotonic and stable across restarts`() = runTest {
        val first = queue.enqueue("one")
        val second = queue.enqueue("two")
        assertTrue(second.id > first.id)
        assertEquals(listOf(first.id, second.id), queue.all().map { it.id })
    }

    @Test
    fun `completing removes exactly the sent entry`() = runTest {
        val first = queue.enqueue("one")
        val second = queue.enqueue("two")

        assertTrue(queue.complete(first.id))
        assertEquals(listOf("two"), queue.all().map { it.text })
        assertFalse("completing twice must not report success", queue.complete(first.id))
        assertEquals(1, queue.size())
        assertTrue(queue.all().any { it.id == second.id })
    }

    @Test
    fun `a failing entry is dropped only after the attempt ceiling`() = runTest {
        val entry = queue.enqueue("flaky")

        repeat(WearOfflineQueue.MAX_ATTEMPTS - 1) {
            assertFalse("dropped too early", queue.recordFailure(entry.id))
            assertEquals(1, queue.size())
        }
        assertTrue("must drop at the ceiling", queue.recordFailure(entry.id))
        assertEquals(0, queue.size())
    }

    @Test
    fun `recordFailure on an unknown id is a no-op`() = runTest {
        queue.enqueue("one")
        assertFalse(queue.recordFailure(999L))
        assertEquals(1, queue.size())
    }

    @Test
    fun `a corrupt queue file reads as empty instead of throwing`() = runTest {
        java.io.File(queueDir, WearOfflineQueue.FILE_NAME).writeText("{not json")
        val broken = WearOfflineQueue(context(queueDir))
        assertEquals(0, broken.size())
        // And it must still be usable afterwards.
        broken.enqueue("recovered")
        assertEquals(1, broken.size())
    }

    @Test
    fun `clear empties the queue`() = runTest {
        queue.enqueue("one")
        queue.enqueue("two")
        queue.clear()
        assertEquals(0, queue.size())
    }
}
