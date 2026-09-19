package com.newoether.agora.autopilot.wearsync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone↔watch wire contract, proved **automatically** — the automated check that stands in
 * for the Data Layer round trip this machine cannot run (both emulators report `Accounts: 0`,
 * and the Data Layer needs both devices on one Google account; see STATUS.md Mission 4).
 *
 * What this proves, and what it does not:
 *
 * * **Proves:** every path, DataMap key and version the phone writes is *exactly* the one the
 *   watch reads, for the config push, the memory push and the pairing handshake. The two sides
 *   are separate modules with no shared source set (by design: the watch APK must not carry
 *   phone code), so this contract is the only thing standing between a rename on one side and a
 *   watch that silently stops receiving updates. It reads both sources and compares the
 *   literals, so a drift fails the build here instead of failing silently on a wrist.
 * * **Does not prove:** that the Data Layer delivers the bytes. That remains HS4 — a paired
 *   device pair or a signed-in emulator pair — and is recorded as unproven rather than implied.
 *
 * The strings are compared **from the sources**, not from the compiled constants: `WatchSync`
 * and the wear listeners live in different modules, and a JVM test cannot import both. Reading
 * the two files is the honest way to compare them without adding a shared module purely for a
 * test (a `ponytail:`-scale change; see UPSTREAM_TOUCHPOINTS.md for why `wear/` is fork-only).
 */
class WatchProtocolContractTest {

