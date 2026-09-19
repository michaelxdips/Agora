package com.newoether.agora.wear

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pairing logic, proved against the seam.
 *
 * The transfer itself cannot be exercised on this machine: two emulators share no Google account, so
 * `getConnectedNodes()` is empty and no message leaves the device. What *can* be proved off-device is
 * every decision the watch makes — and that is where the bug lived. The old button had no decisions
 * at all: it set a flag and printed a fixed sentence, so "no phone" and "sent" were indistinguishable.
 *
 * Each test names the branch it pins down. If someone later replaces the seam with a direct Data Layer
 * call, these tests stop compiling, which is the intended signal.
 */
class WearPairingTest {

    /**
     * A transport that returns a scripted send result and a scripted ack.
     *
     * The ack is a [PairingAck], not a string: an answer has to name the request it belongs to, and a
     * fake that cannot produce an unrelated id cannot test the stale-answer branch at all.
     */
    private class FakeTransport(
        private val send: PairingSend,
        private val ack: PairingAck? = null,
    ) : PairingTransport {
        var sentPayload: ByteArray? = null
        var awaitedTimeout: Long? = null
        var awaitedRequestId: String? = null

        override suspend fun sendRequest(payload: ByteArray): PairingSend {
            sentPayload = payload
            return send
        }

        override suspend fun awaitAck(requestId: String, timeoutMs: Long): PairingAck? {
            awaitedTimeout = timeoutMs
            awaitedRequestId = requestId
            return ack
        }
    }

    private fun ackFor(requestId: String, message: String = "Sent. The watch is standalone now.") =
        PairingAck(requestId = requestId, message = message)

    @Test
    fun `no phone is reported as no phone, not as waiting`() = runTest {
        val transport = FakeTransport(PairingSend.NO_PHONE)
        val seen = mutableListOf<PairingStatus>()

        val result = WearPairing.request(transport, onStatus = { seen += it })

        assertEquals(PairingStatus.NoPhone, result)
        assertEquals(listOf(PairingStatus.Sending, PairingStatus.NoPhone), seen)
        // The decisive assertion: a request with no phone must not sit in "waiting" forever.
        assertFalse(seen.contains(PairingStatus.Sent))
        assertNull(transport.awaitedTimeout)
    }

    @Test
    fun `a failed send does not wait for an ack`() = runTest {
        val transport = FakeTransport(PairingSend.FAILED)

        val result = WearPairing.request(transport)

        assertEquals(PairingStatus.SendFailed, result)
        assertNull(transport.awaitedTimeout)
    }

    @Test
    fun `no ack in time is a timeout with the recovery step in it`() = runTest {
        val transport = FakeTransport(PairingSend.SENT, ack = null)

        val result = WearPairing.request(transport, timeoutMs = 1234L)

        assertEquals(PairingStatus.TimedOut, result)
        assertEquals(1234L, transport.awaitedTimeout)
        assertTrue(result.message.contains("Send to watch"))
    }

    @Test
    fun `an ack for this request means connected and the wait is bounded`() = runTest {
        // The ack must name the request it answers, so the transport is asked for the id it was given
        // and answers with exactly that one.
        val transport = object : PairingTransport {
            var awaitedTimeout: Long? = null
            override suspend fun sendRequest(payload: ByteArray) = PairingSend.SENT
            override suspend fun awaitAck(requestId: String, timeoutMs: Long): PairingAck {
                awaitedTimeout = timeoutMs
                return ackFor(requestId)
            }
        }

        val result = WearPairing.request(transport, timeoutMs = 5000L)

        assertEquals(PairingStatus.Connected, result)
        assertEquals(5000L, transport.awaitedTimeout)
    }

    @Test
    fun `a blank ack is not an ack`() = runTest {
        // A blank message cannot reach the waiting request at all: `PairingRequestSchema.decodeAck`
        // rejects it before it is published, so the transport hands back nothing.
        val transport = FakeTransport(PairingSend.SENT, ack = null)

        assertEquals(PairingStatus.TimedOut, WearPairing.request(transport))
    }

    @Test
    fun `the request carries no credential`() {
        val payload = String(WearPairing.requestPayload(), Charsets.UTF_8)

        // The watch is the least trusted device in the pair. It asks for configuration; it must never
        // prove itself with a secret, because a secret on the watch is a secret that can leak.
        assertFalse(payload.contains("apiKey", ignoreCase = true))
        assertFalse(payload.contains("key", ignoreCase = true))
        assertFalse(payload.contains("token", ignoreCase = true))
        assertTrue(payload.contains(WearBuildInfo.PRODUCT_NAME))
    }

    @Test
    fun `a hostile ack cannot own the screen`() {
        val huge = "x".repeat(10_000)

        val shown = WearPairing.displayableAck(huge)

        assertEquals(WearPairing.MAX_ACK_CHARS, shown.length)
    }

