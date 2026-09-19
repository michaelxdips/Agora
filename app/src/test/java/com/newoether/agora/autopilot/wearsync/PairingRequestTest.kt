package com.newoether.agora.autopilot.wearsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing handshake, as the phone reads it.
 *
 * The transport cannot be proved on this machine (two emulators share no Google account, so nothing
 * crosses the Data Layer), but the *decision* can: this is what stands between "the watch asked" and
 * "the phone pushed a credential". The first version ignored the payload entirely and pushed anyway,
 * so a version-mismatched watch got a config it could not use while the phone reported success.
 *
 * The request id is part of that handshake, not decoration: it is the only thing that lets the watch
 * tell an answer to *this* request from a late answer to the previous one, and it has to survive the
 * round trip through the phone unchanged.
 */
class PairingRequestTest {

    private fun request(
        product: String = "Hermes X",
        protocol: Int = PairingRequest.PROTOCOL,
        requestId: String? = "req-1",
        schemaVersion: Int? = PairingRequest.PROTOCOL,
    ): String = buildString {
        append("{\"product\":\"$product\",\"protocol\":$protocol")
        requestId?.let { append(",\"requestId\":\"$it\"") }
        schemaVersion?.let { append(",\"schemaVersion\":$it") }
        append("}")
    }

    @Test
    fun `the current watch request is supported`() {
        val parsed = PairingRequest.parse(request())

        assertEquals("Hermes X", parsed?.product)
        assertEquals(PairingRequest.PROTOCOL, parsed?.protocol)
        assertTrue(parsed!!.isSupported())
    }

    @Test
    fun `a different protocol is refused`() {
        val parsed = PairingRequest.parse(request(protocol = PairingRequest.PROTOCOL + 1))

        assertEquals(PairingRequest.PROTOCOL + 1, parsed?.protocol)
        assertFalse("a newer protocol must not be served by an older phone", parsed!!.isSupported())
    }

    @Test
    fun `a different product is refused`() {
        val parsed = PairingRequest.parse(request(product = "Agora"))

        assertFalse("another product must not be handed this app's credentials", parsed!!.isSupported())
    }

    @Test
    fun `an unparseable payload is refused rather than guessed at`() {
        assertNull(PairingRequest.parse(""))
        assertNull(PairingRequest.parse("not json"))
        assertNull(PairingRequest.parse("{}"))
        assertNull(PairingRequest.parse("""{"product":"Hermes X"}"""))
        assertNull(PairingRequest.parse("""{"product":"Hermes X","protocol":"one"}"""))
    }

    // ---------- the request id ----------

    @Test
    fun `the watch's request id and schema version are read`() {
        val parsed = PairingRequest.parse(request(requestId = "req-7", schemaVersion = 2))

        assertEquals("req-7", parsed?.requestId)
        assertEquals(2, parsed?.schemaVersion)
    }

    @Test
    fun `an older watch with no id still pairs`() {
        // Refusing it outright would strand every already-installed watch on a phone update. It gets an
        // empty id, which its own (older) ack handling does not check.
        val parsed = PairingRequest.parse(request(requestId = null, schemaVersion = null))

        assertTrue("a pre-id watch must still be served", parsed!!.isSupported())
        assertEquals("", parsed.requestId)
        assertEquals(0, parsed.schemaVersion)
    }

    // ---------- the ack body ----------

    @Test
    fun `the ack echoes the request id back`() {
        val body = PairingRequest.ackBody("req-7", "Sent. The watch is standalone now.")

        assertTrue("the ack must name its request: $body", body.contains("\"requestId\":\"req-7\""))
        assertTrue(body.contains("\"message\":\"Sent. The watch is standalone now.\""))
        assertTrue(body.contains("\"schemaVersion\":"))
    }

    @Test
    fun `an ack body survives quotes and newlines in the sentence`() {
        // The sentences in `PushReason` are prose; a quote or a newline in one of them must not turn the
        // ack into unparseable JSON, because the watch drops an ack it cannot parse and the user then
        // waits out the full timeout for an answer that had already arrived.
        val body = PairingRequest.ackBody("req-1", "He said \"ok\"\nand left.")

        assertTrue("quotes must be escaped: $body", body.contains("\\\"ok\\\""))
        assertFalse("a raw newline would break the JSON: $body", body.contains("\n"))
    }

    @Test
    fun `an empty request id produces an ack the watch will refuse`() {
        // The honest shape for "the watch did not send an id": the ack still travels, the watch sees an
        // answer that names no request, and it says so rather than reporting a success.
        val body = PairingRequest.ackBody("", "Sent.")

        assertTrue(body.contains("\"requestId\":\"\""))
    }
}
