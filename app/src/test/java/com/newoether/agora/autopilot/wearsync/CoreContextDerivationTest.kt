package com.newoether.agora.autopilot.wearsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O3 — the payload that travels to the watch is the derived core context, not the raw snapshot.
 *
 * The measurement that motivated this: the phone base64'd the **whole** active-memory file, so a user
 * with a 100 KB memory transferred ~133 KB over the Data Layer so the watch could truncate it to 500
 * tokens (2000 characters) and discard the rest. The saving is not a micro-optimisation — it is the
 * difference between a payload the watch uses and a payload it mostly throws away, on a Bluetooth link
 * to a device on a battery.
 *
 * The derivation has to match the watch's own rule, because the watch keeps its truncation as a safety
 * net and the two must not disagree about what "the core context" is. These tests pin the shared
 * numbers, and `WearCoreContextTest` on the watch side pins the rule.
 */
class CoreContextDerivationTest {

    @Test
    fun `the budget matches the watch's`() {
        // The two modules share no code (that is deliberate: the watch must build without the phone's
        // module graph), so the one thing they must agree on is asserted from both sides.
        assertEquals(500, CoreContextDerivation.MAX_TOKENS)
        assertEquals(2_000, CoreContextDerivation.MAX_CHARS)
    }

    @Test
    fun `a small snapshot travels unchanged`() {
        val snapshot = "- user lives in Pemalang\n- works on Hermes X"

        val payload = CoreContextDerivation.payloadFor(snapshot)

        assertEquals(snapshot, payload)
        assertTrue("a small snapshot must not be annotated", !payload.contains("truncated"))
    }

    @Test
    fun `a large snapshot is truncated to the budget and says so`() {
        val snapshot = (1..500).joinToString("\n") { "line $it of the user's active memory" }

        val payload = CoreContextDerivation.payloadFor(snapshot)

        assertTrue(
            "the payload must fit the watch's budget: ${payload.length} chars",
            payload.length <= CoreContextDerivation.MAX_CHARS,
        )
        assertTrue("the truncation must be visible: $payload", payload.endsWith(CoreContextDerivation.TRUNCATION_NOTE))
    }

    @Test
    fun `a huge snapshot costs a fraction of the raw payload`() {
        // The number that matters: what the Data Layer has to carry. This is the measurement the
        // optimisation is justified by, so it is asserted rather than described.
        val snapshot = buildString {
            repeat(400) { index -> append("- fact $index: ").append("x".repeat(200)).append('\n') }
        }
        assertTrue("the fixture must be large enough to be interesting", snapshot.length > 80_000)

        val rawBytes = CoreContextDerivation.payloadBytesForTest(snapshot)
        val derivedBytes = CoreContextDerivation.payloadBytesForTest(
            CoreContextDerivation.payloadFor(snapshot)
        )

        assertTrue(
            "the derived payload must be far smaller: $rawBytes raw vs $derivedBytes derived",
            derivedBytes < rawBytes / 10,
        )
        // And the absolute ceiling: 2000 chars of UTF-8 is ~2 KB, +33% base64, with room for the note.
        assertTrue("derived payload is not bounded: $derivedBytes bytes", derivedBytes < 4_000)
    }

    @Test
    fun `persona blocks are stripped before the payload is derived`() {
        // The watch has no persona channel, so a persona block would spend its 500-token budget on
        // instructions the watch app does not follow. The markers are the real ones (`PersonaStore`
        // knows two ids, caveman and ponytail) — a made-up id would prove nothing, because `stripAll`
        // only removes ids it knows.
        val snapshot = buildString {
            append("- a fact about the user\n")
            // The separator `PersonaStore` writes between the user's memory and a block. It is private
            // there, so the literal is repeated here with the reason: `removeBlock` consumes exactly
            // this string, and a different one would leave the block in place.
            append("\n\n")
            append(com.newoether.agora.autopilot.PersonaStore.startMarker(
                com.newoether.agora.autopilot.PersonaStore.ID_CAVEMAN
            )).append('\n')
            append("Always answer as a caveman.\n")
            append(com.newoether.agora.autopilot.PersonaStore.endMarker(
                com.newoether.agora.autopilot.PersonaStore.ID_CAVEMAN
            )).append('\n')
            append("- another fact\n")
        }

        val payload = CoreContextDerivation.payloadFor(snapshot)

        assertTrue("the persona body reached the watch: $payload", !payload.contains("caveman"))
        assertTrue("a persona marker reached the watch: $payload", !payload.contains("HERMES:PERSONA"))
        assertTrue(payload.contains("- a fact about the user"))
        assertTrue(payload.contains("- another fact"))
    }

    @Test
    fun `an empty snapshot produces an empty payload rather than a note`() {
        // The watch must be able to tell "the user's memory is empty" from "the context was cut": an
        // empty payload is the former, and the watch clears its cache on it (see WearMemoryRules).
        assertEquals("", CoreContextDerivation.payloadFor(""))
        assertEquals("", CoreContextDerivation.payloadFor("   \n  \n"))
    }

    @Test
    fun `a snapshot that fits exactly is not annotated`() {
        val exact = "x".repeat(CoreContextDerivation.MAX_CHARS)

        assertEquals(exact, CoreContextDerivation.derive(exact))
    }

    @Test
    fun `whole lines are kept, never half a line`() {
        val snapshot = buildString {
            append("y".repeat(1_500)).append('\n')
            append("z".repeat(1_500))
        }

        val payload = CoreContextDerivation.derive(snapshot)

        // The first line fits; the second does not and is dropped entirely rather than cut mid-line.
        assertTrue(payload.startsWith("y".repeat(1_500)))
        assertTrue("a partial second line was kept: ${payload.takeLast(60)}", !payload.contains("z"))
    }
}
