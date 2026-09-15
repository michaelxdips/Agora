package com.newoether.agora.wear

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** A transport that returns a scripted send result and a scripted ack. */
    private class FakeTransport(
        private val send: PairingSend,
        private val ack: String? = null,
    ) : PairingTransport {
        var sentPayload: ByteArray? = null
        var awaitedTimeout: Long? = null

        override suspend fun sendRequest(payload: ByteArray): PairingSend {
            sentPayload = payload
            return send
        }

        override suspend fun awaitAck(timeoutMs: Long): String? {
            awaitedTimeout = timeoutMs
            return ack
        }
    }

    @Test
    fun `no phone is reported as no phone, not as waiting`() = runTest {
        val transport = FakeTransport(PairingSend.NO_PHONE)
        val seen = mutableListOf<PairingStatus>()

        val result = WearPairing.request(transport, onStatus = { seen += it })

        assertEquals(PairingStatus.NoPhone, result)
        assertEquals(listOf(PairingStatus.Sending, PairingStatus.NoPhone), seen)
        // The decisive assertion: a request with no phone must not sit in "waiting" forever.
        assertFalse(seen.contains(PairingStatus.Sent))
        assertEquals(null, transport.awaitedTimeout)
    }

    @Test
    fun `a failed send does not wait for an ack`() = runTest {
        val transport = FakeTransport(PairingSend.FAILED)

        val result = WearPairing.request(transport)

        assertEquals(PairingStatus.SendFailed, result)
        assertEquals(null, transport.awaitedTimeout)
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
    fun `an ack means connected and the wait is bounded`() = runTest {
        val transport = FakeTransport(PairingSend.SENT, ack = "Sent. The watch is standalone now.")

        val result = WearPairing.request(transport, timeoutMs = 5000L)

        assertEquals(PairingStatus.Connected, result)
        assertEquals(5000L, transport.awaitedTimeout)
    }

    @Test
    fun `a blank ack is not an ack`() = runTest {
        val transport = FakeTransport(PairingSend.SENT, ack = "   ")

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
}
