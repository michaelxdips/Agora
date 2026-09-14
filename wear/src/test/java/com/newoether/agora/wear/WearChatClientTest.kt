package com.newoether.agora.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * First tests for the watch's own LLM client.
 *
 * Until now `WearChatClient` had **zero** coverage: the wear test tree held `WearCoreContextTest`
 * and `WearOfflineQueueTest` only. That left the two things most likely to be wrong on a watch —
 * how the base URL is turned into an endpoint, and how a provider's JSON is unwrapped — asserted by
 * nothing but "it worked once on an emulator".
 *
 * Pure JVM: a raw [ServerSocket] stands in for the provider, so no network, no API key and no
 * Robolectric. The HTTP server is hand-rolled rather than `com.sun.net.httpserver` because the
 * instrumented sibling of this test proved the `com.sun` one does not exist on Android — using it
 * here would have been a trap for whoever copies this file to the device side.
 */
class WearChatClientTest {

    private lateinit var server: ServerSocket
    private var port = 0
    private val requests = mutableListOf<String>()

    private fun serve(status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
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
                        requests += "BODY:" + String(buf, 0, read)
                    }
                    val payload = body.toByteArray()
                    val extra = headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
                    val header = "HTTP/1.1 $status X\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${payload.size}\r\n" +
                        extra +
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

    private fun config(base: String = "http://127.0.0.1:0/v1") = WearConfig(
        baseUrl = base,
        apiKey = "sk-test-not-a-real-key",
        model = "test-model",
    )

    private fun clientForCurrentPort() =
        WearChatClient(config("http://127.0.0.1:$port/v1"))

    // ---------- base URL → endpoint ----------

    @Test
    fun endpointAppendsChatCompletions() {
        serve(200, """{"choices":[{"message":{"content":"hi"}}]}""")
        clientForCurrentPort().ask("q", "")
        assertEquals("POST /v1/chat/completions HTTP/1.1", requests.first().trim())
    }

    @Test
    fun endpointDoesNotDoubleAppendWhenBaseUrlAlreadyEndsWithThePath() {
        serve(200, """{"choices":[{"message":{"content":"hi"}}]}""")
        WearChatClient(config("http://127.0.0.1:$port/v1/chat/completions")).ask("q", "")
        assertEquals("POST /v1/chat/completions HTTP/1.1", requests.first().trim())
    }

    @Test
    fun endpointToleratesATrailingSlash() {
        serve(200, """{"choices":[{"message":{"content":"hi"}}]}""")
        WearChatClient(config("http://127.0.0.1:$port/v1/")).ask("q", "")
        assertEquals("POST /v1/chat/completions HTTP/1.1", requests.first().trim())
    }

    // ---------- request shape ----------

    @Test
    fun theApiKeyTravelsAsABearerHeaderAndTheModelInTheBody() {
        serve(200, """{"choices":[{"message":{"content":"hi"}}]}""")
        clientForCurrentPort().ask("what is my project", "")

        val body = requests.first { it.startsWith("BODY:") }.removePrefix("BODY:")
        assertTrue("model missing from body: $body", body.contains("\"model\":\"test-model\""))
        assertTrue("stream must be off: $body", body.contains("\"stream\":false"))
        assertTrue("question missing: $body", body.contains("what is my project"))
    }

    @Test
    fun coreContextIsSentAsASystemMessageAndOmittedWhenBlank() {
        serve(200, """{"choices":[{"message":{"content":"hi"}}]}""")
        clientForCurrentPort().ask("q", "USER LIVES IN PEMALANG")
        val withCore = requests.first { it.startsWith("BODY:") }
        assertTrue("system role missing: $withCore", withCore.contains("\"role\":\"system\""))
        assertTrue("core context missing: $withCore", withCore.contains("USER LIVES IN PEMALANG"))

        requests.clear()
        clientForCurrentPort().ask("q", "")
        val withoutCore = requests.first { it.startsWith("BODY:") }
        assertFalse("blank core context must not be sent: $withoutCore", withoutCore.contains("\"role\":\"system\""))
    }

