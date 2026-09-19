package com.newoether.agora.autopilot

import com.newoether.agora.api.HttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5 — the phone's cleartext policy, classified rather than "fixed".
 *
 * `AndroidManifest.xml` sets `usesCleartextTraffic="true"` globally, and that line is **upstream's**,
 * not the fork's (`git show 914e7c8d:app/src/main/AndroidManifest.xml` already had it; the fork's diff
 * adds only the `PairingListenerService` block). Upstream also *ships* an `http://` default for a
 * local Ollama server. So the manifest is not a defect to be switched off: turning it off would break
 * the documented self-hosted setup.
 *
 * What makes the global flag safe is the guard below it, and that guard had **no test**. It is the
 * only thing standing between "a user mistyped their endpoint" and "a bearer key was sent in the
 * clear to a public host", so it is the thing that must be pinned:
 *
 *  * a credential over plain HTTP to a public host must **throw**;
 *  * a credential over plain HTTP to loopback (and to the other local forms the app documents) must
 *    **not** throw, or every self-hosted provider would break;
 *  * the *same* public URL without a credential header is allowed — the guard is about credentials,
 *    not about cleartext in general.
 */
class HttpClientCleartextGuardTest {

    private val bearer = mapOf("Authorization" to "Bearer sk-test-not-a-real-key")

    @Test
    fun `a credential over cleartext to a public host is refused`() {
        val refused = runCatching {
            HttpClient.guardCleartextCredentials("http://evil.example/v1/chat/completions", bearer)
        }.exceptionOrNull()

        assertTrue(
            "a bearer key was allowed over plain HTTP to a public host: $refused",
            refused is java.io.IOException,
        )
        assertTrue(
            "the refusal must be explainable to the user: ${refused?.message}",
            refused?.message.orEmpty().contains("https", ignoreCase = true),
        )
    }

    @Test
    fun `the refusal covers every credential header the app actually sends`() {
        listOf(
            "Authorization", "authorization", "X-Api-Key", "x-goog-api-key", "api-key",
        ).forEach { header ->
            val refused = runCatching {
                HttpClient.guardCleartextCredentials(
                    "http://evil.example/v1", mapOf(header to "secret"),
                )
            }.exceptionOrNull()
            assertTrue("$header over cleartext was allowed", refused is java.io.IOException)
        }
    }

    @Test
    fun `cleartext to loopback is allowed because that is the documented self-hosted path`() {
        listOf(
            "http://127.0.0.1:11434/v1",
            "http://localhost:1234/v1",
            "http://10.0.2.2:8080/v1",
        ).forEach { url ->
            val thrown = runCatching { HttpClient.guardCleartextCredentials(url, bearer) }.exceptionOrNull()
            assertTrue("$url must stay usable for a local model server, threw $thrown", thrown == null)
        }
    }

    @Test
    fun `cleartext to a LAN or tailnet host is allowed and to a public host is not`() {
        // The LAN/Tailscale half is deliberate (see isLocalHost): a self-hosted server on the user's
        // own network is reached over http:// on purpose, and the tailnet is an encrypted overlay.
        listOf(
            "http://ollama.lan/v1", "http://nas.local/v1", "http://box.tailnet.ts.net/v1",
            "http://192.168.1.50:11434/v1", "http://100.101.102.103:11434/v1",
        ).forEach { url ->
            val thrown = runCatching { HttpClient.guardCleartextCredentials(url, bearer) }.exceptionOrNull()
            assertTrue("$url is local and must be allowed, threw $thrown", thrown == null)
        }
        listOf(
            "http://api.openai.com/v1", "http://8.8.8.8/v1", "http://localhost.evil.com/v1",
        ).forEach { url ->
            val thrown = runCatching { HttpClient.guardCleartextCredentials(url, bearer) }.exceptionOrNull()
            assertTrue("$url is public and must be refused with a credential", thrown is java.io.IOException)
        }
    }

    @Test
    fun `https is never blocked and cleartext without a credential is not the guard's business`() {
        assertTrue(
            runCatching {
                HttpClient.guardCleartextCredentials("https://api.openai.com/v1", bearer)
            }.exceptionOrNull() == null,
        )
        // No credential header: the global cleartext flag is what governs this, not this guard. If the
        // guard started refusing it, every public http:// endpoint the user configured would break for
        // a reason the message does not explain.
        assertTrue(
            runCatching {
                HttpClient.guardCleartextCredentials("http://example.com/v1", mapOf("Accept" to "*/*"))
            }.exceptionOrNull() == null,
        )
    }

    @Test
    fun `a malformed url does not crash the guard`() {
        // `java.net.URI` throws on these; the guard catches and treats the host as unknown, which
        // lands on the fail-closed side (a credential with an unparseable host is refused).
        listOf("http://", "http://[::1", "http:// space/v1").forEach { url ->
            val thrown = runCatching { HttpClient.guardCleartextCredentials(url, bearer) }.exceptionOrNull()
            assertTrue("$url must fail closed, threw $thrown", thrown is java.io.IOException)
        }
        assertFalse(
            "sanity: an empty host is not 'local'",
            HttpClient.isLocalHost(""),
        )
    }
}
