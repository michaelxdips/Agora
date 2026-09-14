package com.newoether.agora.autopilot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Device-side regression net for the persona update fetch — the half the JVM test **cannot** cover.
 *
 * Why this file exists, stated plainly because the reasoning is the whole point:
 *
 * The original `PersonaUpdater.fetch` shelled out to `ProcessBuilder("curl", …)`. Android has no
 * curl. A JVM unit test does not catch that, and here is the measured proof: with the buggy
 * `ProcessBuilder` version deliberately restored, the JVM `PersonaUpdaterTest` still passed **6/6** —
 * because the developer host *does* ship curl, so the subprocess succeeded on the test machine and
 * failed only on the device the app actually runs on. That is how this shipped broken through a
 * phase that had claimed "fetch verified".
 *
 * Implementation note: this server is a raw [ServerSocket], not `com.sun.net.httpserver` — the
 * `com.sun` one is absent on Android and fails the run with
 * `NoClassDefFoundError: com/sun/net/httpserver/HttpExchange` (hit for real before this rewrite).
 */
@RunWith(AndroidJUnit4::class)
class PersonaUpdaterInstrumentedTest {

    private lateinit var server: ServerSocket
    private var port = 0

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    /**
     * Starts a one-shot HTTP/1.1 server that answers every request with [status] and [body], and
     * records each request line so a test can prove what was actually asked for.
     */
    private fun serve(status: Int, body: String, requests: MutableList<String> = mutableListOf()) {
        server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        port = server.localPort
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket: Socket = try {
                    server.accept()
                } catch (_: Exception) {
                    return@thread
                }
                socket.use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val requestLine = reader.readLine().orEmpty()
                    requests += requestLine
                    // Drain headers so the client's write completes before we respond.
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val reason = if (status == 200) "OK" else "Error"
                    val payload = body.toByteArray()
                    val header = "HTTP/1.1 $status $reason\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "Content-Length: ${payload.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    s.getOutputStream().apply {
                        write(header.toByteArray())
                        write(payload)
                        flush()
                    }
                }
            }
        }
    }

    @Test
    fun theDeviceReallyHasNoCurlSoTheOldImplementationCouldNotHaveWorked() {
        // Guards the premise of this whole file. If a future platform image ships curl, this test
        // says so out loud rather than silently losing its meaning.
        val hasCurl = listOf(
            "/system/bin/curl",
            "/system/xbin/curl",
            "/apex/com.android.runtime/bin/curl",
            "/vendor/bin/curl",
        ).any { File(it).exists() }
        assertEquals("device unexpectedly ships curl; re-check why the old impl failed", false, hasCurl)
    }

    @Test
    fun fetchReturnsTheBodyOnDevice() {
        val markdown = "---\nname: caveman\ndescription: terse\n---\n\nSpeak in fragments.\n"
        serve(200, markdown)

        val result = PersonaUpdater.fetch("http://127.0.0.1:$port/repo", "main", "skills/caveman/SKILL.md")

        assertEquals(markdown, result)
    }

    /**
     * The load-bearing assertion.
     *
     * On Android the OLD implementation called `ProcessBuilder("curl", …)`, which threw because the
     * binary does not exist; `runCatching` swallowed it and returned null. A dead port ALSO returns
     * null through OkHttp — so a test asserting only "returns null" would have passed on the buggy
     * code and proved nothing.
     *
     * What distinguishes them is whether the request reached a listening socket at all, so this
     * asserts both that the body came back AND that the server saw the exact path. A subprocess
     * shell-out cannot satisfy either on Android.
     */
    @Test
    fun fetchReachesALoopbackServerAndRequestsTheExactPath() {
        val payload = "---\nname: ponytail\n---\nLaziest sufficient change.\n"
        val seen = mutableListOf<String>()
        serve(200, payload, seen)

        val result = PersonaUpdater.fetch("http://127.0.0.1:$port/repo", "v1.2.3", "skills/ponytail/SKILL.md")

        assertEquals("fetch must reach the loopback server through the app's own HTTP stack", payload, result)
        assertTrue("server never received a request: $seen", seen.isNotEmpty())
        assertEquals(
            "GET /repo/v1.2.3/skills/ponytail/SKILL.md HTTP/1.1",
            seen.first().trim(),
        )
    }

    @Test
    fun fetchReturnsNullOn404OnDevice() {
        serve(404, "nope")
        assertNull(PersonaUpdater.fetch("http://127.0.0.1:$port/repo", "main", "skills/x/SKILL.md"))
    }

    @Test
    fun fetchRejectsAHtmlBodyOnDevice() {
        serve(200, "<html>captive portal</html>")
        assertNull(PersonaUpdater.fetch("http://127.0.0.1:$port/repo", "main", "skills/x/SKILL.md"))
    }

    @Test
    fun runReportsAReadableStringRatherThanThrowing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = PersonaUpdater.run(context)
        assertTrue("report must be non-blank, got '$report'", report.isNotBlank())
    }
}
