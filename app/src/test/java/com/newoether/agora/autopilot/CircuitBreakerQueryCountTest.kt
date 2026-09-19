package com.newoether.agora.autopilot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * How many DAO round-trips one correction pass costs.
 *
 * The circuit breaker runs on every reflection pass, and the reflection pass runs on every session
 * that reaches 20 messages. `recordCorrection` asked the journal for `injectionsFor(entry.id)` once
 * **per entry in the log**, so the cost of one pass grew with the size of the whole adaptation
 * history rather than with the number of corrections being processed. On a phone that is Room doing
 * a query per journal row, per pass.
 *
 * This is a count, not a wall-clock time: a millisecond number would depend on the machine and the
 * Room cache, while the number of queries is the thing the code actually controls. A count that
 * regresses is the bug coming back, and it fails here rather than in a user's battery stats.
 */
class CircuitBreakerQueryCountTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Delegates to [FakeAdaptationLogDao] and counts the queries that scale with history size. */
    private class CountingLogDao(private val delegate: FakeAdaptationLogDao) : AdaptationLogDao by delegate {
        var injectionsForCalls = 0
            private set
        var injectionsForAllCalls = 0
            private set
        var allCalls = 0
            private set

        override suspend fun injectionsFor(adaptationId: Long): List<AdaptationInjection> {
            injectionsForCalls += 1
            return delegate.injectionsFor(adaptationId)
        }

        override suspend fun all(): List<AdaptationEntry> {
            allCalls += 1
            return delegate.all()
        }

        override suspend fun injectionsForAll(): List<AdaptationInjection> {
            injectionsForAllCalls += 1
            return delegate.injectionsForAll()
        }

        fun resetCounters() {
            injectionsForCalls = 0
            injectionsForAllCalls = 0
            allCalls = 0
        }
    }

    private fun contextFor(root: File): android.content.Context =
        io.mockk.mockk { io.mockk.every { this@mockk.filesDir } returns root }

    @Test
    fun `one correction pass does not query once per journal row`() = runBlocking {
        val filesDir = temporaryFolder.newFolder("files")
        val context = contextFor(filesDir)
        val memoryManager = com.newoether.agora.data.MemoryManager(context)
        val skillManager = com.newoether.agora.data.SkillManager(context)
        val fake = FakeAdaptationLogDao()
        val log = CountingLogDao(fake)
        val applier = MemoryApplier(memoryManager, skillManager, log)
        val breaker = CircuitBreaker(log, applier)

        // A journal of the size the retention policy actually allows: 50 versions across a handful of
        // files. Every one of these rows is a potential `injectionsFor` call.
        val target = AdaptationTarget(AdaptationEntry.STORE_MEMORY, "prefs")
        memoryManager.createFile("prefs", "v0\n")
        repeat(CircuitBreaker.MAX_VERSIONS_PER_FILE) { index ->
            applier.apply(target, "v${index + 1}\n", "update $index", "s1")
        }
        val entries = fake.all().size
        assertTrue("the fixture must build a journal worth measuring", entries >= 50)

        log.resetCounters()
        breaker.recordCorrection("session-with-no-injections")

        // The journal is scanned once for the pass — that is inherent, the candidates have to be
        // found. What must NOT happen is a query per row on top of it.
        assertEquals("one scan of the journal per pass", 1, log.allCalls)
        assertTrue(
            "injectionsFor was called ${log.injectionsForCalls} times for $entries journal rows — " +
                "the per-row query is back",
            log.injectionsForCalls <= 1,
        )
    }

    @Test
    fun `the ledger does not make a re-run re-scan the journal per key`() = runBlocking {
        val filesDir = temporaryFolder.newFolder("files-ledger")
        val context = contextFor(filesDir)
        val memoryManager = com.newoether.agora.data.MemoryManager(context)
        val skillManager = com.newoether.agora.data.SkillManager(context)
        val fake = FakeAdaptationLogDao()
        val log = CountingLogDao(fake)
        val applier = MemoryApplier(memoryManager, skillManager, log)
        val breaker = CircuitBreaker(log, applier)

        memoryManager.createFile("busy", "v0\n")
        val target = AdaptationTarget(AdaptationEntry.STORE_MEMORY, "busy")
        repeat(20) { index -> applier.apply(target, "v${index + 1}\n", "update $index", "s1") }

        log.resetCounters()
        // Five fresh correction keys in one pass: the shape a chatty session produces.
        breaker.recordCorrections("s1", List(5) { "rejection-$it" })

        // Before the fix this was 5 journal scans, each with one injection query per journal row:
        // 5 × 20 = 100 injection queries for a 20-row journal. The pass now reads the injection table
        // **once** for the whole pass and answers every key from the map.
        //
        // The journal scan itself stays per key (5), and that is correct rather than sloppy: a key's
        // rollback mutates entries (`incrementFeedback`, `updateStatus`), so the next key must see the
        // updated rows. Caching that list would re-flag an entry the previous key already rolled back,
        // which `CircuitBreakerTest.anAlreadyRolledBackEntryIsNeverFlaggedAgain` pins against.
        assertEquals("the injection table is read once for the whole pass", 1, log.injectionsForAllCalls)
        // Exactly one `injectionsFor` call: the sentinel lookup for the correction ledger, which is a
        // fixed cost. It does not scale with the journal (20 rows here) — that is the regression.
        assertEquals("only the constant-cost ledger read remains", 1, log.injectionsForCalls)
        assertEquals("one journal scan per key, by design", 5, log.allCalls)
    }
}
