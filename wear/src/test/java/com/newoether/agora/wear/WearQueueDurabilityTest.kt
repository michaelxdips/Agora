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
import java.io.File

/**
 * The Session-5 queue durability contract: **a failed persist is a failure, not a success.**
 *
 * The bugs this pins (found in the Session-5 audit, fixed in `WearOfflineQueue`):
 *
 *  1. `writeAll` discarded `WearAtomicFile.write`'s boolean, so `enqueue` handed the caller an id
 *     for a question that was never written (lost on process death), and `complete` returned `true`
 *     while the entry was still on disk — the drainer counted it delivered and re-sent it next
 *     pass, billed twice.
 *  2. The drain pass kept spending provider calls on a filesystem that could not record their
 *     removal.
 *
 * What is testable on a JVM: `enqueue` must return null when the queue file cannot be written
 * (simulated with a non-empty directory squatting on the file's path — the same shape the atomic
 * write refuses), and the drainer must stop at the first failed commit. The disk-full case itself
 * needs a device; the mechanism is the same boolean.
 */
class WearQueueDurabilityTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var queueDir: File
    private lateinit var queue: WearOfflineQueue

    private fun context(filesDir: File): android.content.Context {
        val context = io.mockk.mockk<android.content.Context>(relaxed = true)
        io.mockk.every { context.filesDir } returns filesDir
        return context
    }

    @Before
    fun setUp() {
        queueDir = temporaryFolder.newFolder("files")
        queue = WearOfflineQueue(context(queueDir))
    }

    @Test
    fun `enqueue returns null when the queue file cannot be written`() = runTest {
        // A non-empty directory where the queue file belongs: the atomic write cannot rename over
        // it and its copy fallback cannot replace it, so the write reports failure.
        val blocked = File(queueDir, WearOfflineQueue.FILE_NAME)
        blocked.mkdirs()
        File(blocked, "child").writeText("occupies the name")

        val entry = queue.enqueue("this must not be reported as held")

        assertNull("a question that was not persisted must not be handed an id", entry)
    }

    @Test
    fun `complete reports failure when the removal cannot be confirmed`() = runTest {
        // With the file replaced by a directory, `readAll` cannot see the entry and `complete`
        // must report false: the caller may not count the question delivered.
        val entry = queue.enqueue("held")
        requireNotNull(entry)
        val file = File(queueDir, WearOfflineQueue.FILE_NAME)
        file.delete()
        file.mkdirs()
        File(file, "child").writeText("occupies the name")

        assertFalse("an unconfirmed removal must not report success", queue.complete(entry.id))
    }

    @Test
    fun `the drain pass stops when a commit cannot be persisted`() {
        // Source contract: `WearQueueDrainer` checks `queue.complete`'s boolean and breaks. Without
        // this, a failed commit re-sends every remaining entry on the next pass (double billing).
        val source = File("src/main/java/com/newoether/agora/wear/WearQueueDrainer.kt")
            .takeIf { it.isFile }
            ?: File("../wear/src/main/java/com/newoether/agora/wear/WearQueueDrainer.kt")
        assertTrue("WearQueueDrainer.kt must be readable", source.isFile)
        val text = source.readText()

        assertTrue(
            "the drainer must check the commit's result",
            Regex("if \\(!queue\\.complete\\(entry\\.id\\)\\)").containsMatchIn(text),
        )
        assertTrue(
            "a failed commit must stop the pass",
            Regex("could not persist the queue; stopping this drain pass").containsMatchIn(text),
        )
    }

    @Test
    fun `the send path handles a null enqueue instead of sending anyway`() {
        // Source contract: `WearMainActivity.sendOnce` must not call the provider when the question
        // has no durable record — the user is told instead of being quietly charged.
        val source = File("src/main/java/com/newoether/agora/wear/WearMainActivity.kt")
            .takeIf { it.isFile }
            ?: File("../wear/src/main/java/com/newoether/agora/wear/WearMainActivity.kt")
        assertTrue("WearMainActivity.kt must be readable", source.isFile)
        val text = source.readText()

        assertTrue(
            "a null enqueue must be handled",
            Regex("if \\(entry == null\\)").containsMatchIn(text),
        )
        assertTrue(
            "the user must be told the question could not be saved",
            Regex("R\\.string\\.wear_chat_queue_write_failed").containsMatchIn(text),
        )
    }

    @Test
    fun `a config push consumes the Data Layer item before any validation`() {
        // Source contract for WearListeners: the delete must come *before* the version/isValid
        // returns, or a refused push leaves the API key in the replicated store indefinitely.
        val source = File("src/main/java/com/newoether/agora/wear/WearListeners.kt")
            .takeIf { it.isFile }
            ?: File("../wear/src/main/java/com/newoether/agora/wear/WearListeners.kt")
        assertTrue("WearListeners.kt must be readable", source.isFile)
        val text = source.readText()

        val deleteAt = text.indexOf("deleteConsumed(event.dataItem.uri)")
        val versionCheckAt = text.indexOf("if (config.version != WearConfig.CURRENT_VERSION)")
        assertTrue("both sites must exist", deleteAt >= 0 && versionCheckAt >= 0)
        assertTrue(
            "the item must be consumed before the version check",
            deleteAt < versionCheckAt,
        )
    }

    @Test
    fun `the setup screen refuses to claim success when the save failed`() {
        // Source contract: the Save path must branch on the write result; `onConfigured` may only
        // run when something is actually on disk.
        val source = File("src/main/java/com/newoether/agora/wear/WearSetupScreen.kt")
            .takeIf { it.isFile }
            ?: File("../wear/src/main/java/com/newoether/agora/wear/WearSetupScreen.kt")
        assertTrue("WearSetupScreen.kt must be readable", source.isFile)
        val text = source.readText()

        assertTrue(
            "the Save path must check the store's result",
            text.contains("val saved = "),
        )
        assertTrue(
            "the store write must be the checked call",
            text.contains("store.write(config)"),
        )
        assertTrue(
            "a failed save must not call onConfigured",
            Regex("if \\(!saved\\)[\\s\\S]*?return@launch").containsMatchIn(text),
        )
        assertTrue(
            "a failed save must not report success",
            Regex("wear_setup_save_failed").containsMatchIn(text),
        )
    }

    @Test
    fun `the endpoint drops a trailing slash after the completions path`() {
        // Source contract for the fix's shape (the wire behaviour is covered by
        // WearChatClientTest.endpointTrimsATrailingSlashAfterTheCompletionsPath).
        val source = File("src/main/java/com/newoether/agora/wear/WearChatClient.kt")
            .takeIf { it.isFile }
            ?: File("../wear/src/main/java/com/newoether/agora/wear/WearChatClient.kt")
        assertTrue("WearChatClient.kt must be readable", source.isFile)
        val text = source.readText()

        assertTrue(
            "the trimmed path must be the one built into the URL",
            Regex("newBuilder\\(\\)\\.encodedPath\\(path\\)").containsMatchIn(text),
        )
    }

    @Test
    fun `a fresh queue instance still reads what a successful enqueue wrote`() = runTest {
        // The positive control for the null-return change: on a writable directory nothing about
        // the round trip may have changed.
        val entry = queue.enqueue("round trip")
        requireNotNull(entry)

        val reopened = WearOfflineQueue(context(queueDir))
        assertEquals(listOf("round trip"), reopened.all().map { it.text })
        assertEquals(entry.id, reopened.all().single().id)
    }
}
