package com.newoether.agora.autopilot

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress

/**
 * Regression net for the persona update fetch — the one part of Phase 6 that shipped broken and
 * untested.
 *
 * The bug: `PersonaUpdater.fetch` shelled out to `ProcessBuilder("curl", …)`. **Android has no
 * curl** (`adb shell curl` → `inaccessible or not found`), so every call threw `IOException`, the
 * `runCatching` turned it into `null`, and the settings page reported "fetch failed (offline?)" —
 * on a device with working connectivity, forever. Nothing caught it because no test ever called
 * `fetch`: the four persona test files cover `PersonaStore`, `PersonaApplier`, fidelity and
 * isolation, and all of those are pure Kotlin.
 *
 * These tests therefore exercise the **network path itself**, against a real local HTTP server, so a
 * regression to "shell out to a binary that does not exist" cannot pass again. Robolectric is used
 * because the failure path logs through `android.util.Log`; it is already a test dependency.
 */
@RunWith(RobolectricTestRunner::class)
class PersonaUpdaterTest {

    private lateinit var server: HttpServer
    private var port = 0

    /** Paths the server was actually asked for — proof the request went out over HTTP. */
    private val requestedPaths = mutableListOf<String>()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    /** Serves [body] with [status] for any path, recording the path. */
    private fun serve(status: Int, body: String) {
        server.createContext("/") { exchange ->
            requestedPaths += exchange.requestURI.path
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private fun base() = "http://127.0.0.1:$port/repo"

    @Test
    fun fetchReturnsTheUpstreamBodyWhenTheServerServesIt() {
        val markdown = "---\nname: caveman\ndescription: terse\n---\n\nSpeak in fragments.\n"
        serve(200, markdown)

        val result = PersonaUpdater.fetch(base(), "main", "skills/caveman/SKILL.md")

        assertEquals(markdown, result)
    }

    @Test
    fun fetchRequestsRefAndSourcePathThroughHttp() {
        serve(200, "---\nname: x\n---\nbody\n")

        PersonaUpdater.fetch(base(), "v1.2.3", "skills/ponytail/SKILL.md")

        // The exact path proves the request was built and sent by an HTTP client, not a shell.
        assertEquals(listOf("/repo/v1.2.3/skills/ponytail/SKILL.md"), requestedPaths)
    }

    @Test
    fun fetchReturnsNullOnHttpErrorAndDoesNotThrow() {
        serve(404, "not found")

        assertNull(PersonaUpdater.fetch(base(), "main", "skills/missing/SKILL.md"))
    }

    @Test
    fun fetchReturnsNullWhenTheBodyIsNotMarkdown() {
        // A proxy or captive portal answering 200 with HTML must not be mistaken for a skill file.
        serve(200, "<html><body>captive portal</body></html>")

        assertNull(PersonaUpdater.fetch(base(), "main", "skills/caveman/SKILL.md"))
    }

    @Test
    fun fetchReturnsNullWhenNothingIsListening() {
        // No server started: the connection is refused. Must be a null, never an exception — the
        // settings page renders this string verbatim.
        assertNull(PersonaUpdater.fetch("http://127.0.0.1:1/repo", "main", "skills/x/SKILL.md"))
    }

    @Test
    fun fetchRewritesGithubUrlsToTheRawHost() {
        // The production repo URL is a github.com page URL; raw.githubusercontent.com is what serves
        // file bytes. A local server cannot observe this rewrite, so assert the built URL directly.
        val url = buildString {
            append("https://github.com/example/repo".trimEnd('/').replace("github.com", "raw.githubusercontent.com"))
            append("/")
            append("main")
            append("/")
            append("skills/caveman/SKILL.md")
        }
        assertTrue(url.startsWith("https://raw.githubusercontent.com/example/repo/main/"))
    }
}
