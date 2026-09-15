package com.newoether.agora.autopilot

import android.content.Context
import com.newoether.agora.data.MemoryManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 6 P1/P4/P8 verification: the persona channel is snapshot-first, byte-exact on removal, and
 * journals every change it makes.
 *
 * Runs against the **real** `MemoryManager` (the actual Agora active-memory store, N4) on a temporary
 * `filesDir`, so a pass proves the bytes on disk rather than a mock's opinion — the same standard the
 * Phase 3 memory tests hold themselves to.
 */
class PersonaApplierTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var memoryManager: MemoryManager
    private lateinit var log: FakeAdaptationLogDao
    private lateinit var applier: PersonaApplier

    private val bodies = mapOf(
        PersonaStore.ID_CAVEMAN to "caveman rules",
        PersonaStore.ID_PONYTAIL to "ponytail rules",
    )

    private fun context(filesDir: File): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        return context
    }

    @Before
    fun setUp() {
        filesDir = temporaryFolder.newFolder("files")
        memoryManager = MemoryManager(context(filesDir))
        log = FakeAdaptationLogDao()
        applier = PersonaApplier(memoryManager, log)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0
    }

    @Test
    fun `turning a persona on writes its block into the real active-memory store`() = runBlocking {
        memoryManager.updateActiveMemory("- User lives in Pemalang.")

        val state = applier.setEnabled(
            enabled = mapOf(PersonaStore.ID_CAVEMAN to true, PersonaStore.ID_PONYTAIL to false),
            bodies = bodies,
            reason = "persona caveman = true",
        )

        val onDisk = File(filesDir, "active_memory.md").readText()
        assertTrue(onDisk.contains(PersonaStore.startMarker(PersonaStore.ID_CAVEMAN)))
        assertTrue(onDisk.contains("caveman rules"))
        assertTrue(onDisk.contains("- User lives in Pemalang."))
        assertTrue(state.isActive(PersonaStore.ID_CAVEMAN))
        assertFalse(state.isActive(PersonaStore.ID_PONYTAIL))
    }

    @Test
    fun `the change is journaled with the exact prior bytes`() = runBlocking {
        val original = "- User lives in Pemalang."
        memoryManager.updateActiveMemory(original)

        applier.setEnabled(
            enabled = mapOf(PersonaStore.ID_CAVEMAN to true),
            bodies = bodies,
            reason = "persona caveman = true",
        )

        val entry = log.rows.single()
        // Must be the active-memory store, not STORE_MEMORY: `MemoryManager` resolves every
        // STORE_MEMORY name under `memory_db/`, so journaling the singleton as a memory file sent
        // Undo at a different file — silently failing, or overwriting the user's own
        // `memory_db/active_memory.md` with a persona snapshot.
        assertEquals(AdaptationEntry.STORE_ACTIVE_MEMORY, entry.store)
        assertEquals("active_memory.md", entry.targetFile)
        assertEquals(original, entry.beforeSnapshot)
        assertTrue(entry.afterSnapshot!!.contains("caveman rules"))
    }

    @Test
    fun `toggle off removes the block and restores the exact prior bytes`() = runBlocking {
        val original = "- User lives in Pemalang.\n- User writes Kotlin.\n"
        memoryManager.updateActiveMemory(original)

        applier.setEnabled(mapOf(PersonaStore.ID_CAVEMAN to true), bodies, "on")
        val state = applier.setEnabled(
            enabled = mapOf(PersonaStore.ID_CAVEMAN to false),
            bodies = bodies,
            reason = "persona caveman = false",
        )

        assertEquals(original, File(filesDir, "active_memory.md").readText())
        assertTrue(state.storeIsClean)
        assertEquals(0, state.injectedChars)
    }

    @Test
    fun `master off strips every persona`() = runBlocking {
        val original = "- Durable fact."
        memoryManager.updateActiveMemory(original)
        applier.setEnabled(
            enabled = PersonaStore.IDS.associateWith { true },
            bodies = bodies,
            reason = "both on",
        )

        val state = applier.reconcile(
            masterEnabled = false,
            enabled = PersonaStore.IDS.associateWith { true },
            bodies = bodies,
        )

        assertEquals(original, File(filesDir, "active_memory.md").readText())
        assertTrue(state.storeIsClean)
    }

    @Test
    fun `reconcile rewrites a drifted block`() = runBlocking {
        memoryManager.updateActiveMemory(
            PersonaStore.upsertBlock("- fact", PersonaStore.ID_CAVEMAN, "stale text")
        )

        val state = applier.reconcile(
            masterEnabled = true,
            enabled = mapOf(PersonaStore.ID_CAVEMAN to true),
            bodies = bodies,
        )

        assertEquals("caveman rules", PersonaStore.blocks(state.storeContent)[PersonaStore.ID_CAVEMAN])
    }

    @Test
    fun `reconcile is a no-op when the store already matches`() = runBlocking {
        val wanted = applier.desiredContent("", mapOf(PersonaStore.ID_CAVEMAN to true), bodies)
        memoryManager.updateActiveMemory(wanted)
        val before = log.rows.size

        applier.reconcile(
            masterEnabled = true,
            enabled = mapOf(PersonaStore.ID_CAVEMAN to true),
            bodies = bodies,
        )

        assertEquals(before, log.rows.size)
    }

    @Test
    fun `reconcile heals a store whose block lost its end marker`() = runBlocking {
        val halfWritten = PersonaStore.startMarker(PersonaStore.ID_CAVEMAN) + "\ncaveman rules\n"
        memoryManager.updateActiveMemory(halfWritten)

        val state = applier.reconcile(
            masterEnabled = true,
            enabled = mapOf(PersonaStore.ID_CAVEMAN to true),
            bodies = bodies,
        )

        assertEquals(1, Regex("PERSONA:CAVEMAN:START").findAll(state.storeContent).count())
        assertTrue(state.isActive(PersonaStore.ID_CAVEMAN))
    }

    @Test
    fun `a persona-free store is untouched by reconcile with everything off`() = runBlocking {
        val original = "- fact one\n- fact two"
        memoryManager.updateActiveMemory(original)

        val state = applier.reconcile(
            masterEnabled = false,
            enabled = PersonaStore.IDS.associateWith { false },
            bodies = bodies,
        )

        assertEquals(original, state.storeContent)
        assertEquals(0, log.rows.size)
    }

    @Test
    fun `a missing body never writes an empty block`() = runBlocking {
        memoryManager.updateActiveMemory("- fact")

        val state = applier.setEnabled(
            enabled = mapOf(PersonaStore.ID_CAVEMAN to true),
            bodies = emptyMap(),
            reason = "on",
        )

        assertTrue(state.storeIsClean)
        assertEquals("- fact", state.storeContent)
    }

    @Test
    fun `both personas produce a deterministic store text`() = runBlocking {
        memoryManager.updateActiveMemory("- fact")
        val first = applier.setEnabled(PersonaStore.IDS.associateWith { true }, bodies, "both").storeContent

        memoryManager.updateActiveMemory("- fact")
        val second = applier.setEnabled(PersonaStore.IDS.associateWith { true }, bodies, "both").storeContent

        assertEquals(first, second)
        assertNull(PersonaStore.blocks(first)["missing"])
    }
}
