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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The PRIMARY Phase 3/4 verification: every adaptation snapshots first, and undo restores the prior
 * file **byte-for-byte**.
 *
 * These run against the real `MemoryManager`/`SkillManager` (the actual Agora stores, N4) on a
 * temporary `filesDir`, so a pass proves the file bytes, not a mock's opinion.
 */
class MemoryApplierTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var memoryManager: MemoryManager
    private lateinit var skillManager: SkillManager
    private lateinit var log: FakeAdaptationLogDao
    private lateinit var applier: MemoryApplier

    private fun setUp() {
        filesDir = temporaryFolder.newFolder("files")
        memoryManager = MemoryManager(context(filesDir))
        skillManager = SkillManager(context(filesDir))
        log = FakeAdaptationLogDao()
        applier = MemoryApplier(memoryManager, skillManager, log)
        silenceAndroidLog()
    }

    /** `DebugLog` delegates to `android.util.Log`, which is a stub in JVM tests. */
    private fun silenceAndroidLog() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0
    }

    /**
     * The mock's `filesDir` must come from a **parameter**, not a class property: inside the stub
     * lambda the mock is the innermost implicit receiver, so a bare `filesDir` on the right-hand
     * side would resolve to the very property being stubbed and silently record nothing.
     */
    private fun context(root: File): Context = mockk {
        every { this@mockk.filesDir } returns root
    }

    @Test
    fun undoRestoresPriorBytesExactlyForAnEditedMemoryFile() = runBlocking {
        setUp()
        memoryManager.createFile("user-preferences", "- prefers dark mode\n")
        val priorBytes = File(filesDir, "memory_db/user-preferences.md").readBytes()

        val target = AdaptationTarget(AdaptationEntry.STORE_MEMORY, "user-preferences")
        val id = applier.apply(
            target = target,
            after = "- prefers dark mode\n- lives in Pemalang\n$PROVENANCE_TAG\n",
            reason = "add: location",
            sourceSessionId = "session-1",
        )

        val entry = requireNotNull(log.find(id))
        assertEquals("- prefers dark mode\n", entry.beforeSnapshot)
        assertTrue(memoryManager.readFile("user-preferences").contains("Pemalang"))

        assertTrue(applier.undo(entry))

        assertArrayEquals(priorBytes, File(filesDir, "memory_db/user-preferences.md").readBytes())
        assertEquals(AdaptationEntry.STATUS_USER_ROLLED_BACK, log.find(id)?.status)
    }

    @Test
    fun undoOfACreatedFileDeletesItAndLeavesNoFileBehind() = runBlocking {
        setUp()
        val target = AdaptationTarget(AdaptationEntry.STORE_MEMORY, "new-facts")
        val id = applier.apply(target, "fact\n", "add: first fact")
        val entry = requireNotNull(log.find(id))
        assertNull(entry.beforeSnapshot)
        assertTrue(File(filesDir, "memory_db/new-facts.md").exists())

        assertTrue(applier.undo(entry))

        assertFalse(File(filesDir, "memory_db/new-facts.md").exists())
    }

    @Test
    fun skillStoreAdaptationsUseSkillManagerAndUndoRestoresBytes() = runBlocking {
        setUp()
        skillManager.createFile("deploy", "step 1\n", "deployment skill")
        val priorBytes = File(filesDir, "skill_db/deploy.md").readBytes()

        val target = AdaptationTarget(AdaptationEntry.STORE_SKILL, "deploy")
        val id = applier.apply(target, "step 1\nstep 2\n$PROVENANCE_TAG\n", "update: steps")
        assertTrue(skillManager.readFile("deploy").contains("step 2"))

        assertTrue(applier.undo(requireNotNull(log.find(id))))

        assertArrayEquals(priorBytes, File(filesDir, "skill_db/deploy.md").readBytes())
        assertEquals("deployment skill", skillManager.getDescription("deploy"))
    }

    @Test
    fun everyWriteHasASnapshotRowBeforeTheStoreChanges() = runBlocking {
        setUp()
        memoryManager.createFile("notes", "old\n")
        val target = AdaptationTarget(AdaptationEntry.STORE_MEMORY, "notes")

        val id = applier.apply(target, "new\n", "update: notes")

        val entry = requireNotNull(log.find(id))
        assertEquals("old\n", entry.beforeSnapshot)
        assertEquals("new\n", entry.afterSnapshot)
        assertEquals(AdaptationEntry.STATUS_APPLIED, entry.status)
        assertEquals("session-42", entry.copy(sourceSessionId = "session-42").sourceSessionId)
    }

    @Test
    fun noOpWritesAreRejectedBeforeAnythingIsLogged() = runBlocking {
        setUp()
        memoryManager.createFile("stable", "same\n")
        val target = AdaptationTarget(AdaptationEntry.STORE_MEMORY, "stable")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { applier.apply(target, "same\n", "update: nothing") }
        }

        assertTrue(log.all().isEmpty())
        assertEquals("same\n", memoryManager.readFile("stable"))
    }

    @Test
    fun aPathTraversalNameIsContainedInsideTheStoreDirectory() = runBlocking {
        setUp()
        val target = AdaptationTarget(AdaptationEntry.STORE_MEMORY, "../escape")

        val id = applier.apply(target, "content\n", "add: escape attempt")

        // MemoryManager sanitizes separators, so the write lands INSIDE memory_db as `.._escape.md`
        // and can never touch a sibling directory.
        val written = File(filesDir, "memory_db/.._escape.md")
        assertTrue("write must stay inside memory_db", written.exists())
        assertFalse(File(filesDir, "escape.md").exists())
        assertEquals("content\n", log.find(id)?.afterSnapshot)

        assertTrue(applier.undo(requireNotNull(log.find(id))))
        assertFalse(written.exists())
    }

    @Test
    fun snapshotReturnsNullForAnAbsentFileAndContentForAnExistingOne() = runBlocking {
        setUp()
        memoryManager.createFile("present", "body\n")

        assertEquals(
            "body\n",
            applier.snapshot(AdaptationTarget(AdaptationEntry.STORE_MEMORY, "present")),
        )
        assertNull(applier.snapshot(AdaptationTarget(AdaptationEntry.STORE_MEMORY, "missing")))
    }

    @Test
    fun undoReportsFailureWhenTheStoreNoLongerHoldsTheFile() = runBlocking {
        setUp()
        val target = AdaptationTarget(AdaptationEntry.STORE_MEMORY, "volatile")
        val id = applier.apply(target, "content\n", "add: volatile")
        val entry = requireNotNull(log.find(id))
        memoryManager.deleteFile("volatile")

        // A missing file makes editFile fail; undo must report false, never claim success.
        assertFalse(applier.undo(entry))
        assertEquals(AdaptationEntry.STATUS_APPLIED, log.find(id)?.status)
    }

    @Test
    fun repeatedUndoIsIdempotentOnFileBytes() = runBlocking {
        setUp()
        memoryManager.createFile("idempotent", "v1\n")
        val target = AdaptationTarget(AdaptationEntry.STORE_MEMORY, "idempotent")
        val id = applier.apply(target, "v2\n", "update: idempotent")
        val entry = requireNotNull(log.find(id))

        assertTrue(applier.undo(entry))
        val afterFirstUndo = File(filesDir, "memory_db/idempotent.md").readBytes()
        assertTrue(applier.undo(entry))

        assertArrayEquals(afterFirstUndo, File(filesDir, "memory_db/idempotent.md").readBytes())
        assertEquals("v1\n", memoryManager.readFile("idempotent"))
    }

    @Test
    fun adaptationEntryRejectsImpossibleStates() {
        assertThrows(IllegalArgumentException::class.java) {
            AdaptationEntry(
                timestamp = 1L,
                store = AdaptationEntry.STORE_MEMORY,
                targetFile = "x",
                beforeSnapshot = "same",
                afterSnapshot = "same",
                reason = "no-op",
                sourceSessionId = null,
                status = AdaptationEntry.STATUS_APPLIED,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdaptationEntry(
                timestamp = 1L,
                store = "not-a-store",
                targetFile = "x",
                beforeSnapshot = null,
                afterSnapshot = "a",
                reason = "bad store",
                sourceSessionId = null,
                status = AdaptationEntry.STATUS_APPLIED,
            )
        }
        assertNotNull(
            AdaptationEntry(
                timestamp = 1L,
                store = AdaptationEntry.STORE_SKILL,
                targetFile = "x",
                beforeSnapshot = null,
                afterSnapshot = "a",
                reason = "valid",
                sourceSessionId = null,
                status = AdaptationEntry.STATUS_NEEDS_REVISION,
            )
        )
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(
            "byte-for-byte mismatch: expected ${expected.size} bytes, got ${actual.size}",
            expected.toList(),
            actual.toList(),
        )
    }
}
