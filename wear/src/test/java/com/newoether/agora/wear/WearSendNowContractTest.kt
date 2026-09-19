package com.newoether.agora.wear

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The "Send now" contract: **one tap, one provider call.**
 *
 * The bug this pins (found in the Session-4 audit, fixed in `WearMainActivity`): the queued
 * question's card has a *Send now* button whose handler called `send(entry.text)`. That looks
 * right and is not:
 *
 *  1. `send` → `sendOnce` **enqueues a new entry** for the same text (new id);
 *  2. the original entry is still in the queue, untouched;
 *  3. the send succeeds, and `sendOnce`'s success branch calls `drainQueue`, which sends the
 *     *original* entry as well.
 *
 * So a user who tapped the button once paid for two provider calls and saw the answer overwrite
 * itself. The fix completes the original entry before the new send starts.
 *
 * These tests exercise the queue-level contract the fix depends on (complete-then-send is a
 * single delivery), plus the composition the UI now performs, without needing a device.
 */
class WearSendNowContractTest {

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

    private class CountingSender(private val answer: String) : QuestionSender {
        val asked = mutableListOf<String>()
        override suspend fun ask(question: String, coreContext: String): AskOutcome {
            asked += question
            return AskOutcome.Answer(answer)
        }
    }

    @Test
    fun `the pre-fix shape double-sends, the drain still delivers the original`() = runTest {
        // The regression itself, kept as a test so the failure mode is documented executably.
        // The pre-fix handler was: leave the entry, `send(text)` — which enqueues a copy, sends it
        // directly, completes it — and then the success branch's drain picks up the *original*.
        val original = queue.enqueue("what is my project?")
        val sender = CountingSender("answer")

        // what `sendOnce` does: enqueue a copy, send it directly (the provider call), complete it
        val copy = queue.enqueue("what is my project?")
        queue.complete(copy.id)   // the direct send succeeded

        // ...and now the drain that `sendOnce` runs on success. This is the second provider call.
        val drain = WearQueueDrainer.drain(queue, sender, "", showResult = false)

        assertEquals("the pre-fix drain re-sent the original entry", 1, drain.delivered)
        assertEquals(listOf("what is my project?"), sender.asked)
        assertTrue("the original survived into the drain", original.id < copy.id)
    }

    @Test
    fun `the fixed shape sends once, completing the original then sending it`() = runTest {
        // What the handler does now, in the same order: complete the original first, then the new
        // send enqueues exactly one entry which is delivered by its own path. A later drain finds
        // nothing left to re-send.
        val original = queue.enqueue("what is my project?")
        assertTrue(queue.complete(original.id))

        // `sendOnce`'s own send, modelled at the queue level: enqueue → deliver → complete.
        val fresh = queue.enqueue("what is my project?")
        val sender = CountingSender("answer")
        queue.complete(fresh.id)

        // The drain that used to pick up the original: nothing to send now.
        val drain = WearQueueDrainer.drain(queue, sender, "", showResult = false)
        assertEquals("a fixed tap must leave nothing for the drain", 0, drain.delivered)
        assertEquals("a fixed tap must not call the provider again", 0, sender.asked.size)
        assertEquals("the queue must be empty after one tap", 0, queue.size())
    }

    @Test
    fun `a failed send-now still leaves the question durably held`() = runTest {
        // The fix must not trade a double-send for a lost question: if the new send fails, the
        // entry `sendOnce` enqueued is still in the queue.
        val original = queue.enqueue("what is my project?")
        queue.complete(original.id)

        val fresh = queue.enqueue("what is my project?")   // sendOnce's durable enqueue
        queue.recordFailure(fresh.id, permanent = false)   // the send failed (offline)

        val held = queue.all()
        assertEquals("the question must survive a failed send-now", 1, held.size)
        assertEquals("what is my project?", held.single().text)
    }
}
