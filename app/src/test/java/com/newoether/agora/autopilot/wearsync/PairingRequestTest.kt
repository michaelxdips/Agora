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
 */
class PairingRequestTest {

    private fun request(product: String = "Hermes X", protocol: Int = PairingRequest.PROTOCOL) =
        """{"product":"$product","protocol":$protocol}"""

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
}
