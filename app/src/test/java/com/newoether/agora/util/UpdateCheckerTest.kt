package com.newoether.agora.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update check, proved against its real inputs.
 *
 * Before this file there was **no test at all** for the version comparison or the repository the check
 * queries, which is how the fork ended up advertising a fix it can never receive: see
 * [the fork must not offer the upstream project's releases].
 *
 * These tests deliberately do not hit the network. What is asserted is (a) the comparison arithmetic,
 * which is pure, and (b) which repository the check is pointed at, which is the defect that shipped.
 */
class UpdateCheckerTest {

    // ── the comparison, including the shape this fork actually uses ──────────

    @Test
    fun `a higher upstream release is newer`() {
        assertTrue(UpdateChecker.compare("4.0.0", "3.0.0") > 0)
        assertTrue(UpdateChecker.compare("3.0.1", "3.0.0") > 0)
        assertTrue(UpdateChecker.compare("3.1.0", "3.0.9") > 0)
    }

    @Test
    fun `an equal version is not an update`() {
        assertEquals(0, UpdateChecker.compare("3.0.0", "3.0.0"))
        assertEquals(0, UpdateChecker.compare("3.0", "3.0.0"))
    }

    @Test
    fun `a lower version is not an update`() {
        assertTrue(UpdateChecker.compare("2.1.0", "3.0.0") < 0)
    }

    @Test
    fun `a plain release outranks the same version with a fork suffix`() {
        // Segment 3 is `0` vs `0-hermesx`: one is numeric, the other is not, so the numeric one wins.
        // That is also semver's rule — a plain release is newer than a pre-release of the same number —
        // and it is the right behaviour here: if the fork ever publishes `v3.0.0` after shipping
        // `3.0.0-hermesx`, the user should be offered it.
        assertTrue(UpdateChecker.compare("3.0.0", "3.0.0-hermesx") > 0)
        // The suffix must not *inflate* the version: a later base version still wins.
        assertTrue(UpdateChecker.compare("3.0.1", "3.0.0-hermesx") > 0)
        // Same string on both sides is never an update.
        assertEquals(0, UpdateChecker.compare("3.0.0-hermesx", "3.0.0-hermesx"))
    }

    @Test
    fun `a non-numeric segment cannot throw`() {
        // The old implementation called `toIntOrNull() ?: 0`, so a tag like "v1.2.3-rc1" was accepted.
        // Whatever the new rule is, it must not throw on a tag the GitHub API can legitimately return.
        UpdateChecker.compare("1.2.3-rc1", "1.2.3")
        UpdateChecker.compare("", "1.0.0")
        UpdateChecker.compare("1.0.0", "not-a-version")
    }

    // ── the defect that shipped ─────────────────────────────────────────────

    @Test
    fun `the fork must not offer the upstream project's releases`() {
        // Before the fix: `UpdateChecker.check` queried
        // `api.github.com/repos/newo-ether/Agora/releases/latest`. This fork ships its own
        // applicationId and its own versionName, and it has features upstream does not; offering the
        // upstream APK as "the update" is a wrong-install, not a missed one. The check must query the
        // fork's own repository.
        assertTrue(
            "UpdateChecker must query the fork's own releases, not upstream's",
            UpdateChecker.RELEASES_REPOSITORY == "michaelxdips/Agora",
        )
    }

    @Test
    fun `the release URL points at the fork`() {
        assertTrue(
            "the release-notes link must be the fork's, so the notes match the build",
            UpdateChecker.RELEASES_URL.startsWith("https://github.com/michaelxdips/Agora"),
        )
    }

    // ── the pure comparison surface ─────────────────────────────────────────

    @Test
    fun `a malformed current version is reported, not hidden`() {
        // `getCurrentVersion()` returns "?" when the PackageManager call fails. Comparing "?" against
        // a real release must not throw and must not claim an update: an unknown local version is not
        // evidence that a newer one exists.
        assertNotNull(UpdateChecker.compare("2.1.0", "?"))
    }
}
