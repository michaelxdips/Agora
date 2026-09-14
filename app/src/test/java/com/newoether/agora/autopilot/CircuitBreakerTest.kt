package com.newoether.agora.autopilot

import android.content.Context
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 4 exit criterion: the circuit breaker demonstrably rolls back a bad adaptation.
 *
 * The bad adaptation is a real file write through the real store, so the rollback is proven on
 * bytes, not on a mock.
 */
class CircuitBreakerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var memoryManager: MemoryManager
    private lateinit var log: FakeAdaptationLogDao
    private lateinit var applier: MemoryApplier
    private lateinit var breaker: CircuitBreaker

    private fun setUp() {
        filesDir = temporaryFolder.newFolder("files")
        memoryManager = MemoryManager(contextFor(filesDir))
        val skillManager = SkillManager(contextFor(filesDir))
        log = FakeAdaptationLogDao()
        applier = MemoryApplier(memoryManager, skillManager, log)
        breaker = CircuitBreaker(log, applier)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0
    }

    private fun contextFor(root: File): Context = mockk {
        every { this@mockk.filesDir } returns root
    }

    @Test
    fun twoCorrectionsInAnInjectedSessionRollBackTheBadAdaptation() = runBlocking {
        setUp()
        memoryManager.createFile("prefs", "- prefers metric units\n")
        val priorBytes = File(filesDir, "memory_db/prefs.md").readBytes()
        val id = applier.apply(
            target = AdaptationTarget(AdaptationEntry.STORE_MEMORY, "prefs"),
            after = "- prefers imperial units\n$PROVENANCE_TAG\n",
            reason = "update: WRONG unit preference",
            sourceSessionId = "session-bad",
        )
        val entry = requireNotNull(log.find(id))
        breaker.recordInjection(entry, "session-bad")

        // First correction: flagged, but still applied.
        assertTrue(breaker.recordCorrection("session-bad").isEmpty())
        assertEquals(1, log.find(id)?.feedbackFlags)
        assertEquals(AdaptationEntry.STATUS_APPLIED, log.find(id)?.status)
        assertTrue(memoryManager.readFile("prefs").contains("imperial"))

        // Second correction: circuit breaker trips.
        val rolledBack = breaker.recordCorrection("session-bad")

        assertEquals(1, rolledBack.size)
        assertEquals(id, rolledBack.single().id)
        assertEquals(2, log.find(id)?.feedbackFlags)
        assertEquals(AdaptationEntry.STATUS_NEEDS_REVISION, log.find(id)?.status)
        assertEquals(
            "the bad adaptation must be gone byte-for-byte",
            priorBytes.toList(),
            File(filesDir, "memory_db/prefs.md").readBytes().toList(),
        )
    }

    @Test
    fun correctionsInOtherSessionsDoNotFlagAnUninjectedAdaptation() = runBlocking {
        setUp()
        memoryManager.createFile("other", "- a\n")
        val id = applier.apply(
            AdaptationTarget(AdaptationEntry.STORE_MEMORY, "other"),
            "- b\n$PROVENANCE_TAG\n",
            "update",
            "session-a",
        )
        breaker.recordInjection(requireNotNull(log.find(id)), "session-a")

        repeat(3) { assertTrue(breaker.recordCorrection("session-elsewhere").isEmpty()) }

        assertEquals(0, log.find(id)?.feedbackFlags)
        assertEquals(AdaptationEntry.STATUS_APPLIED, log.find(id)?.status)
        assertEquals("- b\n$PROVENANCE_TAG\n", memoryManager.readFile("other"))
    }

    @Test
    fun anAlreadyRolledBackEntryIsNeverFlaggedAgain() = runBlocking {
        setUp()
        val id = applier.apply(
            AdaptationTarget(AdaptationEntry.STORE_MEMORY, "fresh"),
            "- x\n$PROVENANCE_TAG\n",
            "add",
            "s1",
        )
        breaker.recordInjection(requireNotNull(log.find(id)), "s1")
        breaker.recordCorrection("s1")
        breaker.recordCorrection("s1")
        assertEquals(AdaptationEntry.STATUS_NEEDS_REVISION, log.find(id)?.status)

        assertTrue(breaker.recordCorrection("s1").isEmpty())

        assertEquals(2, log.find(id)?.feedbackFlags)
    }

    @Test
    fun aManualUndoAlsoStopsTheCircuitBreakerFromTouchingTheEntry() = runBlocking {
        setUp()
        val id = applier.apply(
            AdaptationTarget(AdaptationEntry.STORE_MEMORY, "manual"),
            "- y\n$PROVENANCE_TAG\n",
            "add",
            "s1",
        )
        breaker.recordInjection(requireNotNull(log.find(id)), "s1")
        assertTrue(applier.undo(requireNotNull(log.find(id)), AdaptationEntry.STATUS_USER_ROLLED_BACK))

        assertTrue(breaker.recordCorrection("s1").isEmpty())

        assertEquals(0, log.find(id)?.feedbackFlags)
        assertEquals(AdaptationEntry.STATUS_USER_ROLLED_BACK, log.find(id)?.status)
    }

    @Test
    fun retentionKeepsTheNewestVersionsPerFileAndDropsTheRest() = runBlocking {
        setUp()
        memoryManager.createFile("busy", "v0\n")
        val target = AdaptationTarget(AdaptationEntry.STORE_MEMORY, "busy")
        repeat(60) { index ->
            applier.apply(target, "v${index + 1}\n", "update $index", "s1")
        }
        assertEquals(60, log.all().size)

        breaker.pruneRetention()

        val kept = log.historyFor("busy", AdaptationEntry.STORE_MEMORY)
        assertEquals(CircuitBreaker.MAX_VERSIONS_PER_FILE, kept.size)
        // Newest first, so the newest content survives.
        assertEquals("v60\n", kept.first().afterSnapshot)
    }

    @Test
    fun retentionDropsEntriesOlderThanThirtyDays() = runBlocking {
        setUp()
        val now = System.currentTimeMillis()
        val old = now - CircuitBreaker.MAX_AGE_MILLIS - 1
        val staleId = log.insert(entry("stale", old))
        val freshId = log.insert(entry("fresh", now))
        log.insertInjection(AdaptationInjection(adaptationId = staleId, sessionId = "s1", injectedAt = old))

        breaker.pruneRetention(now)

        assertEquals(null, log.find(staleId))
        assertTrue(log.find(freshId) != null)
        assertTrue(log.injectionsFor(staleId).isEmpty())
    }

    @Test
    fun retentionByAgePrunesTheJournalButNeverTheLiveFileContent() = runBlocking {
        setUp()
        memoryManager.createFile("safe", "v0\n")
        applier.apply(
            AdaptationTarget(AdaptationEntry.STORE_MEMORY, "safe"),
            "v1\n",
            "update",
            "s1",
        )

        // "50 versions per file or 30 days, whichever first": once the newest version is itself
        // older than 30 days, the age rule prunes it too. The live file is untouched either way.
        breaker.pruneRetention(System.currentTimeMillis() + CircuitBreaker.MAX_AGE_MILLIS * 2)

        assertEquals("the live file content is never touched", "v1\n", memoryManager.readFile("safe"))
        assertTrue(log.historyFor("safe", AdaptationEntry.STORE_MEMORY).isEmpty())
    }

    @Test
    fun retentionByCountKeepsTheNewestFiftyWithinTheAgeWindow() = runBlocking {
        setUp()
        memoryManager.createFile("aged", "v0\n")
        val target = AdaptationTarget(AdaptationEntry.STORE_MEMORY, "aged")
        repeat(60) { index -> applier.apply(target, "v${index + 1}\n", "update $index", "s1") }

        // Every entry is inside the 30-day window, so only the per-file count rule applies.
        breaker.pruneRetention()

        val kept = log.historyFor("aged", AdaptationEntry.STORE_MEMORY)
        assertEquals(CircuitBreaker.MAX_VERSIONS_PER_FILE, kept.size)
        assertEquals("v60\n", kept.first().afterSnapshot)
    }

    private fun entry(file: String, timestamp: Long) = AdaptationEntry(
        timestamp = timestamp,
        store = AdaptationEntry.STORE_MEMORY,
        targetFile = file,
        beforeSnapshot = null,
        afterSnapshot = "- $file\n",
        reason = "add",
        sourceSessionId = "s1",
        status = AdaptationEntry.STATUS_APPLIED,
    )

    @Test
    fun theBreakerNeverRollsBackAnEntryThatWasNotInjectedIntoTheSession() = runBlocking {
        setUp()
        memoryManager.createFile("untouched", "original\n")
        val id = applier.apply(
            AdaptationTarget(AdaptationEntry.STORE_MEMORY, "untouched"),
            "changed\n",
            "update",
            "s1",
        )
        // No injection recorded for s1.
        assertTrue(breaker.recordCorrection("s1").isEmpty())
        assertFalse(File(filesDir, "memory_db/untouched.md").readText().contains("original"))
        assertEquals(AdaptationEntry.STATUS_APPLIED, log.find(id)?.status)
    }
}
