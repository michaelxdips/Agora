package com.newoether.agora.wear

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

    /**
     * The phone answered this request and refused to configure the watch.
     *
     * HERMES INTEGRATION POINT: this state exists because every refusal used to be indistinguishable
     * from success. `NO_KEY`, `NO_ENDPOINT`, `PUSH_FAILED`, `STARTING_UP` and the version-mismatch
     * refusal all echo the request id, so the watch's id check passed for all of them and it showed
     * "configuration received" over a config that was never installed. The phone's own sentence is
     * rendered underneath by `WearSetupScreen`, which already shows `pairingAck.message`.
     */
    Refused("The phone could not set this watch up. See its reply below."),

    /**
     * The phone answered, but its answer does not belong to the request that is waiting.
     *
     * Distinct from [Connected] on purpose: the old code treated *any* arriving string as proof that
     * this request had been served, so a slow answer to request #1 turned request #2 green. "A phone
     * said something" and "the phone answered *this* request" are different facts, and only the
     * second one means the watch is configured.
     */
    StaleAck("A late answer arrived for an earlier request. Try again."),
}

/**
 * The phone's answer to one pairing request.
 *
 * [requestId] is what makes the answer *belong* to a request. It travels out in the request payload
 * and comes back in the ack, so an answer that arrives after the user has already retried is
 * recognisable as stale instead of being counted as a success for the new request.
 *
 * [schemaVersion] is the ack's own protocol version, so a phone running a different protocol can be
 * named as such rather than having its sentence rendered as if it were an answer to this request.
 */
data class PairingAck(
    val requestId: String,
    val message: String,
    val schemaVersion: Int = PairingRequestSchema.VERSION,
    /**
     * HERMES INTEGRATION POINT: whether the phone actually **served** the request.
     *
     * Defaults to true so an ack from a phone that predates this field keeps the old meaning (its
     * payload only ever travelled when the push ran). A phone that sends `ok=false` answered the
     * request without configuring anything — `NO_KEY`, `NO_ENDPOINT`, `PUSH_FAILED`, `STARTING_UP`
     * and the protocol-mismatch refusal all name the request they answer, so before this field the
     * watch's `answers(requestId)` was true for a push that installed nothing and it reported
     * "Phone replied — configuration received."
     */
    val ok: Boolean = true,
) {
    /** True when this answer was produced for [requestId]. */
    fun answers(requestId: String): Boolean = this.requestId == requestId

    /** True when this answer both names [requestId] **and** says the phone configured the watch. */
    fun serves(requestId: String): Boolean = answers(requestId) && ok
}

/**
 * The pairing wire schema, in one place, for both sides of the exchange.
 *
 * The watch's copy of the shape: what the request carries out and what the ack must carry back. The
 * phone parses the same keys (`PairingRequest` on its side) — they are two files because they are two
 * apps, but the version number and the field names are one contract.
 */
object PairingRequestSchema {
    /** Bumped whenever the request or the ack changes shape. */
    const val VERSION = 2

    const val KEY_PRODUCT = "product"
    const val KEY_PROTOCOL = "protocol"
    const val KEY_SCHEMA_VERSION = "schemaVersion"
    const val KEY_REQUEST_ID = "requestId"
    const val KEY_MESSAGE = "message"

    /** HERMES INTEGRATION POINT: whether the phone served the request — see [PairingAck.ok]. */
    const val KEY_OK = "ok"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Serialises a request. [requestId] is the caller's own id for this attempt. */
    fun encodeRequest(requestId: String, product: String): ByteArray = buildString {
        append("{")
        append("\"$KEY_PRODUCT\":\"${escape(product)}\",")
        append("\"$KEY_PROTOCOL\":${WearPairing.PROTOCOL},")
        append("\"$KEY_SCHEMA_VERSION\":$VERSION,")
        append("\"$KEY_REQUEST_ID\":\"${escape(requestId)}\"")
        append("}")
    }.toByteArray(Charsets.UTF_8)