    // ---------- response parsing ----------

    @Test
    fun parsesTheStandardChoicesMessageContentShape() {
        serve(200, """{"choices":[{"message":{"content":"Pemalang."}}]}""")
        val result = clientForCurrentPort().ask("q", "")
        assertEquals("Pemalang.", result.getOrNull())
    }

    @Test
    fun parsesTheLegacyTextShape() {
        serve(200, """{"choices":[{"text":"legacy answer"}]}""")
        assertEquals("legacy answer", clientForCurrentPort().ask("q", "").getOrNull())
    }

    @Test
    fun toleratesExtraUnknownFieldsFromAProvider() {
        serve(
            200,
            """{"id":"x","object":"chat.completion","usage":{"total_tokens":9},""" +
                """"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant",""" +
                """"content":"ok","refusal":null}}]}"""
        )
        assertEquals("ok", clientForCurrentPort().ask("q", "").getOrNull())
    }

    // ---------- failures are typed, not thrown ----------

    @Test
    fun anHttpErrorBecomesAFailureWithTheStatusOnly() {
        serve(401, """{"error":{"message":"bad key sk-test-not-a-real-key"}}""")
        val result = clientForCurrentPort().ask("q", "")

        assertTrue(result.isFailure)
        assertEquals("HTTP 401", result.exceptionOrNull()?.message)
    }

    @Test
    fun anUnreadableBodyBecomesAFailureNotACrash() {
        serve(200, "this is not json at all")
        val result = clientForCurrentPort().ask("q", "")
        assertTrue(result.isFailure)
        assertEquals("unreadable response", result.exceptionOrNull()?.message)
    }

    @Test
    fun anEmptyContentBecomesAFailure() {
        serve(200, """{"choices":[{"message":{"content":"   "}}]}""")
        assertTrue(clientForCurrentPort().ask("q", "").isFailure)
    }

    @Test
    fun anEmptyChoicesArrayBecomesAFailure() {
        serve(200, """{"choices":[]}""")
        assertTrue(clientForCurrentPort().ask("q", "").isFailure)
    }

    @Test
    fun anUnreachableEndpointBecomesAFailureWithNoUrlLeaked() {
        // Nothing listening: connection refused. The message must stay generic — a thrown
        // ConnectException carries the full URL, and this string is shown on the watch screen.
        val result = WearChatClient(config("http://127.0.0.1:1/v1")).ask("q", "")
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertFalse("failure message leaked the URL: $message", message.contains("127.0.0.1"))
        assertFalse("failure message leaked the key: $message", message.contains("sk-test"))
    }

    @Test
    fun anInvalidConfigFailsBeforeAnyRequestIsMade() {
        val result = WearChatClient(
            WearConfig(baseUrl = "", apiKey = "", model = "")
        ).ask("q", "")
        assertTrue(result.isFailure)
        assertEquals("not configured", result.exceptionOrNull()?.message)
    }

    // ---------- config validation ----------

    @Test
    fun configRejectsPlainHttpExceptTheTwoDevEscapeHatches() {
        assertTrue(config("https://api.openai.com/v1").isValid())
        assertTrue(config("http://localhost:11434/v1").isValid())
        assertTrue(config("http://10.0.2.2:11434/v1").isValid())

        assertFalse("plain http to a public host must be rejected", config("http://api.openai.com/v1").isValid())
        assertFalse(config("ftp://api.openai.com/v1").isValid())
        assertFalse(WearConfig(baseUrl = "https://x/v1", apiKey = "", model = "m").isValid())
        assertFalse(WearConfig(baseUrl = "https://x/v1", apiKey = "k", model = "").isValid())
    }

    @Test
    fun configRejectsABlankBaseUrlEvenWithAKeyAndModel() {
        assertFalse(WearConfig(baseUrl = "   ", apiKey = "k", model = "m").isValid())
    }

    @Test
    fun decodeReturnsNullForGarbageInsteadOfThrowing() {
        assertNull(WearCrypto.decode("!!!not base64!!!"))
    }
}
