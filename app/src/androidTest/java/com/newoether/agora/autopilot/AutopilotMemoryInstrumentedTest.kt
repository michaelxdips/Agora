package com.newoether.agora.autopilot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The PRIMARY Phase 3 verification on a real device: seed three conversations' worth of clear facts,
 * run the reflection path, assert the facts land in Agora's real memory store, and assert undo
 * restores the prior file **byte-for-byte**.
 *
 * This test runs the real `MemoryManager`/`SkillManager` against the app's real `filesDir` — the same
 * store the app itself uses — so a pass is device-level evidence, not a JVM approximation.
 *
 * The reflection model call is injected (no API key is required to run this test); everything else —
 * stores, snapshots, undo — is production code.
 *
 * Requires a connected arm64 device or emulator (`adb devices`). On a machine without one, run
 * `./gradlew :app:testFdroidDebugUnitTest` for the JVM half of the same verification.
 */
@RunWith(AndroidJUnit4::class)
class AutopilotMemoryInstrumentedTest {

    private lateinit var context: android.content.Context
    private lateinit var memoryManager: MemoryManager
    private lateinit var skillManager: SkillManager
    private lateinit var log: AdaptationLogDao
    private lateinit var applier: MemoryApplier

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        memoryManager = MemoryManager(context)
        skillManager = SkillManager(context)
        log = AdaptationDatabase.get(context).adaptationLogDao()
        applier = MemoryApplier(memoryManager, skillManager, log)
    }

    @Test
    fun threeSeededConversationsProduceFactsInTheAgoraStoreAndUndoRestoresBytes() = runBlocking {
        val seeded = listOf(
            Seed("autopilot-it-a", "- lives in Pemalang\n"),
            Seed("autopilot-it-b", "- prefers Indonesian\n"),
            Seed("autopilot-it-c", "- runs a UMKM directory project\n"),
        )
        seeded.forEach { seed ->
            deleteIfPresent(seed.file)
            memoryManager.createFile(seed.file, seed.initial)
        }
        val beforeBytes = seeded.associate { it.file to fileOf(it.file).readBytes() }

        // The daily cap counts EVERY row in the app's real adaptation_log, and the rows this test
        // writes persist. Without a baseline the test is non-hermetic: run 1 passes, run 2 applies
        // only 2 of 3 ops, and runs 3+ apply 0 — a device that has used the app normally fails on
        // the first run. Take the baseline once and let the stub measure only this test's own rows.
        val baseline = log.countSince(AutopilotSettings.startOfToday(System.currentTimeMillis()))

        var applied = 0
        seeded.forEachIndexed { index, seed ->
            val engine = engine(
                reply = """{"ops":[{"op":"update","target_file":"${seed.file}","content":""" +
                    """"${seed.initial.trim()}\n- fact ${index + 1} $PROVENANCE_TAG\n","category":"c",""" +
                    """"confidence":0.95,"source_quote":"quote ${index + 1}"}]}"""
                ,
                baselineCount = baseline,
            )
            val outcome = engine.run(
                transcript = "USER: seeded conversation ${index + 1}",
                existingFiles = memoryManager.listFiles().map { it.name },
                sourceSessionId = seed.file,
            )
            applied += outcome.applied
        }
        assertEquals(3, applied)

        // 1. the facts are present in the real Agora memory store
        seeded.forEach { seed ->
            val content = memoryManager.readFile(seed.file)
            assertTrue("fact missing in ${seed.file}", content.contains(PROVENANCE_TAG))
        }

        // 2. undo restores the prior file byte-for-byte
        val entries = seeded.mapNotNull { seed ->
            log.historyFor(seed.file, AdaptationEntry.STORE_MEMORY).firstOrNull()
        }
        assertEquals(3, entries.size)
        entries.forEach { entry ->
            assertTrue("undo failed for ${entry.targetFile}", applier.undo(entry))
        }
        seeded.forEach { seed ->
            assertEquals(
                "byte-for-byte mismatch for ${seed.file}",
                beforeBytes.getValue(seed.file).toList(),
                fileOf(seed.file).readBytes().toList(),
            )
        }

        // cleanup: the instrumented run must not leave test memories OR test log rows behind — the
        // rows would otherwise consume the real daily cap for the rest of the day.
        entries.forEach { log.delete(it.id) }
        assertEquals(baseline, log.countSince(AutopilotSettings.startOfToday(System.currentTimeMillis())))
        seeded.forEach { deleteIfPresent(it.file) }
    }

    @Test
    fun theCircuitBreakerRollsBackABadAdaptationOnDevice() = runBlocking {
        val file = "autopilot-it-breaker"
        deleteIfPresent(file)
        memoryManager.createFile(file, "- correct\n")
        val before = fileOf(file).readBytes()

        val id = applier.apply(
            AdaptationTarget(AdaptationEntry.STORE_MEMORY, file),
            "- wrong\n$PROVENANCE_TAG\n",
            "update: deliberately wrong",
            "session-bad",
        )
        val entry = requireNotNull(log.find(id))
        val breaker = CircuitBreaker(log, applier)
        breaker.recordInjection(entry, "session-bad")
        breaker.recordCorrection("session-bad")
        val rolledBack = breaker.recordCorrection("session-bad")

        assertEquals(1, rolledBack.size)
        assertEquals(AdaptationEntry.STATUS_NEEDS_REVISION, log.find(id)?.status)
        assertEquals(before.toList(), fileOf(file).readBytes().toList())

        log.delete(id)
        deleteIfPresent(file)
    }

    @Test
    fun undoOfANewlyCreatedFileRemovesItFromTheStore() = runBlocking {
        val file = "autopilot-it-created"
        deleteIfPresent(file)

        val id = applier.apply(
            AdaptationTarget(AdaptationEntry.STORE_MEMORY, file),
            "- brand new\n$PROVENANCE_TAG\n",
            "add: brand new",
            "session-new",
        )
        assertTrue(fileOf(file).exists())

        assertTrue(applier.undo(requireNotNull(log.find(id))))
        assertFalse(fileOf(file).exists())
        assertNull(applier.snapshot(AdaptationTarget(AdaptationEntry.STORE_MEMORY, file)))

        // JUnit4 requires a void test method: assert the cleanup result instead of returning it.
        assertEquals(1, log.delete(id))
    }

    private data class Seed(val file: String, val initial: String)

    private fun fileOf(name: String) = File(context.filesDir, "memory_db/$name.md")

    private fun deleteIfPresent(name: String) {
        if (fileOf(name).exists()) memoryManager.deleteFile(name)
    }

    /** The production engine with the model call replaced by a fixed reply (no API key needed). */
    private fun engine(reply: String, baselineCount: Int = 0) = ReflectionEngine(
        reflect = { _, _ -> reply },
        applier = applier,
        log = log,
        settings = object : AutopilotControls {
            override suspend fun isEnabled() = true
            override suspend fun currentDailyCap() = AutopilotSettings.DEFAULT_DAILY_CAP
            override suspend fun underDailyCap(log: AdaptationLogDao, sinceMillis: Long) =
                log.countAutopilotSince(sinceMillis, AdaptationEntry.STORE_ACTIVE_MEMORY) -
                baselineCount < AutopilotSettings.DEFAULT_DAILY_CAP
        },
    )
}
