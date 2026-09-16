package com.newoether.agora.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * RED test for mission 1: what the model writes must not reach the watch screen as markup.
 *
 * `WearChatClient.parseContent` unwraps the provider envelope and returns `primitive.content`
 * verbatim — there is no step between "text from the provider" and "text on the screen". So a model
 * that answers in Markdown or LaTeX puts `**`, `$`, `\frac` and fence markers in front of the user.
 *
 * Written **before** the fix and red at the time (all four cases failed with the raw markup in the
 * assertion message); it is the regression test now that [WearAnswerText] does the rendering.
 *
 * Transport is a raw [ServerSocket] stand-in for the provider, the same pattern
 * [WearChatClientTest] uses: no network, no key, no Robolectric.
 */
class WearAnswerLeakTest {

    private lateinit var server: ServerSocket
    private var port = 0

    /** Serves exactly one OpenAI-shaped response carrying [answer] as the assistant text. */
    private fun serveAnswer(answer: String) {
        server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        port = server.localPort
        val json = """{"choices":[{"message":{"role":"assistant","content":${quote(answer)}}}]}"""
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket: Socket = try {
                    server.accept()
                } catch (_: Exception) {
                    return@thread
                }
                socket.use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    reader.readLine()
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                        }
                    }
                    if (contentLength > 0) {
                        val buf = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val n = reader.read(buf, read, contentLength - read)
                            if (n < 0) break
                            read += n
                        }
                    }
                    val payload = json.toByteArray()
                    val header = "HTTP/1.1 200 X\r\n" +
                        "Content-Type: application/json\r\n" +
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

    /** Minimal JSON string encoder; the answers under test carry quotes and backslashes. */
    private fun quote(text: String): String {
        val out = StringBuilder("\"")
        for (ch in text) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        return out.append('"').toString()
    }

    private fun ask(answer: String): String {
        serveAnswer(answer)
        val config = WearConfig(baseUrl = "http://127.0.0.1:$port/v1", apiKey = "k", model = "m")
        return WearChatClient(config).ask("q", "").getOrThrow()
    }

    @Test
    fun markdownBoldNeverReachesTheScreen() {
        val shown = ask("**Jawaban:** 14")
        assertFalse("raw bold markers reached the watch: $shown", shown.contains("**"))
        assertEquals("Jawaban: 14", shown)
    }

    @Test
    fun latexFractionBecomesReadable() {
        val shown = ask("""$\frac{1}{2}$ dari 80 = 40""")
        assertFalse("raw LaTeX reached the watch: $shown", shown.contains("\\frac"))
        assertFalse("dollar delimiters reached the watch: $shown", shown.contains("$"))
        assertTrue("the fraction is not readable: $shown", shown.contains("1/2"))
    }

    @Test
    fun headingMarkerAndFenceAreStripped() {
        val shown = ask("```\n# Hasil\n2 + 2 = 4\n```")
        assertFalse("a code fence reached the watch: $shown", shown.contains("```"))
        assertFalse("a heading marker reached the watch: $shown", shown.contains("#"))
    }

    @Test
    fun superscriptLatexBecomesARealSuperscript() {
        val shown = ask("\$x^{2}\$")
        assertFalse("raw LaTeX reached the watch: $shown", shown.contains("^{"))
        assertTrue("x² missing: $shown", shown.contains("x\u00B2"))
    }
}
