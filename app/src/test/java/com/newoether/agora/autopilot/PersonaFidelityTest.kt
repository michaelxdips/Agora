package com.newoether.agora.autopilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 6 P3/P6 — the persona defaults must be **faithful to upstream**, and their cost must be
 * measured rather than guessed.
 *
 * Two things this locks down:
 *  1. the vendored rule text in the repo, the copy shipped as an APK asset, and the file the app
 *     reads at runtime must all be the same bytes — a mismatch is how "Reset to default" quietly
 *     restores something that is not upstream;
 *  2. the persona block's input cost is reported by the app's own estimator, so the number in
 *     `STATUS.md` / `AUDIT_REPORT.md` is reproducible instead of quoted.
 *
 * This test runs with the module directory as its working directory (`app/`), so repo-relative paths
 * are resolved from the parent.
 */
class PersonaFidelityTest {

    private val moduleDir = File(System.getProperty("user.dir")).absoluteFile
    private val repoRoot: File = if (File(moduleDir, "personas").isDirectory) moduleDir else moduleDir.parentFile

    private fun readIfPresent(relative: String): String? =
        File(repoRoot, relative).takeIf { it.isFile }?.readText()

    @Test
    fun `the vendored rule text is byte-identical to the copy shipped in the APK`() {
        PersonaStore.IDS.forEach { id ->
            val vendored = readIfPresent("personas/$id/SKILL.md")
            val asset = readIfPresent("app/src/main/assets/personas/$id/SKILL.md")
            assertTrue("missing vendored file for $id", vendored != null)
            assertEquals("asset copy drifted from the vendored $id file", vendored, asset)
        }
    }

    @Test
    fun `the vendored files match the hashes recorded in upstream lock`() {
        val lock = readIfPresent("personas/upstream.lock")
        assertTrue("upstream.lock missing", lock != null)
        PersonaStore.IDS.forEach { id ->
            val vendored = readIfPresent("personas/$id/SKILL.md")!!
            val recorded = Regex("\"id\": \"$id\".*?\"sha256\": \"([0-9a-f]{64})\"", RegexOption.DOT_MATCHES_ALL)
                .find(lock!!)?.groupValues?.get(1)
            assertEquals("sha256 recorded for $id does not match the vendored file", recorded, sha256(vendored))
        }
    }

    @Test
    fun `the vendored text still carries the upstream rule content`() {
        // The adapter must not be allowed to mangle the defaults on the way in. These are load-bearing
        // phrases from each upstream skill file, chosen because deleting them changes behaviour:
        // Caveman's compression boundary and Ponytail's ladder floor.
        val caveman = PersonaStore.toBody(readIfPresent("personas/caveman/SKILL.md")!!)
        val ponytail = PersonaStore.toBody(readIfPresent("personas/ponytail/SKILL.md")!!)

        assertTrue("caveman lost its 'never compress code' boundary", caveman.contains("Code blocks unchanged"))
        assertTrue("caveman lost the meaning-preservation rule", caveman.contains("Never drop not/never/no/only/except"))
        assertTrue("ponytail lost the YAGNI rung", ponytail.contains("Does this need to exist at all?"))
        assertTrue("ponytail lost the never-simplify-away list", ponytail.contains("Never simplify away"))
    }

    @Test
    fun `adapter output is smaller than the raw file and never empty`() {
        PersonaStore.IDS.forEach { id ->
            val raw = readIfPresent("personas/$id/SKILL.md")!!
            val body = PersonaStore.toBody(raw)
            assertTrue("$id adapter produced empty body", body.isNotBlank())
            assertTrue("$id adapter grew the text", body.length < raw.length)
        }
    }

    @Test
    fun `input cost is measured and reported by the app estimator`() {
        val bodies = PersonaStore.IDS.associateWith {
            PersonaStore.toBody(readIfPresent("personas/$it/SKILL.md")!!)
        }
        val summary = PersonaCostReport.summary(bodies)
        // Printed so the number lands in the test report; asserted so it can never be zero/unknown.
        println("PERSONA_COST $summary")
        PersonaStore.IDS.forEach { id ->
            assertTrue("$id reported no input cost", PersonaCostReport.inputTokenCost(id, bodies.getValue(id)) > 0)
        }
        assertTrue(
            "combined cost should exceed a single persona",
            PersonaCostReport.combinedInputTokenCost(bodies) >
                PersonaCostReport.inputTokenCost(
                    PersonaStore.ID_CAVEMAN,
                    bodies.getValue(PersonaStore.ID_CAVEMAN),
                ),
        )
    }

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
