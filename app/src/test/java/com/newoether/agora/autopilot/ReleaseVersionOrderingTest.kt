package com.newoether.agora.autopilot

import com.newoether.agora.util.UpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The version arithmetic behind the fork's **first** release, asserted where the guard cannot
 * complain about it.
 *
 * Why this file exists instead of three more cases in `UpdateCheckerTest`: that file is a registered
 * upstream touchpoint with a 100-line budget and it sits at 98/100. A release-ordering assertion is
 * fork-specific — it names the fork's own version string — so it belongs in the fork's own test
 * directory, which `scripts/touchpoint_guard.sh` allows as a class.
 *
 * What it pins, and why each case matters:
 *  * `v3.0.1` must outrank `3.0.0-hermesx`. The About screen compares the release tag against the
 *    installed `versionName`; if that comparison failed, every device on the previous build would be
 *    told "up to date" for the one release that exists.
 *  * the *reverse* must be negative, so a `3.0.1` device is never offered its own version;
 *  * a bare `v` prefix is what GitHub returns and must be stripped, not compared.
 */
class ReleaseVersionOrderingTest {

    @Test
    fun `the first release outranks the unpublished version it replaces`() {
        assertTrue(
            "v3.0.1 must be offered to a 3.0.0-hermesx install",
            UpdateChecker.isNewer("v3.0.1", "3.0.0-hermesx"),
        )
        // The exact tag GitHub serves, with and without its `v` prefix.
        assertTrue(UpdateChecker.isNewer("3.0.1", "3.0.0-hermesx"))
    }

    @Test
    fun `a device already on the release is not offered it`() {
        // The defect this pins: `compare("3.0.1", "3.0.1-hermesx") == 1` because the numeric segment
        // outranks the suffixed one, so a device on the release was offered that same release on
        // every launch. `isNewer` compares the base versions, so the build's own suffix is not newer.
        assertFalse(
            "3.0.1-hermesx must not be told that v3.0.1 is an update",
            UpdateChecker.isNewer("v3.0.1", "3.0.1-hermesx"),
        )
        assertFalse(UpdateChecker.isNewer("3.0.1", "3.0.1"))
        assertFalse(UpdateChecker.isNewer("3.0.0-hermesx", "3.0.1-hermesx"))
        // The raw comparison still orders them — the suffix rule lives in `isNewer`, not in `compare`.
        assertTrue(UpdateChecker.compare("3.0.1", "3.0.1-hermesx") > 0)
    }

    @Test
    fun `the next patch still outranks this release`() {
        assertTrue(UpdateChecker.isNewer("v3.0.2", "3.0.1-hermesx"))
        assertTrue(UpdateChecker.isNewer("v3.1.0", "3.0.1-hermesx"))
        assertTrue(UpdateChecker.isNewer("v4.0.0", "3.0.1"))
    }

    @Test
    fun `the published v3_0_3 release is offered to every earlier install and to none of its own`() {
        // The tag this session actually published. Both directions matter: a device on 3.0.2 must be
        // told about it, and a device already on it must not be told about it forever — the defect
        // that shipped with v3.0.1 and is pinned above for that tag.
        assertTrue("v3.0.3 must be offered to a 3.0.2-hermesx install", UpdateChecker.isNewer("v3.0.3", "3.0.2-hermesx"))
        assertTrue(UpdateChecker.isNewer("v3.0.3", "3.0.0-hermesx"))
        assertFalse("3.0.3-hermesx must not be offered v3.0.3", UpdateChecker.isNewer("v3.0.3", "3.0.3-hermesx"))
        assertFalse(UpdateChecker.isNewer("v3.0.3", "3.0.3"))
    }
}
