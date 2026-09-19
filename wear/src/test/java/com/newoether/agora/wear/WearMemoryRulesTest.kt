package com.newoether.agora.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory-update rule, tested on the JVM.
 *
 * The decision used to live inside `MemoryListenerService.onDataChanged`, which is a
 * `WearableListenerService` and therefore unreachable from a unit test — so the one branch that was
 * wrong (an empty snapshot was discarded, and the watch kept answering from memory the user had
 * already deleted) had no test that could have caught it.
 */
class WearMemoryRulesTest {

    @Test
    fun `a missing payload keeps the previous snapshot`() {
        val update = WearMemoryRules.decide(payloadPresent = false, decoded = null)

        assertTrue("a payload that never arrived must not clear the cache", update is MemoryUpdate.Keep)
    }

    @Test
    fun `an undecodable payload keeps the previous snapshot`() {
        // A corrupt delivery is a transport failure. Replacing the user's memory with nothing because
        // one delivery was corrupt is worse than stale.
        val update = WearMemoryRules.decide(payloadPresent = true, decoded = null)

        assertTrue(update is MemoryUpdate.Keep)
    }

    @Test
    fun `an empty snapshot is applied, not ignored`() {
        // The user deleted everything on the phone. Ignoring it means the watch keeps answering from
        // facts they have erased, in a place they cannot see or edit.
        val update = WearMemoryRules.decide(payloadPresent = true, decoded = "".toByteArray())

        assertTrue("an empty snapshot is a delivered fact", update is MemoryUpdate.Apply)
        assertEquals("", (update as MemoryUpdate.Apply).snapshot)
    }

    @Test
    fun `a normal snapshot is applied verbatim`() {
        val text = "- user lives in Pemalang\n- works on Hermes X"
        val update = WearMemoryRules.decide(payloadPresent = true, decoded = text.toByteArray())

        assertEquals(text, (update as MemoryUpdate.Apply).snapshot)
    }

    @Test
    fun `a cleared cache is distinguishable from a never-pushed one`() {
        // The consequence of applying an empty snapshot: the watch's cache becomes empty rather than
        // holding the old text, so the core context is empty too.
        val applied = WearMemoryRules.decide(true, "".toByteArray()) as MemoryUpdate.Apply

        assertEquals("", WearCoreContext.build(applied.snapshot))
        assertFalse(WearCoreContext.wasTruncated(applied.snapshot))
    }

    // ---------- O3: the payload may already be a derived core context ----------

    @Test
    fun `a pre-derived payload is idempotent under the watch's own rule`() {
        // The current phone derives the core context before pushing (O3); an older phone pushes the raw
        // snapshot. The watch applies `cacheValue` to both, so the rule has to be idempotent: deriving
        // an already-derived context must return it unchanged, or every push would append a second
        // truncation note and eventually the note would be the whole context.
        val raw = (1..500).joinToString("\n") { "fact $it: " + "x".repeat(80) }

        val once = WearMemoryRules.cacheValue(raw.toByteArray())
        val twice = WearMemoryRules.cacheValue(once.toByteArray())

        assertEquals("deriving twice must be the same as deriving once", once, twice)
        assertTrue("the result must fit the watch's budget", once.length <= WearCoreContext.MAX_TOKENS * 4)
        assertTrue("the truncation must be stated", once.endsWith("[core context truncated to fit the watch budget]"))
    }

    @Test
    fun `a small raw snapshot and a small derived payload agree`() {
        val small = "- user lives in Pemalang"

        assertEquals(small, WearMemoryRules.cacheValue(small.toByteArray()))
    }

    @Test
    fun `an empty payload stays empty through the rule`() {
        assertEquals("", WearMemoryRules.cacheValue("".toByteArray()))
        assertEquals("", WearMemoryRules.cacheValue(null))
    }
}
