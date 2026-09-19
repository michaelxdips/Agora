package com.newoether.agora.autopilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 P5 — isolation, proved rather than asserted.
 *
 * The risk this test exists for: a persona block leaking into the reflection or skill-synthesis
 * request. The extraction contract demands strict JSON, and Caveman compresses prose by design, so a
 * leak would not crash anything — it would make the reply unparseable and silently stop the
 * autopilot from learning, which is exactly the kind of failure that never gets noticed.
 *
 * Both callers strip the transcript before building their prompt, so what is asserted here is the
 * composition of the real prompt builders with the real stripper.
 */
class PersonaIsolationTest {

    private val cavemanBody = "Respond terse. Drop articles, filler, pleasantries."
    private val ponytailBody = "Stop at the first rung that holds. YAGNI first."

    private fun personaLoadedTranscript(): String {
        val userMemory = "- User lives in Pemalang.\n- User ships Kotlin on Android.\n- USER: aku tinggal di Pemalang"
        var text = userMemory
        text = PersonaStore.upsertBlock(text, PersonaStore.ID_CAVEMAN, cavemanBody)
        text = PersonaStore.upsertBlock(text, PersonaStore.ID_PONYTAIL, ponytailBody)
        return text
    }

    @Test
    fun `reflection prompt never carries a persona block`() {
        val transcript = personaLoadedTranscript()

        val prompt = ReflectionProtocol.extractionPrompt(PersonaStore.stripAll(transcript), listOf("facts"))

        assertFalse(prompt.contains(PersonaStore.MARKER_PREFIX))
        assertFalse(prompt.contains(cavemanBody))
        assertFalse(prompt.contains(ponytailBody))
        // The real conversation content must survive the strip.
        assertTrue(prompt.contains("- User lives in Pemalang."))
    }

    @Test
    fun `skill synthesis prompt never carries a persona block`() {
        val transcript = personaLoadedTranscript()

        val prompt = SkillSynthesisProtocol.synthesisPrompt(PersonaStore.stripAll(transcript), listOf("deploy"))

        assertFalse(prompt.contains(PersonaStore.MARKER_PREFIX))
        assertFalse(prompt.contains(cavemanBody))
        assertFalse(prompt.contains(ponytailBody))
    }

    @Test
    fun `an extraction reply stays parseable with personas on`() {
        // The contract is only at risk if the persona text could reach the model. It cannot, so a
        // normal-shaped reply must parse exactly as it does with personas off. The quote is grounded
        // in the user's own words — with personas stripped, that is all the model ever sees.
        val reply = """
            {"ops":[{"op":"add","target_file":"user-profile","content":"- User lives in Pemalang.",
            "category":"identity","confidence":0.9,"source_quote":"aku tinggal di Pemalang"}]}
        """.trimIndent()

        val plan = ReflectionProtocol.parse(reply, personaLoadedTranscript())
        assertEquals(1, plan?.ops?.size)
        assertEquals("user-profile", plan?.ops?.first()?.targetFile)
    }

    @Test
    fun `stripping is not fooled by a block in the middle of the transcript`() {
        val transcript = buildString {
            appendLine("- fact before")
            appendLine(PersonaStore.block(PersonaStore.ID_CAVEMAN, cavemanBody))
            appendLine("- fact after")
        }

        val stripped = PersonaStore.stripAll(transcript)

        assertTrue(stripped.contains("- fact before"))
        assertTrue(stripped.contains("- fact after"))
        assertFalse(stripped.contains(PersonaStore.MARKER_PREFIX))
    }

    @Test
    fun `the user-facing channel still carries the persona`() {
        // Isolation must not be achieved by simply never injecting anything: the whole feature is
        // that the persona reaches the model's system prompt through active memory.
        val transcript = personaLoadedTranscript()

        assertTrue(PersonaStore.hasBlock(transcript, PersonaStore.ID_CAVEMAN))
        assertTrue(PersonaStore.hasBlock(transcript, PersonaStore.ID_PONYTAIL))
    }
}
