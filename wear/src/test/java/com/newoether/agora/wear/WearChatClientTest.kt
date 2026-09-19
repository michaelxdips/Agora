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
                        // Capture every request header: the test named for the bearer token asserts
                        // on it, and before this line was here the header was read and discarded, so
                        // an Authorization regression could not fail that test.
                        requests += "HEADER:" + line
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

    @Test
    fun aBaseUrlWithAQueryStringKeepsItAndStillGetsThePath() {
        // String concatenation produced `…/v1?api-version=2024/chat/completions`, which is not a URL
        // any provider recognises — the user saw a 404 for a base URL copied from the provider's own
        // documentation. The path is appended to the *path*; the query survives.
        serve(200, """{"choices":[{"message":{"content":"hi"}}]}""")
        WearChatClient(config("http://127.0.0.1:$port/v1?api-version=2024")).ask("q", "")
        assertEquals(
            "POST /v1/chat/completions?api-version=2024 HTTP/1.1",
            requests.first().trim(),
        )
    }

    @Test
    fun aMalformedBaseUrlIsAFailureRatherThanAnException() {
        // `Request.Builder.url` throws on a URL it cannot parse. `ask`'s contract is "never throws", so
        // the parse has to happen before the builder is handed the string. `https://…` passes
        // `WearConfig.isValid()` (which only checks the scheme), so this reaches the URL builder.
        val result = WearChatClient(config("https://not a host/v1")).ask("q", "")

        assertTrue(result.isFailure)
        assertEquals("bad base URL", result.exceptionOrNull()?.message)
        assertEquals(
            WearChatException.Kind.NOT_CONFIGURED,
            (result.exceptionOrNull() as WearChatException).kind,
        )
    }

    @Test
    fun aBaseUrlThatFailsConfigValidationIsReportedAsNotConfigured() {
        // The other half of the same rule: a base URL the *config* rejects never reaches the builder,
        // and the message says which check refused it.
        val result = WearChatClient(config("http://[not a host/v1")).ask("q", "")

        assertTrue(result.isFailure)
        assertEquals("not configured", result.exceptionOrNull()?.message)
    }

    // ---------- request shape ----------

    @Test
    fun theApiKeyTravelsAsABearerHeaderAndTheModelInTheBody() {
        serve(200, """{"choices":[{"message":{"content":"hi"}}]}""")
        clientForCurrentPort().ask("what is my project", "")

        // The header half. It was missing: the test server discarded every request header, so this
        // test — named for the bearer token — only ever checked the body, and dropping
        // `Authorization` from the request would have left it green. The provider answers 401 for a
        // missing key, so the bug would have surfaced only on a real watch.
        val authorization = requests
            .firstOrNull { it.startsWith("HEADER:", ignoreCase = true) && it.contains("Authorization:", ignoreCase = true) }
            ?.removePrefix("HEADER:")
        assertTrue(
            "no Authorization header reached the provider; captured: ${requests.filter { it.startsWith("HEADER:") }}",
            authorization != null,
        )
        assertTrue(
            "the key must travel as a Bearer token, not bare: $authorization",
            authorization!!.substringAfter(":").trim().startsWith("Bearer "),
        )
        assertTrue(
            "the configured key must be the token: $authorization",
            authorization.substringAfter("Bearer ").trim().isNotBlank(),
        )

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
    fun parsesTheContentPartArrayShape() {
        // What an OpenAI-compatible gateway returns when the answer is wrapped in parts. The previous
        // version read `content` as a primitive only, so a correct answer arrived and was reported as
        // "unreadable response" — the user saw a failure for a response that was fine.
        serve(
            200,
            """{"choices":[{"message":{"content":[{"type":"text","text":"Jawabannya 14."}]}}]}""",
        )
        assertEquals("Jawabannya 14.", clientForCurrentPort().ask("q", "").getOrNull())
    }

    @Test
    fun aContentPartArrayWithSeveralTextPartsIsJoined() {
        serve(
            200,
            """{"choices":[{"message":{"content":[
                {"type":"text","text":"baris satu"},
                {"type":"text","text":"baris dua"}
            ]}}]}""",
        )
        assertEquals("baris satu\nbaris dua", clientForCurrentPort().ask("q", "").getOrNull())
    }

    @Test
    fun aNonTextPartAloneIsUnreadableRatherThanStringified() {
        // An image part has no text to show. Stringifying the JSON would put a blob of markup on a
        // 384 px screen; the honest answer is "unreadable response".
        serve(
            200,
            """{"choices":[{"message":{"content":[{"type":"image_url","image_url":{"url":"x"}}]}}]}""",
        )
        val result = clientForCurrentPort().ask("q", "")
        assertTrue(result.isFailure)
        assertEquals("unreadable response", result.exceptionOrNull()?.message)
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
    fun a401IsNotRetryableAndA429Is() {
        // The queue's whole give-up rule hangs off this distinction. Before it, every failure looked
        // the same, so a revoked key spent three attempts (and three provider calls) and the question
        // was then deleted as if the network had been down.
        serve(401, """{"error":"bad key"}""")
        val unauthorized = clientForCurrentPort().ask("q", "").exceptionOrNull() as WearChatException
        assertEquals(WearChatException.Kind.HTTP_STATUS, unauthorized.kind)
        assertEquals(401, unauthorized.httpStatus)
        assertFalse("a 401 can never succeed on retry", unauthorized.retryable)

        val missing = WearChatException("HTTP 404", WearChatException.Kind.HTTP_STATUS, 404)
        assertFalse("a 404 endpoint will not appear on retry", missing.retryable)

        val throttled = WearChatException("HTTP 429", WearChatException.Kind.HTTP_STATUS, 429)
        assertTrue(throttled.retryable)
        val serverError = WearChatException("HTTP 503", WearChatException.Kind.HTTP_STATUS, 503)
        assertTrue(serverError.retryable)
    }

    @Test
    fun aRetryAfterHeaderIsCarriedOnTheFailureInMilliseconds() {
        // A 429 without the server's own back-off is a 429 the watch will hit again immediately.
        serve(429, """{"error":"slow down"}""", headers = mapOf("Retry-After" to "7"))
        val error = clientForCurrentPort().ask("q", "").exceptionOrNull() as WearChatException

        assertEquals(429, error.httpStatus)
        assertEquals(7_000L, error.retryAfterMs)
        assertTrue(error.retryable)
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
    fun aNullContentBecomesAFailureNotTheWordNull() {
        // Found on the device: with `content: null` the watch displayed the literal string "null".
        // `JsonNull` IS a `JsonPrimitive` in kotlinx.serialization, so the `as? JsonPrimitive` cast
        // succeeded, `.content` returned the text "null", and `takeIf { it.isNotBlank() }` passed
        // because "null" is not blank. The user asked a question and was answered with the word
        // "null" — a wrong answer, which is worse than an honest failure.
        serve(200, """{"choices":[{"message":{"role":"assistant","content":null}}]}""")
        val result = clientForCurrentPort().ask("q", "")

        assertTrue("a null content must be a failure, got: ${result.getOrNull()}", result.isFailure)
        assertEquals("unreadable response", result.exceptionOrNull()?.message)
    }

    @Test
    fun aMissingContentBecomesAFailure() {
        serve(200, """{"choices":[{"message":{"role":"assistant"}}]}""")
        assertTrue(clientForCurrentPort().ask("q", "").isFailure)
    }

    @Test
    fun anExplicitJsonNullIsNeverTreatedAsText() {
        // The general rule behind the two cases above: no JSON null anywhere in the response may
        // become the string "null".
        serve(200, """{"choices":[{"message":{"content":null},"text":null}]}""")
        val result = clientForCurrentPort().ask("q", "")

        assertTrue(result.isFailure)
        assertFalse("the word null reached the caller", result.getOrNull() == "null")
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
    fun configRejectsAHostThatMerelyStartsWithLocalhost() {
        // Prefix matching accepted this, so validation said "fine" while the platform's
        // network_security_config refused the request — the user got UnknownServiceException instead
        // of the honest "need an https base URL" message.
        assertFalse(
            "http://localhost.evil.com is a public plain-http host",
            config("http://localhost.evil.com/v1").isValid(),
        )
        assertFalse(config("http://127.0.0.1.evil.com/v1").isValid())
        assertFalse(config("http://10.0.2.2.evil.com/v1").isValid())
        // …while the real escape hatches still pass.
        assertTrue(config("http://127.0.0.1:11434/v1").isValid())
        assertTrue(config("http://localhost:1234/v1").isValid())
    }

    @Test
    fun aBareHostGetsTheV1SegmentTheUserOmitted() {
        // The user types what the provider's docs show; a missing /v1 used to 404 with no hint.
        serve(200, """{"choices":[{"message":{"content":"hi"}}]}""")
        WearChatClient(config("http://127.0.0.1:$port")).ask("q", "")
        assertEquals("POST /v1/chat/completions HTTP/1.1", requests.first().trim())
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
