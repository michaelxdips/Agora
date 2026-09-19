package com.newoether.agora.wear

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
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
    private class ScriptedSender(private val answers: List<AskOutcome>) : QuestionSender {
        val asked = mutableListOf<String>()
        private var index = 0

        override suspend fun ask(question: String, coreContext: String): AskOutcome {
            asked += question
            val answer = answers.getOrElse(index) { answers.last() }
            index += 1
            return answer
        }
    }

    private fun answer(text: String): AskOutcome = AskOutcome.Answer(text)
    private fun failure(message: String = "offline", retryable: Boolean = true) =
        AskOutcome.Failed(message, retryable = retryable)

    @Test
    fun `an empty queue is not a send`() = runTest {
        val sender = ScriptedSender(listOf(answer("x")))

        val report = WearQueueDrainer.drain(queue, sender, "", showResult = true)

        assertEquals(0, report.delivered)
        assertTrue(sender.asked.isEmpty())
    }

    @Test
    fun `held questions are delivered oldest first`() = runTest {
        queue.enqueue("first")
        queue.enqueue("second")
        queue.enqueue("third")
        val sender = ScriptedSender(listOf(answer("a"), answer("b"), answer("c")))

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
        val sender = ScriptedSender(listOf(answer("the answer")))

        val report = WearQueueDrainer.drain(queue, sender, "", showResult = true)

        assertEquals(1, report.delivered)
        assertEquals("the answer", report.lastAnswer)
        assertEquals(0, queue.size())
    }

    @Test
    fun `showResult false never lets an old answer win`() = runTest {
        queue.enqueue("old question")
        val sender = ScriptedSender(listOf(answer("old answer")))

        val report = WearQueueDrainer.drain(queue, sender, "", showResult = false)

        assertEquals(1, report.delivered)
        assertNull(report.lastAnswer)
    }

    @Test
    fun `the first failure stops the pass so the rest keep their attempts`() = runTest {
        queue.enqueue("one")
        queue.enqueue("two")
        val sender = ScriptedSender(listOf(answer("ok"), failure("offline")))

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
        val sender = ScriptedSender(List(WearOfflineQueue.MAX_ATTEMPTS) { failure() })

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
    fun `a permanent failure leaves the queue at once and is not retried`() = runTest {
        // The revoked-key case: three attempts and three provider calls on a 401 that can never
        // succeed, and then the question was deleted as if the network had been down.
        queue.enqueue("will never work")
        val sender = ScriptedSender(List(3) { failure("HTTP 401", retryable = false) })

        val report = WearQueueDrainer.drain(queue, sender, "", showResult = false)

        assertEquals("a permanent failure must be reported as dropped", "will never work", report.droppedText)
        assertEquals(0, queue.size())
        assertEquals("exactly one provider call, not three", 1, sender.asked.size)
        // …and the text is kept, so an expired key does not silently destroy the question.
        assertEquals(listOf("will never work"), queue.deadLetters().map { it.text })
    }

    @Test
    fun `a transient failure is still retried up to the ceiling`() = runTest {
        queue.enqueue("flaky")
        val sender = ScriptedSender(List(3) { failure("HTTP 503", retryable = true) })

        val first = WearQueueDrainer.drain(queue, sender, "", showResult = false)
        assertNull("a retryable failure must not be dropped on the first pass", first.droppedText)
        assertEquals(1, queue.size())

        val second = WearQueueDrainer.drain(queue, sender, "", showResult = false)
        assertNull(second.droppedText)

        val third = WearQueueDrainer.drain(queue, sender, "", showResult = false)
        assertEquals("flaky", third.droppedText)
        assertEquals(0, queue.size())
    }

    @Test
    fun `a Retry-After is honoured before the pass gives up, and reported`() = runTest {
        // A 429 without the server's back-off is a 429 the watch hits again immediately.
        queue.enqueue("throttled")
        val sender = ScriptedSender(
            listOf(AskOutcome.Failed("HTTP 429", retryable = true, retryAfterMs = 5_000L))
        )
        val waited = mutableListOf<Long>()

        val report = WearQueueDrainer.drain(
            queue = queue,
            sender = sender,
            coreContext = "",
            showResult = false,
            onWait = { waited += it },
        )

        assertEquals(listOf(5_000L), waited)
        assertEquals(0, report.delivered)
        assertEquals("the question stays held for the retry", 1, queue.size())
    }

    @Test
    fun `a Retry-After is persisted on the entry, so the next pass does not retry at once`() = runTest {
        // HERMES INTEGRATION POINT (Session 4): the drain used to `sleep(wait)` inside its own lock.
        // That throttled nothing — the sleep ended, the pass gave up, and the next drain retried the
        // same entry immediately — while holding the send lock (and the UI's "Sending…" state) for
        // the whole wait. The deadline is now written to the queue entry, so the *next* pass, with
        // no memory of the first, still refuses to send it. Mutation that makes this red: drop the
        // `notBefore` argument from `recordFailure`, or remove the `notBefore > now` skip in `drain`.
        queue.enqueue("throttled")
        val sender = ScriptedSender(
            listOf(
                AskOutcome.Failed("HTTP 429", retryable = true, retryAfterMs = 5_000L),
                AskOutcome.Answer("should not be sent yet"),
            )
        )

        WearQueueDrainer.drain(queue, sender, "", showResult = false)

        // The stored deadline must be in the future, not merely present.
        val held = queue.all().single()
        assertTrue(
            "the Retry-After must be persisted as a future deadline (was ${held.notBefore})",
            held.notBefore != null && held.notBefore > System.currentTimeMillis(),
        )

        // A second pass — the state that used to retry immediately — must send nothing.
        val second = WearQueueDrainer.drain(queue, sender, "", showResult = false)
        assertEquals("a throttled entry must not be retried by the next pass", 0, second.delivered)
        assertEquals("the sender must not have been called again", 1, sender.asked.size)
    }

    @Test
    fun `an entry past its Retry-After deadline is sent again`() = runTest {
        // The other half of the contract: the throttle is a deadline, not a tombstone. An entry
        // whose wait has elapsed goes out on the next pass. Mutation: make the skip unconditional
        // (`if (entry.notBefore != null) break`) and this goes red.
        queue.enqueue("throttled")
        val sender = ScriptedSender(
            listOf(
                AskOutcome.Failed("HTTP 429", retryable = true, retryAfterMs = 1L),
                AskOutcome.Answer("delivered after the wait"),
            )
        )

        WearQueueDrainer.drain(queue, sender, "", showResult = false)
        Thread.sleep(20L)   // let the 1 ms deadline pass
        val second = WearQueueDrainer.drain(queue, sender, "", showResult = false)

        assertEquals(1, second.delivered)
        assertEquals(0, queue.size())
    }

    @Test
    fun `a hostile Retry-After cannot park the pass for a day`() = runTest {
        queue.enqueue("throttled")
        val sender = ScriptedSender(
            listOf(AskOutcome.Failed("HTTP 429", retryable = true, retryAfterMs = 86_400_000L))
        )
        val waited = mutableListOf<Long>()

        WearQueueDrainer.drain(
            queue = queue,
            sender = sender,
            coreContext = "",
            showResult = false,
            onWait = { waited += it },
        )

        assertEquals(listOf(WearQueueDrainer.MAX_RETRY_AFTER_MS), waited)
        val held = queue.all().single()
        // Bounded on disk too, not only in the callback: a hostile header cannot park the queue for
        // a day. Ceiling + a generous slack for test execution time.
        assertTrue(held.notBefore!! - System.currentTimeMillis() <= WearQueueDrainer.MAX_RETRY_AFTER_MS)
    }

    @Test
    fun `a non-retryable failure is not waited on`() = runTest {
        queue.enqueue("unauthorized")
        val sender = ScriptedSender(
            listOf(AskOutcome.Failed("HTTP 401", retryable = false, retryAfterMs = 5_000L))
        )
        val waited = mutableListOf<Long>()

        WearQueueDrainer.drain(
            queue = queue,
            sender = sender,
            coreContext = "",
            showResult = false,
            onWait = { waited += it },
        )

        assertTrue("a permanent failure must not be waited on", waited.isEmpty())
    }

    @Test
    fun `an answer that arrived is removed from the queue even if the next one fails`() = runTest {
        queue.enqueue("answered")
        queue.enqueue("unanswered")
        val sender = ScriptedSender(listOf(answer("yes"), failure("offline")))

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
            answer("ok")
        }

        WearQueueDrainer.drain(queue, sender, "core-context", showResult = false)

        assertEquals(listOf("core-context", "core-context"), seen)
    }

    @Test
    fun `every drained answer is reported, not only the last one`() = runTest {
        // A-021: the first version kept only the newest answer and deleted the rest, so a user with
        // 3 held questions saw 1 answer and had no way to tell the other 2 had been answered at all.
        queue.enqueue("one")
        queue.enqueue("two")
        queue.enqueue("three")
        val sender = ScriptedSender(listOf(answer("a1"), answer("a2"), answer("a3")))

        val report = WearQueueDrainer.drain(queue, sender, "", showResult = true)

        assertEquals(listOf("a1", "a2", "a3"), report.answers)
        assertEquals("a3", report.lastAnswer)
        assertEquals(2, report.answersNotShown)
    }

    @Test
    fun `concurrent passes never send the same held question twice`() = runTest {
        // A-020: the snapshot is taken outside the queue's mutex, so launch-drain and send-drain
        // running at the same time used to both see the same entry and both send it.
        queue.enqueue("only once")
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sender = QuestionSender { question, _ ->
            asked += question
            kotlinx.coroutines.delay(50)
            answer("answer")
        }

        val scope: kotlinx.coroutines.CoroutineScope = this
        val passes = List(4) {
            scope.async { WearQueueDrainer.drain(queue, sender, "", showResult = false) }
        }
        val total = passes.sumOf { it.await().delivered }

        assertEquals(listOf("only once"), asked.toList())
        assertEquals(1, total)
        assertEquals(0, queue.size())
    }

    @Test
    fun `the outcome bridge keeps the retry verdict from the client`() {
        // `WearChatClient` answers with a Result; the drainer needs the verdict and the back-off. The
        // translation lives in `AskOutcome.from`, so a change to either shape fails a test rather than
        // silently downgrading a permanent failure into a retryable one.
        val permanent = AskOutcome.from(
            Result.failure(WearChatException("HTTP 401", WearChatException.Kind.HTTP_STATUS, 401))
        ) as AskOutcome.Failed
        assertTrue(!permanent.retryable)

        val throttled = AskOutcome.from(
            Result.failure(
                WearChatException("HTTP 429", WearChatException.Kind.HTTP_STATUS, 429, 3_000L)
            )
        ) as AskOutcome.Failed
        assertTrue(throttled.retryable)
        assertEquals(3_000L, throttled.retryAfterMs)

        val success = AskOutcome.from(Result.success("hello")) as AskOutcome.Answer
        assertEquals("hello", success.text)
    }
}
