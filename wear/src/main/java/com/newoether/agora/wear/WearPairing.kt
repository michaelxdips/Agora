package com.newoether.agora.wear

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Outcome of the *send* half of a pairing request. */
enum class PairingSend {
    /** The request reached at least one node. */
    SENT,

    /** No node to send to: no phone app paired with this watch. */
    NO_PHONE,

    /** A node existed but every send failed (Data Layer refused, transport error). */
    FAILED,
}

/**
 * What the user sees. Every state maps to a sentence that is true of the system right now — the
 * button never says "Waiting for the phone…" while nothing is being sent, which is what the previous
 * version did (it only disabled the text fields and never contacted the phone at all).
 */
enum class PairingStatus(val message: String) {
    Idle("Not paired yet"),
    Sending("Asking the phone…"),
    NoPhone("No phone app found. Install it and open it once, or use a key on the watch."),
    SendFailed("Could not reach the phone. Try again."),
    Sent("Request sent. Waiting for the phone to answer…"),
    TimedOut("The phone did not answer. Open the phone app on Watch setup and press Send to watch."),
    Connected("Phone replied — configuration received."),
}

/**
 * The Data Layer calls a pairing request needs, behind an interface.
 *
 * The seam exists because the transfer itself cannot be proved on this machine: two emulators share
 * no Google account, so `getConnectedNodes()` is empty and no message ever leaves. With the seam the
 * *logic* (no phone → say so, send fails → say so, no ack in time → say so, ack → connected) is
 * proved by unit test against a fake, and only the transport stays unverified. That split is stated
 * in the report instead of being papered over with a "verified" claim.
 */
interface PairingTransport {
    /** Sends the request to every reachable node. */
    suspend fun sendRequest(payload: ByteArray): PairingSend

    /** Waits up to [timeoutMs] for the phone's answer. Null = nothing arrived. */
    suspend fun awaitAck(timeoutMs: Long): String?
}

/**
 * The real Data Layer transport.
 *
 * Uses `CapabilityClient` with a reachable filter rather than raw `connectedNodes`: the phone
 * advertises `hermes_phone`, so the watch sends to the app that can actually answer instead of to
 * whatever else happens to be connected.
 */
class WearPairingTransport(private val context: Context) : PairingTransport {

    override suspend fun sendRequest(payload: ByteArray): PairingSend = withContext(Dispatchers.IO) {
        val nodes = try {
            Wearable.getCapabilityClient(context)
                .getCapability(PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            WearLog.w("pairing: capability query failed: ${error.javaClass.simpleName}")
            return@withContext PairingSend.FAILED
        }
        if (nodes.isEmpty()) return@withContext PairingSend.NO_PHONE

        var sent = 0
        for (node in nodes) {
            try {
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, WearPairing.REQUEST_PATH, payload)
                    .await()
                sent += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Class name only: a Data Layer failure must not be able to put payload bytes
                // (which carry no key, but still) into a log line.
                WearLog.w("pairing: send to node failed: ${error.javaClass.simpleName}")
            }
        }
        if (sent > 0) PairingSend.SENT else PairingSend.FAILED
    }

    override suspend fun awaitAck(timeoutMs: Long): String? =
        withTimeoutOrNull(timeoutMs) { WearSignals.pairingAck.filterNotNull().first() }

    companion object {
        const val PHONE_CAPABILITY = "hermes_phone"
    }
}

/**
 * The pairing request, minus the transport.
 *
 * Deliberately not a class: the whole algorithm is four branches, and a class would need a
 * constructor argument to be testable anyway.
 */
object WearPairing {

    const val REQUEST_PATH = "/hermes/pair"
    const val ACK_PATH = "/hermes/pair/ack"

    /** How long the watch waits for the phone's answer before it says so. */
    const val ACK_TIMEOUT_MS = 20_000L

    /** Ack text is remote input: bounded before it is shown, so it cannot own the screen. */
    const val MAX_ACK_CHARS = 120

    /**
     * The request payload.
     *
     * Contains **no credential**: the phone reads the key from its own provider settings. The watch
     * is the least trusted device in the pair, so it asks for configuration rather than proving who
     * it is with a secret.
     */
    fun requestPayload(): ByteArray =
        """{"product":"${WearBuildInfo.PRODUCT_NAME}","protocol":1}""".toByteArray(Charsets.UTF_8)

    /** Truncates an ack for display. Never trusts the remote side to be brief. */
    fun displayableAck(ack: String): String =
        ack.trim().take(MAX_ACK_CHARS)

    /**
     * Runs one pairing request and reports what actually happened.
     *
     * @param transport the Data Layer, real on device and fake in tests.
     * @param onStatus called for each state change so the UI can show progress; the caller owns the
     *   flow, this function owns the decision.
     */
    suspend fun request(
        transport: PairingTransport,
        timeoutMs: Long = ACK_TIMEOUT_MS,
        onStatus: (PairingStatus) -> Unit = { WearSignals.pairing.value = it },
    ): PairingStatus {
        onStatus(PairingStatus.Sending)
        val sent = transport.sendRequest(requestPayload())
        val afterSend = when (sent) {
            PairingSend.NO_PHONE -> PairingStatus.NoPhone
            PairingSend.FAILED -> PairingStatus.SendFailed
            PairingSend.SENT -> PairingStatus.Sent
        }
        if (afterSend != PairingStatus.Sent) {
            onStatus(afterSend)
            return afterSend
        }
        onStatus(PairingStatus.Sent)
        val ack = transport.awaitAck(timeoutMs)
        val final = if (ack.isNullOrBlank()) PairingStatus.TimedOut else PairingStatus.Connected
        onStatus(final)
        return final
    }
}
