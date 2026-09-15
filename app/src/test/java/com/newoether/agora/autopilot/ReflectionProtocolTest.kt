package com.newoether.agora.autopilot

import android.content.Context
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The conservative extraction contract: the JSON schema is strict, the confidence floor is
 * enforced, and anything unusable results in **nothing** being written.
 */
class ReflectionProtocolTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun parsesAStrictPlanAndKeepsOnlyValidOps() {
        val reply = """
            {"ops":[
              {"op":"add","target_file":"user-preferences","content":"- a","category":"preference","confidence":0.9,"source_quote":"I prefer a"},
              {"op":"add","target_file":"user-preferences","content":"- b","category":"preference","confidence":0.5,"source_quote":"maybe b"},
              {"op":"delete","target_file":"user-preferences","content":"- c","category":"x","confidence":0.99,"source_quote":"remove c"},
              {"op":"update","target_file":"","content":"- d","category":"x","confidence":0.99,"source_quote":"d"}
            ]}
        """.trimIndent()

        val plan = requireNotNull(ReflectionProtocol.parse(reply))

        // Low confidence, unknown op, and blank target are all dropped.
        assertEquals(1, plan.ops.size)
        assertEquals("user-preferences", plan.ops.single().targetFile)
        assertEquals(ReflectionOp.OP_ADD, plan.ops.single().op)
    }

    @Test
    fun stripsAMarkdownCodeFenceAroundTheJson() {
        val reply = "```json\n{\"ops\":[{\"op\":\"add\",\"target_file\":\"f\",\"content\":\"- x\"," +
            "\"category\":\"c\",\"confidence\":0.95,\"source_quote\":\"quote\"}]}\n```"

        val plan = requireNotNull(ReflectionProtocol.parse(reply))

        assertEquals(1, plan.ops.size)
    }

    @Test
    fun refusesProseEmptyAndMalformedReplies() {
        assertNull(ReflectionProtocol.parse("I could not find any durable facts."))
        assertNull(ReflectionProtocol.parse(""))
        assertNull(ReflectionProtocol.parse("{\"ops\":[{\"op\":\"add\"}"))
        assertNull(ReflectionProtocol.parse("{\"unexpected\":true}"))
    }

    @Test
    fun anEmptyOpsArrayIsAValidNoOpPlan() {
        val plan = requireNotNull(ReflectionProtocol.parse("{\"ops\":[]}"))

        assertTrue(plan.ops.isEmpty())
    }

    @Test
    fun thePromptStatesTheConservativeRulesAndTheProvenanceMarker() {
        val prompt = ReflectionProtocol.extractionPrompt("USER: hello", listOf("notes"))

        assertTrue(prompt.contains("When in doubt, extract nothing"))
        assertTrue(prompt.contains(PROVENANCE_TAG))
        assertTrue(prompt.contains("notes"))
        assertTrue(prompt.contains(ReflectionOp.MIN_CONFIDENCE.toString()))
    }

    @Test
    fun engineWritesNothingWhenTheReplyIsUnusable() = runBlocking {
        val harness = harness()

        val outcome = harness.engine.run(
            transcript = "USER: hi",
            existingFiles = emptyList(),
            sourceSessionId = "s1",
        )

        assertEquals(0, outcome.applied)
        assertTrue(harness.log.all().isEmpty())
    }

    @Test
    fun engineSkipsSilentlyWhenTheProviderFails() = runBlocking {
        val harness = harness(reply = null)

        val outcome = harness.engine.run("USER: hi", emptyList(), "s1")

        assertEquals("no reflection reply", outcome.skippedReason)
        assertTrue(harness.log.all().isEmpty())
    }

    @Test
    fun engineRefusesToRunWhenDisabled() = runBlocking {
        val harness = harness(enabled = false)

        val outcome = harness.engine.run("USER: hi", emptyList(), "s1")

        assertEquals("disabled", outcome.skippedReason)
        assertTrue(harness.log.all().isEmpty())
    }

    @Test
    fun engineAppliesEveryValidOpAndTagsItWithProvenance() = runBlocking {
        val harness = harness(
            reply = """
                {"ops":[
                  {"op":"add","target_file":"facts-a","content":"- one","category":"c","confidence":0.9,"source_quote":"q1"},
                  {"op":"add","target_file":"facts-b","content":"- two","category":"c","confidence":0.9,"source_quote":"q2"}
                ]}
            """.trimIndent()
        )

        val outcome = harness.engine.run("USER: hi", emptyList(), "session-7")

        assertEquals(2, outcome.applied)
        assertEquals(2, harness.log.all().size)
        assertTrue(harness.memoryManager.readFile("facts-a").contains(PROVENANCE_TAG))
        assertTrue(harness.memoryManager.readFile("facts-b").contains(PROVENANCE_TAG))
        assertEquals("session-7", harness.log.all().first().sourceSessionId)
    }

    @Test
    fun engineStopsAtTheDailyCapAndLeavesTheRestUnwritten() = runBlocking {
        val harness = harness(
            dailyCap = 1,
            reply = """
                {"ops":[
                  {"op":"add","target_file":"cap-a","content":"- one","category":"c","confidence":0.9,"source_quote":"q1"},
                  {"op":"add","target_file":"cap-b","content":"- two","category":"c","confidence":0.9,"source_quote":"q2"}
                ]}
            """.trimIndent()
        )

        val outcome = harness.engine.run("USER: hi", emptyList(), "s1")

        assertEquals(1, outcome.applied)
        assertEquals(1, harness.log.all().size)
        assertTrue(File(harness.filesDir, "memory_db/cap-a.md").exists())
        assertTrue(!File(harness.filesDir, "memory_db/cap-b.md").exists())
    }

    @Test
    fun engineRefusesToRunWhenTheCapIsAlreadyExhausted() = runBlocking {
        val harness = harness(dailyCap = 1)
        harness.log.insert(
            AdaptationEntry(
                timestamp = System.currentTimeMillis(),
                store = AdaptationEntry.STORE_MEMORY,
                targetFile = "earlier",
                beforeSnapshot = null,
                afterSnapshot = "- earlier\n",
                reason = "add",
                sourceSessionId = "s0",
                status = AdaptationEntry.STATUS_APPLIED,
            )
        )

        val outcome = harness.engine.run("USER: hi", emptyList(), "s1")

        assertEquals("daily cap reached", outcome.skippedReason)
        assertEquals(1, harness.log.all().size)
    }

    @Test
    fun aZeroCapDisablesWritesEntirely() = runBlocking {
        val harness = harness(dailyCap = 0)

        val outcome = harness.engine.run("USER: hi", emptyList(), "s1")

        assertEquals("daily cap reached", outcome.skippedReason)
        assertTrue(harness.log.all().isEmpty())
    }

    // ── harness ─────────────────────────────────────────────────────────────

    /**
     * Stubs `filesDir` from a parameter. A bare class property on the right-hand side would resolve
     * to the mock's own member being stubbed and record nothing.
     */
    private fun contextFor(root: File): Context = mockk {
        every { this@mockk.filesDir } returns root
    }

    /** `DebugLog` delegates to `android.util.Log`, which is a stub in JVM tests. */
    private fun silenceAndroidLog() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0
    }

    private class Harness(
        val filesDir: File,
        val memoryManager: MemoryManager,
        val log: FakeAdaptationLogDao,
        val engine: ReflectionEngine,
    )

    private fun harness(
        reply: String? = """{"ops":[]}""",
        enabled: Boolean = true,
        dailyCap: Int = AutopilotSettings.DEFAULT_DAILY_CAP,
    ): Harness {
        silenceAndroidLog()
        val filesDir = temporaryFolder.newFolder("files-${System.nanoTime()}")
        val context = contextFor(filesDir)
        val memoryManager = MemoryManager(context)
        val skillManager = SkillManager(context)
        val log = FakeAdaptationLogDao()
        val applier = MemoryApplier(memoryManager, skillManager, log)
        val controls = object : AutopilotControls {
            override suspend fun isEnabled() = enabled
            override suspend fun currentDailyCap() = dailyCap
            override suspend fun underDailyCap(log: AdaptationLogDao, sinceMillis: Long): Boolean =
                dailyCap > 0 &&
                log.countAutopilotSince(sinceMillis, AdaptationEntry.STORE_ACTIVE_MEMORY) < dailyCap
        }
        return Harness(
            filesDir = filesDir,
            memoryManager = memoryManager,
            log = log,
            engine = ReflectionEngine(
                reflect = { _, _ -> reply },
                applier = applier,
                log = log,
                settings = controls,
            ),
        )
    }
}