    @Test
    fun `every status carries a sentence the user can act on`() {
        PairingStatus.entries.forEach { status ->
            assertTrue("${status.name} has no message", status.message.isNotBlank())
        }
        assertTrue(PairingStatus.NoPhone.message.contains("phone"))
        assertTrue(PairingStatus.TimedOut.message.contains("phone"))
    }

    /**
     * A cancelled pairing must not leave the setup screen disabled.
     *
     * The screen derives `waitingForPhone` from `Sending`/`Sent` and disables the Pair button (and the
     * credential fields) on it. `WearSignals.pairing` is process-wide and nothing else resets it, so a
     * cancellation used to park it on `Sent` forever: the user could not retry and could not type a
     * key — a dead end until the app was killed.
     */
    @Test
    fun `a cancelled request leaves no in-flight status behind`() = runTest {
        val transport = object : PairingTransport {
            override suspend fun sendRequest(payload: ByteArray) = PairingSend.SENT
            override suspend fun awaitAck(requestId: String, timeoutMs: Long): PairingAck? {
                // What a real await does when the composition dies mid-wait.
                throw kotlinx.coroutines.CancellationException("wrist down")
            }
        }
        val seen = mutableListOf<PairingStatus>()

        val thrown = runCatching { WearPairing.request(transport, onStatus = { seen += it }) }

        assertTrue("cancellation must propagate, not be swallowed", thrown.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        // The decisive assertion: no state that the screen reads as "still waiting" is left behind.
        val stuck = seen.lastOrNull()
        assertTrue("a cancelled request left the status on $stuck", stuck == PairingStatus.Idle)
        assertFalse(
            "Sending/Sent disable the Pair button; neither may survive a cancellation",
            seen.lastOrNull() == PairingStatus.Sending || seen.lastOrNull() == PairingStatus.Sent,
        )
    }

    // ---------- the request id: a stale answer must not certify a new request ----------

    @Test
    fun `the request carries an id and the schema version`() {
        val payload = String(WearPairing.requestPayload("req-42"), Charsets.UTF_8)

        assertTrue("no requestId in $payload", payload.contains("\"requestId\":\"req-42\""))
        assertTrue("no schemaVersion in $payload", payload.contains("\"schemaVersion\":"))
        assertTrue("no protocol in $payload", payload.contains("\"protocol\":"))
        // A per-attempt id: two calls must not produce the same one, or "stale" is undetectable.
        val other = String(WearPairing.requestPayload(), Charsets.UTF_8)
        assertFalse("request ids must not repeat", other == payload)
    }

    @Test
    fun `an answer for a different request is refused, not reported as connected`() = runTest {
        // The exact race: the phone's slow answer to request #1 lands while request #2 is waiting.
        // `filterNotNull().first()` returned it and the user saw a green "configuration received" for a
        // request the phone never served.
        val transport = FakeTransport(
            PairingSend.SENT,
            ack = ackFor("request-from-an-earlier-attempt"),
        )

        val result = WearPairing.request(transport)

        assertEquals(PairingStatus.StaleAck, result)
        assertFalse("a stale answer must never read as Connected", result == PairingStatus.Connected)
    }

    @Test
    fun `an ack with no request id is not this request's answer`() = runTest {
        // What an older phone sends: a bare sentence. It cannot be attributed to any request, so it
        // must not be treated as an answer to this one.
        val transport = FakeTransport(PairingSend.SENT, ack = PairingAck(requestId = "", message = "Sent."))

        assertEquals(PairingStatus.StaleAck, WearPairing.request(transport))
    }

    @Test
    fun `an ack that names the request but did not serve it is not connected`() = runTest {
        // The bug this pins: every refusal the phone sends — no key, no endpoint, push failed, still
        // starting up, protocol mismatch — echoes the request id. `answers(requestId)` was therefore
        // true for all of them, and the watch showed "Phone replied — configuration received." over a
        // config that was never installed. The id says *which* request was answered; `ok` says whether
        // it was **served**, and only the second one means this watch is configured.
        //
        // The transport echoes the id it is asked about, because that is what the real phone does: the
        // refusal is a *served* answer to *this* request in every respect except the outcome.
        val transport = object : PairingTransport {
            override suspend fun sendRequest(payload: ByteArray) = PairingSend.SENT
            override suspend fun awaitAck(requestId: String, timeoutMs: Long) = PairingAck(
                requestId = requestId,
                message = "No API key configured for the selected model. Set it in Providers first.",
                ok = false,
            )
        }

        val result = WearPairing.request(transport)

        assertFalse(
            "a refusal that names the request must never read as Connected",
            result == PairingStatus.Connected,
        )
        assertEquals(PairingStatus.Refused, result)
    }

    @Test
    fun `an ack from a phone that predates the ok field still means connected`() = runTest {
        // Backward compatibility, and the reason `ok` defaults to true: an older phone's ack only ever
        // travelled when the push actually ran, so its arrival still means what it always meant.
        val transport = object : PairingTransport {
            override suspend fun sendRequest(payload: ByteArray) = PairingSend.SENT
            override suspend fun awaitAck(requestId: String, timeoutMs: Long): PairingAck =
                PairingAck(requestId = requestId, message = "Sent. The watch is standalone now.")
        }

        assertEquals(PairingStatus.Connected, WearPairing.request(transport))
    }

    @Test
    fun `a refusal decodes from the wire with its sentence and its ok flag`() = runTest {
        // The sentence travels on the wire and is rendered by the setup screen; `WearPairing.request`
        // never writes to `WearSignals` (the listener service does). So the thing worth pinning is the
        // *decode*: a refusal must arrive with `ok = false` and with the phone's own sentence intact,
        // because the sentence is the only actionable part of a refusal.
        val sentence = "No API key configured for the selected model. Set it in Providers first."
        val body = "{\"requestId\":\"r-1\",\"message\":\"$sentence\",\"ok\":false,\"schemaVersion\":2}"

        val ack = PairingRequestSchema.decodeAck(body)

        assertNotNull("a well-formed refusal must decode", ack)
        assertEquals(sentence, ack?.message)
        assertEquals(false, ack?.ok)
        assertEquals("r-1", ack?.requestId)
        assertFalse("a refusal must not serve the request", ack!!.serves("r-1"))
        assertTrue("…but it still names it", ack.answers("r-1"))
    }

    @Test
    fun `an ack with no ok field decodes as served, for an older phone`() = runTest {
        // The compatibility half of the same contract: a phone that predates the field sends only
        // `requestId` + `message`, and its ack always meant "the push ran".
        val body = "{\"requestId\":\"r-2\",\"message\":\"Sent. The watch is standalone now.\",\"schemaVersion\":2}"

        val ack = PairingRequestSchema.decodeAck(body)

        assertNotNull(ack)
        assertTrue("an absent ok must default to served", ack!!.serves("r-2"))
    }

    @Test
    fun `the request clears the previous answer before it asks`() = runTest {
        WearSignals.pairingAck.value = ackFor("a-previous-request", "old answer")
        val transport = FakeTransport(PairingSend.SENT, ack = null)

        val result = WearPairing.request(transport)

        assertEquals(PairingStatus.TimedOut, result)
        assertNull("the previous answer must be cleared, not reused", WearSignals.pairingAck.value)
    }

    @Test
    fun `the waiting request tells the transport which id it is waiting for`() = runTest {
        val transport = FakeTransport(PairingSend.SENT, ack = null)

        WearPairing.request(transport)

        assertNotNull("the transport must be told which request to wait for", transport.awaitedRequestId)
        assertTrue(transport.awaitedRequestId!!.isNotBlank())
    }

    // ---------- ack wire format ----------

    @Test
    fun `a versioned ack round-trips through the wire format`() {
        val encoded = PairingRequestSchema.encodeRequest("req-1", WearBuildInfo.PRODUCT_NAME)
        val body = String(encoded, Charsets.UTF_8)
        assertTrue(body.contains("\"requestId\":\"req-1\""))
        assertTrue(body.contains("\"schemaVersion\":${PairingRequestSchema.VERSION}"))

        val ack = PairingRequestSchema.decodeAck(
            """{"requestId":"req-1","message":"Sent. The watch is standalone now.","schemaVersion":2}"""
        )
        assertNotNull(ack)
        assertEquals("req-1", ack!!.requestId)
        assertEquals("Sent. The watch is standalone now.", ack.message)
        assertTrue(ack.answers("req-1"))
        assertFalse(ack.answers("req-2"))
    }

    @Test
    fun `a bare-string ack from an older phone parses but answers nothing`() {
        val ack = PairingRequestSchema.decodeAck("Sent. The watch is standalone now.")

        assertNotNull("an unversioned ack must still be displayable", ack)
        assertEquals("Sent. The watch is standalone now.", ack!!.message)
        assertEquals("", ack.requestId)
        assertFalse("an unattributable answer must not satisfy a request", ack.answers("req-1"))
    }

    @Test
    fun `an unparseable or empty ack is dropped rather than shown`() {
        assertNull(PairingRequestSchema.decodeAck(""))
        assertNull(PairingRequestSchema.decodeAck("   "))
        // JSON with no message field is not an answer.
        assertNull(PairingRequestSchema.decodeAck("""{"requestId":"req-1"}"""))
    }

    @Test
    fun `an ack message is truncated before it can own the screen`() {
        val ack = PairingRequestSchema.decodeAck(
            """{"requestId":"req-1","message":"${"x".repeat(5_000)}"}"""
        )

        assertEquals(WearPairing.MAX_ACK_CHARS, ack!!.message.length)
    }
}
