package com.newoether.agora.wear

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression tests for the drain pass.
 *
 * This is the test the P0 work did not have: `drainQueue` used to be a local function inside a
 * composable, so the launch-drain bug — a held question that was only ever delivered if the user
 * happened to ask a second question that also succeeded — had no test that could have caught it.
 * Extracting [WearQueueDrainer] is what makes these assertions possible; they are the reason the
 * extraction is not just a refactor.
 */
class WearQueueDrainerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var queue: WearOfflineQueue

    private fun context(filesDir: java.io.File): android.content.Context {
        val context = io.mockk.mockk<android.content.Context>(relaxed = true)
        io.mockk.every { context.filesDir } returns filesDir
        return context
    }

    @Before
    fun setUp() {
        queue = WearOfflineQueue(context(temporaryFolder.newFolder("files")))
    }

    /** Records every question it is asked and answers from a script. */
    private class ScriptedSender(private val answers: List<Result<String>>) : QuestionSender {
        val asked = mutableListOf<String>()
        private var index = 0

        override suspend fun ask(question: String, coreContext: String): Result<String> {
            asked += question
            val answer = answers.getOrElse(index) { answers.last() }
            index += 1
            return answer
        }
    }

    @Test
    fun `an empty queue is not a send`() = runTest {
        val sender = ScriptedSender(listOf(Result.success("x")))

        val report = WearQueueDrainer.drain(queue, sender, "", showResult = true)

        assertEquals(0, report.delivered)
        assertTrue(sender.asked.isEmpty())
    }

    @Test
    fun `held questions are delivered oldest first`() = runTest {
        queue.enqueue("first")
        queue.enqueue("second")
        queue.enqueue("third")
        val sender = ScriptedSender(listOf(Result.success("a"), Result.success("b"), Result.success("c")))

        val report = WearQueueDrainer.drain(queue, sender, "", showResult = false)

        assertEquals(3, report.delivered)
        assertEquals(listOf("first", "second", "third"), sender.asked)
        assertEquals(0, queue.size())
    }

    @Test
    fun `launch drain delivers a held question with no new question asked`() = runTest {
        // The exact regression: one held question, app opened, nothing else happens. Before the fix
        // this was 0 delivered and the question stayed on disk forever.
        queue.enqueue("held while offline")
        val sender = ScriptedSender(listOf(Result.success("the answer")))

        val report = WearQueueDrainer.drain(queue, sender, "", showResult = true)

        assertEquals(1, report.delivered)
        assertEquals("the answer", report.lastAnswer)
        assertEquals(0, queue.size())
    }

    @Test
    fun `showResult false never lets an old answer win`() = runTest {
        queue.enqueue("old question")
        val sender = ScriptedSender(listOf(Result.success("old answer")))

        val report = WearQueueDrainer.drain(queue, sender, "", showResult = false)

        assertEquals(1, report.delivered)
        assertNull(report.lastAnswer)
    }

    @Test
    fun `the first failure stops the pass so the rest keep their attempts`() = runTest {
        queue.enqueue("one")
        queue.enqueue("two")
        val sender = ScriptedSender(
            listOf(Result.success("ok"), Result.failure(java.io.IOException("offline"))),
        )

        val report = WearQueueDrainer.drain(queue, sender, "", showResult = false)

        assertEquals(1, report.delivered)
        assertEquals(listOf("one", "two"), sender.asked)
        // "two" is still held, with one attempt spent — not dropped, not skipped.
        val remaining = queue.all()
        assertEquals(1, remaining.size)
        assertEquals("two", remaining.single().text)
        assertEquals(1, remaining.single().attempts)
    }

    @Test
    fun `a question is dropped only at the attempt ceiling, and the drop is reported`() = runTest {
        queue.enqueue("doomed")
        val failure = Result.failure<String>(java.io.IOException("offline"))
        val sender = ScriptedSender(List(WearOfflineQueue.MAX_ATTEMPTS) { failure })

        // MAX_ATTEMPTS - 1 failures: still held, nothing reported as dropped.
        repeat(WearOfflineQueue.MAX_ATTEMPTS - 1) {
            val report = WearQueueDrainer.drain(queue, sender, "", showResult = false)
            assertNull(report.droppedText)
            assertEquals(1, queue.size())
        }

        val finalReport = WearQueueDrainer.drain(queue, sender, "", showResult = false)

        assertEquals("doomed", finalReport.droppedText)
        assertEquals(0, queue.size())
    }

    @Test
    fun `an answer that arrived is removed from the queue even if the next one fails`() = runTest {
        queue.enqueue("answered")
        queue.enqueue("unanswered")
        val sender = ScriptedSender(
            listOf(Result.success("yes"), Result.failure(java.io.IOException("offline"))),
        )

        WearQueueDrainer.drain(queue, sender, "", showResult = false)

        // The delivered question is gone; the failed one is still there to be retried. A drain that
        // removed nothing on failure would re-send "answered" forever.
        assertEquals(listOf("unanswered"), queue.all().map { it.text })
    }

    @Test
    fun `the pass hands the core context to every question`() = runTest {
        queue.enqueue("a")
        queue.enqueue("b")
        val seen = mutableListOf<String>()
        val sender = QuestionSender { _, core ->
            seen += core
            Result.success("ok")
        }

        WearQueueDrainer.drain(queue, sender, "core-context", showResult = false)

        assertEquals(listOf("core-context", "core-context"), seen)
    }
}
