package com.newoether.agora.autopilot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.newoether.agora.data.MemoryManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 6 device verification: the persona channel works against the app's **real** active-memory
 * store on a real device, and toggle-off leaves nothing behind.
 *
 * This is the P1 promise ("toggle OFF = block removed = zero trace") executed against production
 * code and the real `filesDir` — the same standard the Phase 3 memory verification holds itself to.
 * The JVM tests cover the pure logic; this one proves the bytes the app itself would inject.
 */
@RunWith(AndroidJUnit4::class)
class PersonaInstrumentedTest {

    private lateinit var context: android.content.Context
    private lateinit var memoryManager: MemoryManager
    private lateinit var log: AdaptationLogDao
    private lateinit var applier: PersonaApplier

    private val bodies = mapOf(
        PersonaStore.ID_CAVEMAN to "Respond terse. All technical substance stay.",
        PersonaStore.ID_PONYTAIL to "Stop at the first rung that holds.",
    )

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        memoryManager = MemoryManager(context)
        log = AdaptationDatabase.get(context).adaptationLogDao()
        applier = PersonaApplier(memoryManager, log)
    }

    private fun activeMemoryFile() = File(context.filesDir, "active_memory.md")

    @Test
    fun personasLandInTheRealActiveMemoryStoreAndToggleOffLeavesNoTrace() = runBlocking {
        val original = "- device test: user prefers Indonesian\n"
        memoryManager.updateActiveMemory(original)

        // ON: the block must be in the file the app injects, next to the user's own memory.
        val on = applier.setEnabled(
            enabled = mapOf(PersonaStore.ID_CAVEMAN to true, PersonaStore.ID_PONYTAIL to true),
            bodies = bodies,
            reason = "instrumented persona on",
        )
        val onDisk = activeMemoryFile().readText()
        assertTrue("caveman block missing on device", PersonaStore.hasBlock(onDisk, PersonaStore.ID_CAVEMAN))
        assertTrue("ponytail block missing on device", PersonaStore.hasBlock(onDisk, PersonaStore.ID_PONYTAIL))
        assertTrue("user memory was dropped", onDisk.contains("user prefers Indonesian"))
        assertTrue("status was not read back from the channel", on.isActive(PersonaStore.ID_CAVEMAN))
        assertTrue("persona cost was not measured", on.injectedChars > 0)

        // The journal must carry the exact prior bytes, or undo cannot be byte-exact.
        val journal = log.all().firstOrNull { it.targetFile == "active_memory.md" }
        assertTrue("persona write was not journaled", journal != null)
        assertEquals(original, journal!!.beforeSnapshot)

        // OFF: byte-for-byte back to the original, and no marker anywhere.
        val off = applier.setEnabled(
            enabled = mapOf(PersonaStore.ID_CAVEMAN to false, PersonaStore.ID_PONYTAIL to false),
            bodies = bodies,
            reason = "instrumented persona off",
        )
        assertEquals(original, activeMemoryFile().readText())
        assertTrue("a persona marker survived toggle-off", off.storeIsClean)
        assertFalse(PersonaStore.hasAnyMarker(off.storeContent))
        assertEquals(0, off.injectedChars)
    }

    @Test
    fun masterAutopilotOffStripsPersonasOnDevice() = runBlocking {
        val original = "- device test: master toggle\n"
        memoryManager.updateActiveMemory(original)
        applier.setEnabled(PersonaStore.IDS.associateWith { true }, bodies, "both on")
        assertTrue(PersonaStore.hasAnyMarker(activeMemoryFile().readText()))

        val state = applier.reconcile(
            masterEnabled = false,
            enabled = PersonaStore.IDS.associateWith { true },
            bodies = bodies,
        )

        assertEquals(original, activeMemoryFile().readText())
        assertTrue(state.storeIsClean)
    }

    @Test
    fun aKilledWriteSelfHealsOnReconcile() = runBlocking {
        // Simulates the P8 adversarial case: the process died after the START marker was written and
        // before the END marker was. The store must converge, not duplicate the persona.
        val halfWritten = PersonaStore.startMarker(PersonaStore.ID_CAVEMAN) + "\npartial text\n"
        memoryManager.updateActiveMemory(halfWritten)

        val state = applier.reconcile(
            masterEnabled = true,
            enabled = mapOf(PersonaStore.ID_CAVEMAN to true),
            bodies = bodies,
        )

        assertEquals(1, Regex("PERSONA:CAVEMAN:START").findAll(state.storeContent).count())
        assertTrue(state.isActive(PersonaStore.ID_CAVEMAN))
        assertTrue(state.storeContent.contains("Respond terse."))
    }
}