    /**
     * Parses an ack.
     *
     * Two shapes are accepted, and the difference is deliberate: a **versioned** ack is the object
     * `{"requestId":…,"message":…}` this build's phone sends, while a **bare string** is what an
     * older phone sends (its whole payload was the sentence). A bare string has no request id, so it
     * cannot be attributed to a request — it is parsed with an empty id, which [PairingAck.answers]
     * then refuses for any real request. The user sees [PairingStatus.StaleAck] and retries, instead
     * of a green tick for a configuration that never arrived.
     */
    fun decodeAck(raw: String): PairingAck? {
        val body = raw.trim()
        if (body.isEmpty()) return null
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull()
        val object_ = element as? JsonObject ?: return PairingAck(
            requestId = "",
            message = WearPairing.displayableAck(body),
            schemaVersion = 0,
        )
        val message = (object_[KEY_MESSAGE] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (message.isEmpty()) return null
        return PairingAck(
            requestId = (object_[KEY_REQUEST_ID] as? JsonPrimitive)?.content.orEmpty(),
            message = WearPairing.displayableAck(message),
            schemaVersion = (object_[KEY_SCHEMA_VERSION] as? JsonPrimitive)?.content?.toIntOrNull()
                ?: VERSION,
            // HERMES INTEGRATION POINT: absent means an older phone, whose ack only ever carried a
            // success (see [PairingAck.ok]); anything other than a literal `true`/`false` is treated
            // as "not served", because a value we cannot read must not certify a configuration.
            ok = (object_[KEY_OK] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true,
        )
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
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

    /**
     * Waits up to [timeoutMs] for an answer **to [requestId]**.
     *
     * The id is a parameter, not something the transport filters on its own: the decision "this answer
     * is not mine" is the watch's, and it has to be visible in a test rather than buried in a Data
     * Layer callback.
     */
    suspend fun awaitAck(requestId: String, timeoutMs: Long): PairingAck?
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
        if (nodes.isEmpty()) {
            // Logged because the honest "no phone" outcome is otherwise invisible on the device: the
            // UI shows it, but a log line is what a future investigation reads first.
            WearLog.w("pairing: no node advertises $PHONE_CAPABILITY")
            return@withContext PairingSend.NO_PHONE
        }

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

    /**
     * Waits for an answer to [requestId], or the next answer that arrives if it is for a different
     * request.
     *
     * The loop is the fix for a real race: `pairingAck` is a single process-wide slot, and a *late*
     * answer to the previous request can land between this request's send and its answer. Consuming
     * that one and continuing to wait is the correct behaviour — the answer to *this* request is
     * still on its way. Returning it instead (which is what `filterNotNull().first()` did) reported
     * success for a request the phone never served.
     */
    override suspend fun awaitAck(requestId: String, timeoutMs: Long): PairingAck? {
        // HERMES INTEGRATION POINT: this was `System.currentTimeMillis() + timeoutMs`, i.e. a
        // wall-clock deadline. An NTP correction, a timezone-less manual clock change, or a DST shift
        // during the 20 s wait moves that deadline arbitrarily — forward and the request times out
        // instantly, backward and the watch waits far past its own timeout. `elapsedRealtime` is
        // monotonic and unaffected by either.
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0L) return null
            val next = withTimeoutOrNull(remaining) { WearSignals.pairingAck.first { it != null } }
                ?: return null
            if (next.answers(requestId)) return next
            // Not ours: clear it and keep waiting. Logged because a stale answer means the phone is
            // slower than the user's retry, which is worth knowing when a report says "timed out".
            WearLog.w("pairing: discarding an ack for ${next.requestId.ifBlank { "an older protocol" }}")
            WearSignals.pairingAck.value = null
        }
    }

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

    /** Protocol version the phone checks. Bumped with [PairingRequestSchema.VERSION]. */
    const val PROTOCOL = 2

    /** How long the watch waits for the phone's answer before it says so. */
    const val ACK_TIMEOUT_MS = 20_000L

    /** Ack text is remote input: bounded before it is shown, so it cannot own the screen. */
    const val MAX_ACK_CHARS = 120

    /** Monotonic-ish, collision-free enough for one watch's own retries. */
    private var requestCounter = 0L

    private fun nextRequestId(): String {
        requestCounter += 1
        return "${WearBuildInfo.PRODUCT_NAME.lowercase()}-${System.currentTimeMillis()}-$requestCounter"
    }

    /**
     * The request payload.
     *
     * Contains **no credential**: the phone reads the key from its own provider settings. The watch
     * is the least trusted device in the pair, so it asks for configuration rather than proving who
     * it is with a secret.
     *
     * It *does* carry the schema version and a per-attempt request id — both are non-secret, and both
     * are what let the phone refuse a watch it cannot serve and let this watch refuse an answer that
     * belongs to an earlier attempt.
     */
    fun requestPayload(requestId: String = nextRequestId()): ByteArray =
        PairingRequestSchema.encodeRequest(requestId, WearBuildInfo.PRODUCT_NAME)

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
        // Clear the previous answer before asking again. Without this the ack flow is
        // `filterNotNull().first()`, so a *stale* ack from the last request satisfied the wait
        // instantly and a second tap reported `Connected` without the phone having answered at all.
        // The request id is the structural half of the same fix: even if a stale ack is already in
        // the slot, it cannot answer a request it does not name.
        WearSignals.pairingAck.value = null
        val requestId = nextRequestId()
        onStatus(PairingStatus.Sending)
        return try {
            val sent = transport.sendRequest(requestPayload(requestId))
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
            val ack = transport.awaitAck(requestId, timeoutMs)
            val final = when {
                ack == null -> PairingStatus.TimedOut
                // HERMES INTEGRATION POINT: `ack.answers(requestId)` alone was the bug. Every refusal
                // the phone sends — no key, no endpoint, push failed, still starting up, version
                // mismatch — echoes the request id, so "this answer names my request" was true for a
                // push that configured nothing and the watch showed "configuration received". The
                // watch now needs the phone to say it was **served**.
                ack.serves(requestId) -> PairingStatus.Connected
                // Named this request, but the phone refused it. The sentence travels; the status does
                // not pretend it worked.
                ack.answers(requestId) -> PairingStatus.Refused
                // An answer arrived that does not name this request. Never `Connected`: the whole
                // point of the id is that a late answer cannot certify a new request.
                else -> PairingStatus.StaleAck
            }
            onStatus(final)
            final
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // A cancelled request produces no return value — the coroutine is unwinding (wrist-down,
            // configuration change, process death) so nobody reads the result. What it must not do is
            // leave the process-wide status on `Sending`/`Sent`: the setup screen computes
            // `waitingForPhone` from exactly those two states and disables the Pair button on it, and
            // the text fields are read-only while waiting. A cancelled pairing therefore used to be a
            // dead end — no retry, no way back to setup — until the process was killed.
            //
            // `Idle` is the honest state: no request is in flight any more. Re-thrown so the caller's
            // cancellation still propagates, matching `WearPairingTransport` and `WatchSync`.
            onStatus(PairingStatus.Idle)
            throw cancelled
        }
    }
}
