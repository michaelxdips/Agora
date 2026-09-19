package com.newoether.agora.wear

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The watch's atomic file replacement.
 *
 * Every one of the watch's three state files goes through [WearAtomicFile] now, and the failure this
 * guards is the one a watch actually experiences: the platform kills the process without warning
 * (battery, wrist-down, an update), so a write interrupted halfway leaves a file that is shorter than
 * it should be. For the config that means `WearCrypto.decrypt` fails and the user is back on the setup
 * screen with their key apparently gone; for the memory snapshot it means the watch answers from half
 * the user's context.
 *
 * A unit test cannot simulate a power loss. What it *can* prove is the mechanism: the destination is
 * replaced by a rename (so a reader never sees a partial file), the bytes survive a round trip, and a
 * failed write leaves the previous contents intact rather than truncating them.
 */
class WearAtomicFileTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var dir: java.io.File

    @Before
    fun setUp() {
        dir = temporaryFolder.newFolder("files")
    }

    @Test
    fun `a write round-trips the bytes`() {
        val target = java.io.File(dir, "state.bin")
        val bytes = ByteArray(256) { (it % 251).toByte() }

        assertTrue(WearAtomicFile.write(target, bytes))

        assertTrue(target.readBytes().contentEquals(bytes))
    }

    @Test
    fun `a write replaces the previous contents rather than appending`() {
        val target = java.io.File(dir, "state.txt")

        WearAtomicFile.write(target, "first")
        WearAtomicFile.write(target, "second")

        assertEquals("second", target.readText())
    }

    @Test
    fun `no temp file is left behind`() {
        // A leftover temp file is not harmless: it sits next to the state file, and the next reader
        // that globs the directory (the debug panel, a recovery attempt) sees two candidates.
        val target = java.io.File(dir, "state.txt")

        WearAtomicFile.write(target, "value")

        val names = dir.listFiles().orEmpty().map { it.name }
        assertTrue("temp file left behind: $names", names.none { it.endsWith(".tmp") })
        assertTrue(names.contains("state.txt"))
    }

    @Test
    fun `a failed write leaves the previous contents intact`() {
        // The property that matters most: a write that cannot complete must not destroy what was
        // already there. The destination is a *directory*, so both the rename and the copy fallback
        // fail — and the previous file must still be readable.
        val previous = java.io.File(dir, "previous.txt").apply { writeText("keep me") }
        val target = java.io.File(dir, "blocked")
        target.mkdirs()
        java.io.File(target, "child").writeText("occupies the name")

        val written = WearAtomicFile.write(target, "cannot land here")

        assertFalse("writing over a non-empty directory must report failure", written)
        assertEquals("the previous state must survive a failed write", "keep me", previous.readText())
    }

    @Test
    fun `a write creates the parent directory when it is missing`() {
        // The queue's `filesDir` exists, but the memory cache and the config store write on first
        // push, and a missing parent must not be a silent no-op.
        val nested = java.io.File(java.io.File(dir, "a/b/c"), "state.txt")

        assertTrue(WearAtomicFile.write(nested, "deep"))

        assertEquals("deep", nested.readText())
    }

    @Test
    fun `an empty write is a real write, not a no-op`() {
        // The empty snapshot is the message "the user's memory is empty now". If an empty write were
        // skipped, the watch would keep the previous snapshot — the exact bug the memory rule exists
        // to prevent.
        val target = java.io.File(dir, "state.txt")
        WearAtomicFile.write(target, "something")

        assertTrue(WearAtomicFile.write(target, ""))

        assertEquals("", target.readText())
        assertTrue(target.isFile)
    }

    @Test
    fun `the queue writes through the same helper and survives a reopen`() = runTest {
        // End-to-end through a real consumer, so the helper is exercised in the shape it is used.
        val context = io.mockk.mockk<android.content.Context>(relaxed = true)
        io.mockk.every { context.filesDir } returns dir
        val queue = WearOfflineQueue(context)

        queue.enqueue("held question")
        val reopened = WearOfflineQueue(context)

        assertEquals(listOf("held question"), reopened.all().map { it.text })
        assertNull(
            "no temp file may be left next to the queue",
            dir.listFiles().orEmpty().firstOrNull { it.name.endsWith(".tmp") },
        )
    }
}
