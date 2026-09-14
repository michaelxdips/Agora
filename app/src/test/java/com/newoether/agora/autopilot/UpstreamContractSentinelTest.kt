package com.newoether.agora.autopilot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UPSTREAM-REGRESSION SENTINEL — the single highest-value deliverable of the hardening pass.
 *
 * The fork's whole survival strategy is that Hermes only ever *reads* upstream contracts: the memory
 * store API, the `SkillManager` API, the active-memory injection site, and the settings/navigation
 * attach points. If upstream renames a method, moves a file, or changes the shape of the injection
 * call, every autopilot feature breaks at once — and until this test existed, it would break
 * *silently*: the code would still compile, the reflection would simply stop finding facts.
 *
 * This test asserts the **exact source-level facts the fork depends on** by reading upstream files
 * from the repository root. It is wired into `scripts/upstream_sync.sh` as a sync gate, so an upstream
 * release that moves any of these fails the sync loudly and precisely, naming the symbol.
 *
 * Rules for this file:
 *  * assert on the *symbol* (declaration text), never on line numbers — line drift is not a contract
 *    change and must not fail the gate;
 *  * keep the list short and load-bearing. A sentinel that fires on cosmetic changes gets muted, and
 *    a muted sentinel is worse than none.
 */
class UpstreamContractSentinelTest {

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
        assertTrue("upstream contract file is missing: $relative", file.isFile)
        return file.readText()
    }

    // ── 1. memory store API (MemoryManager) ──────────────────────────────────

    @Test
    fun `memory store still exposes the API the autopilot writes through`() {
        val source = source("app/src/main/java/com/newoether/agora/data/MemoryManager.kt")
        listOf(
            "class MemoryManager(context: Context)",
            "fun getActiveMemory(): String",
            "fun updateActiveMemory(",
            "fun readFile(name: String): String",
            "fun createFile(",
            "fun editFile(",
            "fun deleteFile(name: String): String",
            "fun listFiles(): List<MemoryFileInfo>",
            "val activeMemoryRevision",
        ).forEach { symbol ->
            assertTrue("MemoryManager no longer declares `$symbol`", source.contains(symbol))
        }
    }

    @Test
    fun `active memory still lives at filesDir slash active_memory dot md`() {
        val source = source("app/src/main/java/com/newoether/agora/data/MemoryManager.kt")
        // The persona channel is a delimited block inside this exact file. If upstream moves it, the
        // personas stop being injected — and nothing else would notice.
        assertTrue(
            "active memory file name changed",
            source.contains("File(context.filesDir, \"active_memory.md\")"),
        )
    }

    // ── 2. skill store API (SkillManager) ────────────────────────────────────

    @Test
    fun `skill store still exposes the API skill synthesis writes through`() {
        val source = source("app/src/main/java/com/newoether/agora/data/SkillManager.kt")
        listOf(
            "class SkillManager(context: Context)",
            "fun catalog(): String",
            "fun readFile(",
            "fun createFile(",
            "fun editFile(",
            "fun deleteFile(",
            "fun listFiles(): List<SkillFileInfo>",
        ).forEach { symbol ->
            assertTrue("SkillManager no longer declares `$symbol`", source.contains(symbol))
        }
    }

    // ── 3. active-memory injection site ──────────────────────────────────────

    @Test
    fun `active memory is still injected into the resolved system prompt`() {
        val source = source("app/src/main/java/com/newoether/agora/viewmodel/GenerationRequestBuilder.kt")
        // P5 isolation is implemented at this call site; if the injection moves, the strip moves with
        // it or personas leak into the reflection request. This is why the sentinel asserts the call.
        assertTrue(
            "active memory is no longer read at the injection site",
            source.contains("memoryManager.getActiveMemory()"),
        )
        assertTrue(
            "active memory is no longer fed into the prompt runtime values",
            source.contains("activeMemory = activeMemoryDeferred.await()"),
        )
    }

    // ── 4. navigation / settings attach points ───────────────────────────────

    @Test
    fun `settings still dispatches through the selectedCategory switch`() {
        val source = source("app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt")
        listOf(
            "private data class SettingsCategory(",
            "fun SettingsScreen(",
            "\"memory\" -> SettingsMemoryPage(",
            "\"skills\" -> SettingsSkillsPage(",
        ).forEach { symbol ->
            assertTrue("SettingsScreen no longer declares `$symbol`", source.contains(symbol))
        }
    }

    @Test
    fun `the hermes integration points are still marked and still present`() {
        // The guard enforces the marker; this asserts the *edits* the marker describes still exist, so
        // a sync that silently drops a Hermes hunk is caught by a test rather than by a user.
        assertTrue(
            "autopilot trigger hook vanished from MainActivity",
            source("app/src/main/java/com/newoether/agora/MainActivity.kt")
                .contains("AutopilotTriggerObserver"),
        )
        assertTrue(
            "adaptation history entry vanished from SettingsScreen",
            source("app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt")
                .contains("\"adaptation\""),
        )
        assertTrue(
            "applicationId override vanished from app/build.gradle.kts",
            source("app/build.gradle.kts").contains("applicationId = \"com.hermes.app\""),
        )
    }

    // ── 5. the stores the autopilot owns (own Room DB, own settings) ─────────

    @Test
    fun `agora's own room database is still not extended by the autopilot`() {
        // N2 in one assertion: the autopilot database is separate and versioned on its own.
        val source = source("app/src/main/java/com/newoether/agora/autopilot/AdaptationLog.kt")
        assertTrue(source.contains("DB_NAME = \"hermes_autopilot.db\""))
        assertTrue(
            "Agora's Room DB must not gain autopilot tables",
            !source.contains("com.newoether.agora.data.local"),
        )
    }
}