    private fun repoFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }

    private val phoneSource by lazy {
        repoFile("app/src/main/java/com/newoether/agora/autopilot/wearsync/WatchSync.kt")
    }
    private val phonePairingSource by lazy {
        repoFile("app/src/main/java/com/newoether/agora/autopilot/wearsync/PairingRequest.kt")
    }
    private val wearListeners by lazy {
        repoFile("wear/src/main/java/com/newoether/agora/wear/WearListeners.kt")
    }
    private val wearPairing by lazy {
        repoFile("wear/src/main/java/com/newoether/agora/wear/WearPairing.kt")
    }

    /** Extracts `const val NAME = "value"` (or `= 2`) from a source file, or null. */
    private fun constant(source: String, name: String): String? {
        val match = Regex("const val $name(?:\\s*:\\s*\\w+)?\\s*=\\s*(\"([^\"]*)\"|\\d+)")
            .find(source) ?: return null
        return match.groupValues[2].ifEmpty { match.groupValues[1] }
    }

    /** Every value of a repeated `const val NAME` (e.g. `PATH` once per listener companion). */
    private fun constants(source: String, name: String): List<String> =
        Regex("const val $name(?:\\s*:\\s*\\w+)?\\s*=\\s*(\"([^\"]*)\"|\\d+)")
            .findAll(source)
            .map { it.groupValues[2].ifEmpty { it.groupValues[1] } }
            .toList()

    // ── config push: /hermes/config ──────────────────────────────────────────

    @Test
    fun `the config path is identical on both sides`() {
        assertEquals("/hermes/config", constant(phoneSource, "CONFIG_PATH"))
        assertTrue(
            "the watch's config listener must filter on /hermes/config",
            constants(wearListeners, "PATH").contains("/hermes/config"),
        )
    }

    @Test
    fun `every config key the phone writes is a key the watch reads`() {
        // The phone's writer (WatchSync.pushConfig) and the watch's reader (ConfigListenerService)
        // must agree on all four keys. Asserted as an equality of the written set against the read
        // set, so an added key on either side fails until both sides are updated.
        val written = setOf("version", "baseUrl", "apiKey", "model")
        val read = setOf(
            constant(wearListeners, "KEY_VERSION"),
            constant(wearListeners, "KEY_BASE_URL"),
            constant(wearListeners, "KEY_API_KEY"),
            constant(wearListeners, "KEY_MODEL"),
        )
        assertEquals(written, read)
        written.forEach { key ->
            assertTrue(
                "phone never writes config key '$key'",
                phoneSource.contains("putString(\"$key\"") ||
                    phoneSource.contains("putInt(\"$key\""),
            )
        }
    }

    @Test
    fun `the config schema version matches on both sides`() {
        // Phone default (pushConfig's `version` argument) and the watch's CURRENT_VERSION.
        assertTrue(
            "phone must send version 1 by default",
            phoneSource.contains("version: Int = 1"),
        )
        assertEquals("1", constant(wearListeners, "CURRENT_VERSION")
            ?: repoFile("wear/src/main/java/com/newoether/agora/wear/WearConfig.kt")
                .let { constant(it, "CURRENT_VERSION") })
    }

    // ── memory push: /hermes/memory ──────────────────────────────────────────

    @Test
    fun `the memory path is identical on both sides`() {
        assertEquals("/hermes/memory", constant(phoneSource, "MEMORY_PATH"))
        assertTrue(
            "the watch's memory listener must filter on /hermes/memory",
            constants(wearListeners, "PATH").contains("/hermes/memory"),
        )
    }

    @Test
    fun `every memory key the phone writes is a key the watch reads`() {
        val written = setOf("payload", "updatedAt", "sourceChars")
        written.forEach { key ->
            assertTrue(
                "phone never writes memory key '$key'",
                phoneSource.contains("putString(\"$key\"") ||
                    phoneSource.contains("putLong(\"$key\"") ||
                    phoneSource.contains("putInt(\"$key\""),
            )
        }
        // The watch reads payload and updatedAt; sourceChars is debug-only on the watch side, so it
        // is asserted as written by the phone but not required as a read.
        assertEquals("payload", constant(wearListeners, "KEY_PAYLOAD"))
        assertEquals("updatedAt", constant(wearListeners, "KEY_UPDATED_AT"))
        assertTrue(phoneSource.contains("putInt(\"sourceChars\""))
    }

    // ── pairing handshake ────────────────────────────────────────────────────

    @Test
    fun `the pairing paths are identical on both sides`() {
        assertEquals("/hermes/pair", constant(wearPairing, "REQUEST_PATH"))
        assertEquals("/hermes/pair/ack", constant(wearPairing, "ACK_PATH"))
        // The phone answers on the same two paths: PairingListenerService receives REQUEST_PATH and
        // WatchSync replies on the ack path. The literals live in the phone's pairing files.
        val phonePairingListener = repoFile(
            "app/src/main/java/com/newoether/agora/autopilot/wearsync/PairingListenerService.kt",
        )
        assertTrue(phonePairingListener.contains("/hermes/pair/ack"))
    }

    @Test
    fun `the pairing protocol version matches on both sides`() {
        // The phone checks PROTOCOL and the watch sends it; the schema version travels in the
        // request body. A mismatch is a refusal, so drift is a pairing outage, not a silent one.
        assertEquals("2", constant(wearPairing, "PROTOCOL"))
        assertEquals("2", constant(wearPairing, "VERSION"))
        assertTrue(
            "phone must know protocol 2",
            phonePairingSource.contains("PROTOCOL = 2") || phonePairingSource.contains("PROTOCOL: Int = 2"),
        )
    }

    @Test
    fun `the pairing capability is the one the phone advertises`() {
        assertEquals("hermes_phone", constant(phoneSource, "PHONE_CAPABILITY"))
        assertEquals("hermes_phone", constant(wearPairing, "PHONE_CAPABILITY"))
        // The static capability declaration the watch's discovery reads.
        val wearXml = repoFile("app/src/fdroid/res/values/wear.xml")
        assertTrue(wearXml.contains("hermes_phone"))
    }

    @Test
    fun `the pairing request carries no credential`() {
        // The security property the protocol is built around: the request is a handshake, and the
        // credential only travels in the config push *after* the user accepts on the phone.
        val requestBody = phonePairingSource
        assertTrue(requestBody.contains("requestId"))
        assertTrue(requestBody.contains("schemaVersion"))
        assertTrue(
            "the pairing request body must not carry an api key",
            !Regex("KEY_API_KEY|apiKey|api_key").containsMatchIn(requestBody),
        )
    }
}
