package com.newoether.agora.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch's core-context builder — the piece a wrong answer would be blamed on but never traced to.
 *
 * A watch answer is only useful if the phone's memory reaches it, and only safe if a chatty memory
 * file cannot blow the 500-token budget. Both halves are asserted here.
 */
class WearCoreContextTest {

    @Test
    fun `empty snapshot produces an empty prompt`() {
        assertEquals("", WearCoreContext.build(""))
        assertEquals("", WearCoreContext.build("   \n\n  "))
    }

    @Test
    fun `a short snapshot passes through unchanged`() {
        val snapshot = "- User lives in Pemalang.\n- User writes Kotlin."
        assertEquals(snapshot, WearCoreContext.build(snapshot))
        assertFalse(WearCoreContext.wasTruncated(snapshot))
    }

    @Test
    fun `persona blocks never reach the watch prompt`() {
        val snapshot = buildString {
            appendLine("- User lives in Pemalang.")
            appendLine(PersonaMarkers.start)
            appendLine("Respond terse like smart caveman.")
            appendLine(PersonaMarkers.end)
        }
        val built = WearCoreContext.build(snapshot)
        assertFalse(built.contains("HERMES:PERSONA"))
        assertFalse(built.contains("smart caveman"))
        assertTrue(built.contains("User lives in Pemalang."))
    }

    @Test
    fun `an oversized snapshot is truncated inside the budget and says so`() {
        val snapshot = (1..400).joinToString("\n") { "- durable fact number $it about the user" }
        val built = WearCoreContext.build(snapshot)
        assertTrue("must announce the truncation", built.endsWith("[core context truncated to fit the watch budget]"))
        assertTrue(
            "must stay inside the token ceiling",
            WearCoreContext.estimateTokens(built) <= WearCoreContext.MAX_TOKENS,
        )
        assertTrue(WearCoreContext.wasTruncated(snapshot))
    }

    @Test
    fun `truncation keeps whole lines, never a half fact`() {
        val snapshot = (1..400).joinToString("\n") { "- durable fact number $it about the user" }
        val built = WearCoreContext.build(snapshot)
        built.lineSequence()
            .filter { it.startsWith("- ") }
            .forEach { line ->
                assertTrue("truncated mid-fact: $line", line.matches(Regex("- durable fact number \\d+ about the user")))
            }
    }

    @Test
    fun `crlf and blank-line runs are normalised`() {
        val built = WearCoreContext.build("- one\r\n\r\n\r\n\r\n- two")
        assertEquals("- one\n\n- two", built)
    }

    @Test
    fun `token estimate is monotonic and zero for empty`() {
        assertEquals(0, WearCoreContext.estimateTokens(""))
        assertTrue(
            WearCoreContext.estimateTokens("x".repeat(400)) >
                WearCoreContext.estimateTokens("x".repeat(40)),
        )
    }

    @Test
    fun `a user note that merely mentions the marker text keeps the rest of the snapshot`() {
        // The marker prefix inside prose is not a block. Matching the raw substring treated it as one,
        // and the unterminated branch then dropped every line after the mention — the user's memory,
        // silently, while the watch reported a clean core context (audit A-037/A-040).
        val snapshot = "- user lives in Pemalang\n" +
            "the persona block is delimited by <!-- HERMES:PERSONA:CAVEMAN:START --> in active memory\n" +
            "- user writes Kotlin\n"

        val built = WearCoreContext.build(snapshot)

        assertTrue("the user's own line was deleted: $built", built.contains("- user writes Kotlin"))
        assertTrue(built.contains("- user lives in Pemalang"))
    }

    @Test
    fun `a real unterminated block is still removed entirely`() {
        val snapshot = "- user lives in Pemalang\n" +
            "${PersonaMarkers.start}\n" +
            "Respond terse. Drop articles.\n"

        val built = WearCoreContext.build(snapshot)

        assertEquals("- user lives in Pemalang", built)
    }

    @Test
    fun `a terminated block never leaks its rule text`() {
        val snapshot = "- user lives in Pemalang\n" +
            "${PersonaMarkers.start}\nRespond terse.\n${PersonaMarkers.end}\n- user writes Kotlin\n"

        val built = WearCoreContext.build(snapshot)

        assertFalse("the rule text survived: $built", built.contains("Respond terse"))
        assertTrue(built.contains("- user lives in Pemalang"))
        assertTrue(built.contains("- user writes Kotlin"))
    }

    private object PersonaMarkers {
        const val start = "<!-- HERMES:PERSONA:CAVEMAN:START -->"
        const val end = "<!-- HERMES:PERSONA:CAVEMAN:END -->"
    }
}
