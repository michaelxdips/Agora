package com.newoether.agora.wear

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Platform-trap sentinel for the watch module.
 *
 * Why this exists, concretely: during this session the drain pass was extracted out of the
 * composable into [WearQueueDrainer] for testability, and the `withContext(Dispatchers.IO)` wrapper
 * was lost in the move. Every JVM test still passed — `Dispatchers.IO` is not observable in a unit
 * test, and the sender was a fake. On the device the drain died with
 * `NetworkOnMainThreadException` and the held question stayed on disk. The JVM suite was green and the
 * feature was broken; only the on-device proof caught it.
 *
 * A unit test cannot assert "this ran on a background dispatcher" without the very threading the
 * production code is supposed to own. What a unit test *can* do is refuse to let the two call sites
 * that reach the network lose their dispatcher again. That is what this file is: a cheap, precise
 * tripwire on a trap that already bit once, in this module, in this session.
 *
 * Deliberately a source assertion, not a behavioural one: behavioural coverage of the network path is
 * `_tools/p0_autodrain_proof.py`, which runs the real OkHttp call on the real device. This test's job
 * is to fail fast and name the file.
 */
class WearMainThreadSentinelTest {

    private val repoRoot: File = findRepoRoot()

    private fun findRepoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "UPSTREAM_TOUCHPOINTS.md").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("repository root not found from ${System.getProperty("user.dir")}")
    }

    private fun source(relative: String): String {
        val file = File(repoRoot, relative)
        assertTrue("source file is missing: $relative", file.isFile)
        return file.readText()
    }

    @Test
    fun `the drain pass owns a background dispatcher`() {
        val source = source("wear/src/main/java/com/newoether/agora/wear/WearQueueDrainer.kt")

        // `WearChatClient.ask` is a blocking OkHttp call. It is called from inside `drain`, so `drain`
        // is the one that has to move off the main thread — the caller cannot fix it from outside
        // without knowing that the sender blocks.
        assertTrue(
            "WearQueueDrainer.drain must run its sender on Dispatchers.IO; without it the launch " +
                "drain dies with NetworkOnMainThreadException on the device while every JVM test passes",
            source.contains("withContext(Dispatchers.IO)"),
        )
    }

    @Test
    fun `the direct send path keeps its dispatcher around the network call`() {
        val source = source("wear/src/main/java/com/newoether/agora/wear/WearMainActivity.kt")

        // The interactive paths (ask, Send now) each wrap their own call; the drain path is covered
        // above. Asserting on the call text, not on a line number, so ordinary edits do not fire it.
        assertTrue(
            "WearMainActivity must wrap WearChatClient.ask in Dispatchers.IO",
            Regex("withContext\\(Dispatchers\\.IO\\)\\s*\\{\\s*\\n?\\s*client\\.ask\\(").containsMatchIn(source),
        )
    }

    @Test
    fun `the pairing transport does not touch the Data Layer on the caller's thread`() {
        val source = source("wear/src/main/java/com/newoether/agora/wear/WearPairing.kt")

        // Data Layer queries are blocking Task.await() calls; the same trap applies.
        assertTrue(
            "WearPairingTransport must run the capability query on Dispatchers.IO",
            source.contains("withContext(Dispatchers.IO)"),
        )
    }

    @Test
    fun `no watch source calls the blocking HTTP client from a composable scope without a dispatcher`() {
        // Broadest form of the same rule: every `client.ask(` / `.ask(` call site in the module must
        // sit inside a Dispatchers.IO block. Catches a future call site that forgets.
        val files = File(repoRoot, "wear/src/main/java/com/newoether/agora/wear")
            .listFiles { f -> f.extension == "kt" }
            .orEmpty()
        val offenders = files.filter { file ->
            val text = file.readText()
            Regex("\\.ask\\(").containsMatchIn(text) &&
                !text.contains("withContext(Dispatchers.IO)")
        }.map { it.name }

        assertTrue(
            "these files call the blocking ask() with no Dispatchers.IO anywhere: $offenders",
            offenders.isEmpty(),
        )
    }
}
