package com.newoether.agora.wear

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The duplicate-send guard.
 *
 * A double tap on a 40 px watch button ran two full send paths: two queue entries, two provider
 * calls, two charges, and the second answer overwriting the first on screen. This is the test that
 * pins the fix — and it also pins the two things the guard must *not* do, because a guard that
 * blocks a different question, or that wedges after a cancellation, is worse than the double send.
 */
class WearSendCoordinatorTest {

    @Test
    fun `the same question cannot be sent twice at once`() = runTest {
        val runs = mutableListOf<String>()

        val passes = coroutineScope {
            List(4) {
                async {
                    WearSendCoordinator.send("what is my project?") {
                        runs += "started"
                        delay(50)
                        "answer"
                    }
                }
            }
        }.map { it.await() }

        assertEquals("exactly one send must run", 1, runs.size)
        assertEquals(1, passes.count { it is WearSendCoordinator.Outcome.Ran })
        assertEquals(3, passes.count { it is WearSendCoordinator.Outcome.Duplicate })
    }

    @Test
    fun `a duplicate is reported rather than silently dropped`() = runTest {
        // The UI says "Already sending that question". A tap that does nothing is indistinguishable
        // from a broken button, and the user tapped twice on purpose.
        val first = async { WearSendCoordinator.send("q") { delay(30); "a" } }
        delay(10)
        val second = WearSendCoordinator.send("q") { "b" }

        assertTrue(first.await() is WearSendCoordinator.Outcome.Ran)
        assertTrue("a duplicate must be visible to the caller", second is WearSendCoordinator.Outcome.Duplicate)
    }

    @Test
    fun `a different question is never blocked`() = runTest {
        val order = mutableListOf<String>()

        val first = async {
            WearSendCoordinator.send("first") { delay(30); order += "first"; "a" }
        }
        delay(5)
        val second = async {
            WearSendCoordinator.send("second") { order += "second"; "b" }
        }

        assertTrue(first.await() is WearSendCoordinator.Outcome.Ran)
        assertTrue("a different question must go through", second.await() is WearSendCoordinator.Outcome.Ran)
        assertEquals(listOf("second", "first"), order)
    }

    @Test
    fun `whitespace does not make it a different question`() = runTest {
        val first = async { WearSendCoordinator.send("hello") { delay(30); "a" } }
        delay(10)

        val padded = WearSendCoordinator.send("  hello  ") { "b" }

        assertTrue(padded is WearSendCoordinator.Outcome.Duplicate)
        first.await()
    }

    @Test
    fun `a cancelled send releases the guard`() = runTest {
        // The composition scope dies on wrist-down. If the guard were not released in a `finally`, the
        // question would be permanently "in flight" and every later attempt at the same text would be
        // silently swallowed — the dead end this whole file exists to prevent.
        val thrown = runCatching {
            WearSendCoordinator.send("doomed") {
                throw kotlinx.coroutines.CancellationException("wrist down")
            }
        }
        assertTrue(thrown.exceptionOrNull() is kotlinx.coroutines.CancellationException)

        val retry = WearSendCoordinator.send("doomed") { "second attempt" }

        assertTrue("the guard must be free after a cancellation", retry is WearSendCoordinator.Outcome.Ran)
        assertEquals("second attempt", (retry as WearSendCoordinator.Outcome.Ran).value)
    }

    @Test
    fun `a failed send also releases the guard`() = runTest {
        runCatching { WearSendCoordinator.send("boom") { throw java.io.IOException("network") } }

        val retry = WearSendCoordinator.send("boom") { "ok" }

        assertTrue(retry is WearSendCoordinator.Outcome.Ran)
    }

    @Test
    fun `the guard reports what is in flight`() = runTest {
        assertTrue(WearSendCoordinator.inFlightCount() == 0)
        val running = async { WearSendCoordinator.send("q") { delay(30); "a" } }
        delay(10)

        assertTrue(WearSendCoordinator.isInFlight("q"))
        assertEquals(1, WearSendCoordinator.inFlightCount())

        running.await()
        assertTrue(!WearSendCoordinator.isInFlight("q"))
        assertEquals(0, WearSendCoordinator.inFlightCount())
    }
}
