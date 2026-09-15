package com.newoether.agora.autopilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 P3/P5 regression net for the persona channel.
 *
 * These are the tests `scripts/persona_update.sh` runs before it is allowed to bump a pinned ref, so
 * they encode the promises that would otherwise rot silently: markers are exact, removal is total,
 * the adapter is faithful, and the reflection path never sees a persona.
 */
class PersonaStoreTest {

    private val body = "Respond terse. All technical substance stay."


    @Test
    fun `a marker mentioned inside prose is not treated as a block`() {
        // A user's own note about the marker format used to be indistinguishable from a real block:
        // `removeBlock` matched the raw substring and, finding no END, cut everything from the mention
        // to the end of the file — the user's memory, deleted by the startup reconcile (audit A-040).
        val text = "- user lives in Pemalang\n" +
            "note: the persona block is wrapped in <!-- HERMES:PERSONA:CAVEMAN:START --> markers\n" +
            "- user writes Kotlin\n"

        val cleaned = PersonaStore.removeBlock(text, PersonaStore.ID_CAVEMAN)

        assertEquals(text, cleaned)
        assertTrue(PersonaStore.blocks(text).isEmpty())
    }

    @Test
    fun `a real block on its own lines is still removed`() {
        val text = "- user lives in Pemalang\n\n" +
            PersonaStore.block(PersonaStore.ID_CAVEMAN, "Respond terse.") + "\n"

        val cleaned = PersonaStore.removeBlock(text, PersonaStore.ID_CAVEMAN)

        assertEquals("- user lives in Pemalang\n", cleaned)
    }

    private fun context(): android.content.Context {
        val dir = java.nio.file.Files.createTempDirectory("persona-test").toFile()
        val context = io.mockk.mockk<android.content.Context>(relaxed = true)
        io.mockk.every { context.filesDir } returns dir
        return context
    }

    @Test
    fun `block carries both markers around the body`() {
        val block = PersonaStore.block(PersonaStore.ID_CAVEMAN, body)
        assertTrue(block.startsWith("<!-- HERMES:PERSONA:CAVEMAN:START -->"))
        assertTrue(block.endsWith("<!-- HERMES:PERSONA:CAVEMAN:END -->"))
        assertTrue(block.contains(body))
    }

    @Test
    fun `upsert is idempotent`() {
        val once = PersonaStore.upsertBlock("", PersonaStore.ID_CAVEMAN, body)
        val twice = PersonaStore.upsertBlock(once, PersonaStore.ID_CAVEMAN, body)
        assertEquals(once, twice)
    }

    @Test
    fun `upsert replaces a stale block instead of stacking a second one`() {
        val first = PersonaStore.upsertBlock("", PersonaStore.ID_CAVEMAN, "old text")
        val second = PersonaStore.upsertBlock(first, PersonaStore.ID_CAVEMAN, "new text")
        assertFalse(second.contains("old text"))
        assertTrue(second.contains("new text"))
        assertEquals(1, Regex("PERSONA:CAVEMAN:START").findAll(second).count())
    }

    @Test
    fun `remove leaves the rest of the store byte-exact`() {
        val userMemory = "- User lives in Pemalang.\n- User writes Kotlin."
        val withPersona = PersonaStore.upsertBlock(userMemory, PersonaStore.ID_CAVEMAN, body)
        assertEquals(userMemory, PersonaStore.removeBlock(withPersona, PersonaStore.ID_CAVEMAN))
    }

    @Test
    fun `remove keeps the user's trailing newline`() {
        // Regression: an earlier implementation `trim()`-ed the result, so toggling a persona off
        // silently rewrote a file the user owned. Caught by the on-device test, not by review.
        val userMemory = "- fact one\n"
        val withPersona = PersonaStore.upsertBlock(userMemory, PersonaStore.ID_CAVEMAN, body)
        assertEquals(userMemory, PersonaStore.removeBlock(withPersona, PersonaStore.ID_CAVEMAN))
    }

    @Test
    fun `remove keeps the user's leading indentation and blank lines`() {
        val userMemory = "\n\n- fact one\n\n- fact two\n"
        val withPersona = PersonaStore.upsertBlock(userMemory, PersonaStore.ID_CAVEMAN, body)
        assertEquals(userMemory, PersonaStore.removeBlock(withPersona, PersonaStore.ID_CAVEMAN))
    }

    @Test
    fun `remove is complete - no marker survives`() {
        var text = "seed"
        PersonaStore.IDS.forEach { text = PersonaStore.upsertBlock(text, it, body) }
        PersonaStore.IDS.forEach { text = PersonaStore.removeBlock(text, it) }
        assertFalse(PersonaStore.hasAnyMarker(text))
        assertEquals("seed", text)
    }

    @Test
    fun `stripAll removes every persona but keeps user memory`() {
        val userMemory = "- Durable fact one."
        var text = userMemory
        PersonaStore.IDS.forEach { text = PersonaStore.upsertBlock(text, it, body) }
        assertEquals(userMemory, PersonaStore.stripAll(text))
    }

    @Test
    fun `stripAll on persona-free text is a no-op`() {
        val text = "- Just user memory.\n\n## Notes\nnothing here."
        assertEquals(text, PersonaStore.stripAll(text))
    }

    @Test
    fun `blocks reads back both bodies`() {
        var text = ""
        PersonaStore.IDS.forEach { text = PersonaStore.upsertBlock(text, it, "$it body") }
        val blocks = PersonaStore.blocks(text)
        assertEquals(2, blocks.size)
        assertEquals("caveman body", blocks[PersonaStore.ID_CAVEMAN])
        assertEquals("ponytail body", blocks[PersonaStore.ID_PONYTAIL])
    }

    @Test
    fun `adapter drops frontmatter and keeps the rule text verbatim`() {
        val skill = """
            ---
            name: caveman
            description: >
              Ultra-compressed communication mode.
            license: MIT
            ---

            # Caveman

            Respond terse like smart caveman.
        """.trimIndent()
        val adapted = PersonaStore.toBody(skill)
        assertFalse(adapted.contains("name: caveman"))
        assertFalse(adapted.contains("license: MIT"))
        assertTrue(adapted.startsWith("# Caveman"))
        assertTrue(adapted.endsWith("Respond terse like smart caveman."))
    }

    @Test
    fun `adapter passes through text without frontmatter unchanged`() {
        val plain = "# Rules\n\nDo the thing."
        assertEquals(plain, PersonaStore.toBody(plain))
    }

    @Test
    fun `the same enabled set always produces byte-identical store content`() {
        // The real invariant lives in the applier, not in upsert: `desiredContent` strips every block
        // and re-appends in fixed IDS order, so the order the blocks happened to be written in cannot
        // leak into the prompt.
        val applier = PersonaApplier(
            memoryManager = com.newoether.agora.data.MemoryManager(context()),
            log = FakeAdaptationLogDao(),
        )
        val bodies = mapOf(PersonaStore.ID_CAVEMAN to "caveman body", PersonaStore.ID_PONYTAIL to "ponytail body")
        val enabled = PersonaStore.IDS.associateWith { true }

        val cavemanFirst = PersonaStore.IDS.fold("seed") { acc, id ->
            PersonaStore.upsertBlock(acc, id, bodies.getValue(id))
        }
        val ponytailFirst = PersonaStore.IDS.reversed().fold("seed") { acc, id ->
            PersonaStore.upsertBlock(acc, id, bodies.getValue(id))
        }

        assertEquals(
            applier.desiredContent(cavemanFirst, enabled, bodies),
            applier.desiredContent(ponytailFirst, enabled, bodies),
        )
    }
}
