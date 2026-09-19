package com.newoether.agora.wear

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The watch's field-survival fix, asserted where it can actually fail.
 *
 * `WearSetupFieldsSaveableTest` (instrumented) round-trips a `Saver` it declares **itself**:
 * `Saver(save = { it }, restore = { it })`. That proves Compose's String saver works; it does not
 * touch `WearSetupScreen`. The Session-4 audit named the mutation exactly — change
 * `rememberSaveable` back to `remember` in the screen and all five of those tests stay green,
 * because none of them reads the screen.
 *
 * This file is the missing half, and it follows the pattern the module already uses for traps a
 * unit test cannot observe (`WearMainThreadSentinelTest`): read the source, assert the thing that
 * must be true of it, and fail with the file name so the next reader knows where to look.
 *
 * What it asserts, per state holder:
 *
 *  * every field the setup screen persists across a wrist-down uses `rememberSaveable`, not
 *    `remember` — a plain `remember` wipes the masked API key on a rotation, which the user cannot
 *    retype from memory;
 *  * the screen still *imports* the saveable runtime (a rename or a stray "unused import" cleanup
 *    that removes it would take the fix with it);
 *  * the count is not zero — an empty file, or a file whose fields were all moved elsewhere without
 *    this test being updated, fails rather than passing vacuously.
 *
 * Deliberately not a behavioural test: composing the screen needs a Compose UI test host this
 * module does not carry, and a fake host would assert the fake. The on-device suite keeps the
 * checks that genuinely need a device.
 */
class WearFieldSurvivalSourceContractTest {

    private val repoRoot: File = findRepoRoot()

    private fun findRepoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "UPSTREAM_TOUCHPOINTS.md").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("repository root not found from ${System.getProperty("user.dir")}")
    }

    private fun source(relative: String): String =
        File(repoRoot, relative).readText().replace("\r\n", "\n")

    private val setupScreen by lazy {
        source("wear/src/main/java/com/newoether/agora/wear/WearSetupScreen.kt")
    }
    private val mainActivity by lazy {
        source("wear/src/main/java/com/newoether/agora/wear/WearMainActivity.kt")
    }

    @Test
    fun `the setup screen persists its fields with rememberSaveable`() {
        // The three typed fields, by name: base URL, API key, model.
        assertTrue(
            "the setup screen must import the saveable runtime",
            setupScreen.contains("import androidx.compose.runtime.saveable.rememberSaveable"),
        )
        val saveableFields = Regex("var \\w+ by rememberSaveable").findAll(setupScreen).count()
        assertTrue(
            "the setup screen's fields must survive a wrist-down (found $saveableFields saveable fields)",
            saveableFields >= 3,
        )
        // The user-typed fields specifically. A transient status line may use plain `remember` — it
        // is recomputed on the next action and losing it costs nothing — but the three fields the
        // user typed, and the masked key they cannot retype, must be saveable.
        listOf("baseUrl", "apiKey", "model").forEach { field ->
            assertTrue(
                "`$field` must be rememberSaveable, not plain remember",
                Regex("var $field by rememberSaveable").containsMatchIn(setupScreen),
            )
        }
    }

    @Test
    fun `the chat screen keeps its persisted fields saveable too`() {
        // Same class of fix in WearMainActivity: the draft, the visible answer, the status line and
        // the notices. A regression here is the same user-visible bug on the other screen.
        assertTrue(
            "WearMainActivity must import the saveable runtime",
            mainActivity.contains("import androidx.compose.runtime.saveable.rememberSaveable"),
        )
        val saveableFields = Regex("var \\w+ by rememberSaveable").findAll(mainActivity).count()
        assertTrue(
            "the chat screen's draft/answer/status must survive a wrist-down (found $saveableFields)",
            saveableFields >= 4,
        )
    }

    @Test
    fun `send-now completes the held entry before it sends, so one tap is one call`() {
        // The bug (Session 4): the handler called `send(entry.text)` alone. `send` enqueues a new
        // entry, and its success branch drains the queue — which still held the original — so the
        // original went out too: two provider calls for one tap. The fix is an ordering, and an
        // ordering inside a composable lambda has no unit-test seam, so it is asserted here the way
        // this module already asserts screen invariants (WearMainThreadSentinelTest).
        //
        // The assertion is positional, not merely "both lines exist": `queue.complete(entry.id)`
        // must appear *before* `send(entry.text)` inside the Send-now handler. Reversing them
        // restores the double-send, and this test fails.
        //
        // `lastIndexOf` for the send, not `indexOf`: the fix's own comment quotes the old call
        // (`just send(entry.text)`) a few lines above the code, so the first match is prose. The
        // test caught exactly that on its first run — it compared the comment's position against a
        // `queue.complete` from the sendOnce path 15 KB earlier and failed for the wrong reason.
        val handler = mainActivity.lastIndexOf("send(entry.text)")
        assertTrue("the Send-now handler must call send(entry.text)", handler > 0)
        val complete = mainActivity.lastIndexOf("queue.complete(entry.id)", handler)
        assertTrue(
            "the Send-now handler must complete the held entry before sending it; " +
                "completing after (or not at all) re-runs the original through the drain",
            complete > 0 && complete < handler,
        )
        // And the completion must be inside the same handler window — a `queue.complete` somewhere
        // else in the file (the Discard button also completes) must not satisfy this test by
        // accident, so the gap between the two calls is bounded.
        assertTrue(
            "the complete and the send must be in the same handler (gap was ${handler - complete} chars)",
            handler - complete < 1200,
        )
    }
}
