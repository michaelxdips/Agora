package com.newoether.agora.wear

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Session-5 watch fixes, pinned at the layer that can be tested on a JVM.
 *
 * Each test here corresponds to a defect that was verified in the source before it was changed; the
 * comment on each names the defect, because a test whose reason is lost gets "fixed" by deleting it.
 *
 * What these tests can and cannot prove:
 *  - They prove the *mechanism*: a config with no host is refused, a failed write reports failure, a
 *    concurrent pair of writes does not corrupt the file, and the setup screen's late seed cannot
 *    overwrite typing.
 *  - They cannot prove live device behaviour (a real ENOSPC, a real wrist-down). The tests that
 *    would need a device are marked as such where they exist elsewhere; nothing here claims more.
 */
class WearSession5FixTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = temporaryFolder.newFolder("files")
    }

    // ---------- WearConfig.isValid: the URL must actually parse ----------

    @Test
    fun `a base url with no host is not valid`() {
        // Verified defect: `isValid()` checked only `startsWith("https://")`, so `"https://"` — no
        // host at all — passed validation, was stored, and then failed at send time as
        // NOT_CONFIGURED, which is non-retryable: the question was dead-lettered.
        assertFalse(
            WearConfig(baseUrl = "https://", apiKey = "k", model = "m").isValid(),
        )
    }

    @Test
    fun `an unparseable base url is not valid`() {
        assertFalse(
            WearConfig(baseUrl = "https://not a host/v1", apiKey = "k", model = "m").isValid(),
        )
        assertFalse(
            WearConfig(baseUrl = "https://", apiKey = "k", model = "m").isValid(),
        )
        assertFalse(
            WearConfig(baseUrl = "https:// /v1", apiKey = "k", model = "m").isValid(),
        )
    }

    @Test
    fun `the scheme comparison is case-insensitive and the value is trimmed`() {
        // Verified defect: `startsWith("https://")` is case-sensitive, so `HTTPS://…` was rejected;
        // and with no `trim()`, `" https://…"` was rejected although `WearChatClient` trims before
        // use. A phone push that added whitespace bricked an otherwise correct config.
        assertTrue(
            WearConfig(baseUrl = "HTTPS://api.example.com/v1", apiKey = "k", model = "m").isValid(),
        )
        assertTrue(
            WearConfig(baseUrl = "  https://api.example.com/v1  ", apiKey = "k", model = "m")
                .isValid(),
        )
    }

    @Test
    fun `the local development hosts still pass over plain http`() {
        // The relaxation is deliberately narrow: loopback and the emulator's host alias only, so a
        // public http endpoint is still refused (see the existing suite for the negative cases).
        assertTrue(
            WearConfig(baseUrl = "http://127.0.0.1:11434/v1", apiKey = "k", model = "m").isValid(),
        )
        assertTrue(
            WearConfig(baseUrl = "http://10.0.2.2:11434/v1", apiKey = "k", model = "m").isValid(),
        )
        assertTrue(
            WearConfig(baseUrl = "HTTP://localhost:1234/v1", apiKey = "k", model = "m").isValid(),
        )
    }

    // ---------- WearConfigStore.write: the caller must learn whether it landed ----------

    @Test
    fun `a config write reports failure when the destination cannot be written`() {
        // Verified defect: `WearConfigStore.write` returned `Unit`, so `ConfigListenerService` could
        // not tell success from failure — and deleted the Data Layer item (the only other copy of
        // the credential) either way. The boolean is what lets the caller leave the item in place.
        io.mockk.mockkObject(WearCrypto)
        io.mockk.every { WearCrypto.encrypt(any(), any()) } returns byteArrayOf(1, 2, 3)
        try {
            val context = io.mockk.mockk<android.content.Context>(relaxed = true)
            // A *file* where the app expects to create its files directory: every write inside fails.
            val blocked = File(dir, "not-a-dir").apply { writeText("occupied") }
            io.mockk.every { context.filesDir } returns blocked

            val store = WearConfigStore(context)
            val landed = store.write(
                WearConfig(baseUrl = "https://api.example.com/v1", apiKey = "k", model = "m"),
            )

            assertFalse("a write that cannot land must report failure", landed)
        } finally {
            io.mockk.unmockkObject(WearCrypto)
        }
    }

    @Test
    fun `a config write that lands reports success and is readable back`() {
        io.mockk.mockkObject(WearCrypto)
        val stored = byteArrayOf(9, 8, 7)
        io.mockk.every { WearCrypto.encrypt(any(), any()) } returns stored
        io.mockk.every { WearCrypto.decrypt(any(), any()) } returns
            """{"baseUrl":"https://api.example.com/v1","apiKey":"k","model":"m","version":1}"""
        try {
            val context = io.mockk.mockk<android.content.Context>(relaxed = true)
            io.mockk.every { context.filesDir } returns dir

            val store = WearConfigStore(context)
            val config = WearConfig(baseUrl = "https://api.example.com/v1", apiKey = "k", model = "m")
            val landed = store.write(config)

            assertTrue("a write into a writable directory must report success", landed)
            assertEquals(config.baseUrl, store.read()?.baseUrl)
        } finally {
            io.mockk.unmockkObject(WearCrypto)
        }
    }

    // ---------- WearAtomicFile: concurrent writers ----------

    @Test
    fun `concurrent writes to the same target all report success and leave one complete file`() =
        runTest {
            // Verified defect: the temp name was the fixed `"${target.name}.tmp"`, shared by every
            // writer of the same target. One writer renamed the other's temp away, and the loser's
            // `copyTo` fallback threw `FileNotFoundException` → the write reported `false` silently.
            val target = File(dir, "contended.bin")
            val payloads = (1..8).map { n -> ByteArray(4_096) { (n % 251).toByte() } }

            val results = payloads
                .map { payload -> async(Dispatchers.IO) { WearAtomicFile.write(target, payload) } }
                .awaitAll()

            assertTrue("every concurrent write must report success: $results", results.all { it })
            val written = target.readBytes()
            assertTrue(
                "the surviving file must be exactly one of the payloads, not a mix",
                payloads.any { it.contentEquals(written) },
            )
            val leftovers = dir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
            assertTrue("temp files must not survive a concurrent write: $leftovers", leftovers.isEmpty())
        }

    @Test
    fun `a failed write does not leave the temp file behind`() {
        // Verified defect: the temp leaked whenever the write threw after creating it, so the next
        // write had to work around a stale sibling and a watch accumulated dead bytes.
        val target = File(dir, "blocked")
        target.mkdirs()
        File(target, "child").writeText("occupies the name")

        assertFalse(WearAtomicFile.write(target, "cannot land here"))

        val leftovers = dir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertTrue("a failed write must clean up its temp: $leftovers", leftovers.isEmpty())
    }

    // ---------- WearSetupScreen: the seed must not eat typing ----------

    @Test
    fun `the setup screen guards its seed against in-progress typing`() {
        // Verified defect: `seeded` only covered the *already seeded* case, so a keystore read that
        // finished after the user had started typing (`existing` flipping null → value) overwrote all
        // three fields. A source-level assertion is the honest tool here: the guard is a Compose
        // effect, and this module's instrumented tests are the place for the rendered behaviour.
        val source = File(
            "src/main/java/com/newoether/agora/wear/WearSetupScreen.kt",
        ).takeIf { it.isFile }
            ?: File("../wear/src/main/java/com/newoether/agora/wear/WearSetupScreen.kt")
        assertTrue("WearSetupScreen.kt must be readable from the module dir", source.isFile)
        val text = source.readText()

        val guard = Regex(
            "if \\(baseUrl\\.isNotEmpty\\(\\) \\|\\| apiKey\\.isNotEmpty\\(\\) \\|\\| model\\.isNotEmpty\\(\\)\\)",
        )
        assertTrue(
            "the seed must bail out when the user has already typed something",
            guard.containsMatchIn(text),
        )
    }
}
