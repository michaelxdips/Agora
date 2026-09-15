package com.newoether.agora.autopilot

import android.content.Context
import com.newoether.agora.data.SkillManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
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
 * Phase 5 exit criterion: a generalized skill draft is created **via SkillManager** from a
 * multi-tool session, with the same snapshot/rollback parity as memory.
 */
class SkillSynthesizerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var skillManager: SkillManager
    private lateinit var log: FakeAdaptationLogDao
    private lateinit var applier: MemoryApplier
    private lateinit var synthesizer: SkillSynthesizer

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0

        filesDir = temporaryFolder.newFolder("files")
        val memoryManager = com.newoether.agora.data.MemoryManager(contextFor(filesDir))
        skillManager = SkillManager(contextFor(filesDir))
        log = FakeAdaptationLogDao()
        applier = MemoryApplier(memoryManager, skillManager, log)
        synthesizer = SkillSynthesizer(applier, log, controls())
    }

    private fun contextFor(root: File): Context = mockk {
        every { this@mockk.filesDir } returns root
    }

    private fun controls(enabled: Boolean = true, cap: Int = 5) = object : AutopilotControls {
        override suspend fun isEnabled() = enabled
        override suspend fun currentDailyCap() = cap
        override suspend fun underDailyCap(log: AdaptationLogDao, sinceMillis: Long) =
            cap > 0 && log.countAutopilotSince(sinceMillis, AdaptationEntry.STORE_ACTIVE_MEMORY) < cap
    }

    @Test
    fun aMultiToolSessionProducesASkillDraftThroughSkillManager() = runBlocking {
        val reply = """
            {"name":"deploy-static-site","trigger":"When deploying a static site to a server",
             "steps":["Build the site","Copy artifacts to the target","Reload the web server"],
             "pitfalls":["Do not skip the build step"]}
        """.trimIndent()

        val id = synthesizer.synthesize(
            transcript = "USER: deploy it\nMODEL: ran 3 tools...",
            existingSkills = emptyList(),
            reflect = { _, _ -> reply },
            sourceSessionId = "session-tools",
        )

        assertTrue("a draft must be written", id != null)
        // Written through SkillManager, i.e. readable from the real skill store.
        val content = skillManager.readFile("deploy-static-site")
        assertTrue(content.contains("# deploy-static-site"))
        assertTrue(content.contains("## When to use"))
        assertTrue(content.contains("1. Build the site"))
        assertTrue(content.contains("## Pitfalls"))
        assertTrue(content.contains(PROVENANCE_TAG))

        // Same provenance + journal parity as memory.
        val entry = requireNotNull(log.find(requireNotNull(id)))
        assertEquals(AdaptationEntry.STORE_SKILL, entry.store)
        assertEquals("session-tools", entry.sourceSessionId)
        assertEquals(AdaptationEntry.STATUS_APPLIED, entry.status)
        assertNull(entry.beforeSnapshot)
    }

    @Test
    fun aDraftIsFullyUndoableAndRemovesTheCreatedSkillFile() = runBlocking {
        val reply = """{"name":"temp-skill","trigger":"t","steps":["s"],"pitfalls":[]}"""
        val id = synthesizer.synthesize(
            "USER: x", emptyList(), { _, _ -> reply }, "s1",
        )
        val entry = requireNotNull(log.find(requireNotNull(id)))
        assertTrue(File(filesDir, "skill_db/temp-skill.md").exists())

        assertTrue(applier.undo(entry))

        assertFalse("undo must remove the created skill file", File(filesDir, "skill_db/temp-skill.md").exists())
        assertEquals(AdaptationEntry.STATUS_USER_ROLLED_BACK, log.find(entry.id)?.status)
    }

    @Test
    fun aSkipReplyWritesNothing() = runBlocking {
        val id = synthesizer.synthesize("USER: x", emptyList(), { _, _ -> """{"skip":true}""" }, "s1")

        assertNull(id)
        assertTrue(log.all().isEmpty())
        assertTrue(skillManager.listFiles().isEmpty())
    }

    @Test
    fun aFailedSynthesisCallWritesNothing() = runBlocking {
        val id = synthesizer.synthesize("USER: x", emptyList(), { _, _ -> null }, "s1")

        assertNull(id)
        assertTrue(log.all().isEmpty())
    }

    @Test
    fun synthesisIsBlockedByTheMasterToggleAndTheDailyCap() = runBlocking {
        val off = SkillSynthesizer(applier, log, controls(enabled = false))
        assertNull(
            off.synthesize("USER: x", emptyList(), { _, _ -> """{"name":"n","trigger":"t","steps":["s"]}""" }, "s1")
        )

        val capped = SkillSynthesizer(applier, log, controls(cap = 0))
        assertNull(
            capped.synthesize("USER: x", emptyList(), { _, _ -> """{"name":"n","trigger":"t","steps":["s"]}""" }, "s1")
        )
        assertTrue(log.all().isEmpty())
    }

    @Test
    fun anExistingSkillNameIsRejectedWithoutTouchingTheExistingFile() = runBlocking {
        skillManager.createFile("taken", "# taken\n", "existing")
        val before = File(filesDir, "skill_db/taken.md").readBytes()
        val reply = """{"name":"taken","trigger":"t","steps":["s"],"pitfalls":[]}"""

        val id = synthesizer.synthesize("USER: x", listOf("taken"), { _, _ -> reply }, "s1")

        assertNull(id)
        assertEquals(before.toList(), File(filesDir, "skill_db/taken.md").readBytes().toList())
        assertTrue(log.all().isEmpty())
    }

    @Test
    fun anExistingSkillNameWithTheMdSuffixIsAlsoRejected() = runBlocking {
        // The production caller passes `SkillManager.listFiles().map { it.name }`, which yields file
        // names **with** `.md`, while a draft carries a bare name. The old comparison asked whether
        // "taken" equals "taken.md" — never true — so a colliding draft overwrote the user's skill.
        skillManager.createFile("taken", "# taken\n", "existing")
        val before = File(filesDir, "skill_db/taken.md").readBytes()
        val reply = """{"name":"taken","trigger":"t","steps":["s"],"pitfalls":[]}"""

        val id = synthesizer.synthesize("USER: x", listOf("taken.md"), { _, _ -> reply }, "s1")

        assertNull("a colliding draft must be refused, not applied", id)
        assertEquals(before.toList(), File(filesDir, "skill_db/taken.md").readBytes().toList())
        assertTrue(log.all().isEmpty())
    }

    @Test
    fun candidateDetectionFollowsTheMandateThresholds() {
        assertTrue(SkillCandidateDetector.isCandidate(chainedToolCalls = 3, userCorrections = 0, succeeded = true))
        assertTrue(SkillCandidateDetector.isCandidate(chainedToolCalls = 0, userCorrections = 2, succeeded = true))
        assertFalse(SkillCandidateDetector.isCandidate(chainedToolCalls = 2, userCorrections = 1, succeeded = true))
        assertFalse("a failed session is never a candidate", SkillCandidateDetector.isCandidate(5, 5, false))
        assertTrue(SkillCandidateDetector.reason(3, 2).contains("3 chained tool calls"))
        assertTrue(SkillCandidateDetector.reason(3, 2).contains("2 user corrections"))
    }

    @Test
    fun theSynthesisPromptForbidsTranscriptionAndStatesTheContract() {
        val prompt = SkillSynthesisProtocol.synthesisPrompt("USER: x", listOf("existing-skill"))

        assertTrue(prompt.contains("Generalize"))
        assertTrue(prompt.contains("never transcribe"))
        assertTrue(prompt.contains("existing-skill"))
        assertTrue(prompt.contains("pitfalls"))
    }

    @Test
    fun aMalformedOrIncompleteDraftIsRejected() {
        assertNull(SkillSynthesisProtocol.parse("not json"))
        assertNull(SkillSynthesisProtocol.parse("""{"name":"n"}"""))
        assertNull(SkillSynthesisProtocol.parse("""{"name":"","trigger":"t","steps":["s"]}"""))
        assertNull(SkillSynthesisProtocol.parse("""{"name":"n","trigger":"t","steps":[]}"""))
        assertTrue(SkillSynthesisProtocol.parse("""{"name":"n","trigger":"t","steps":["s"]}""") != null)
    }
}
