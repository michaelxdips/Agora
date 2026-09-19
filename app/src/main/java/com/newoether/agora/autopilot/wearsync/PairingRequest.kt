package com.newoether.agora.autopilot.wearsync

import com.newoether.agora.autopilot.HermesBuildInfo

/**
 * The watch's pairing request, as the phone reads it.
 *
 * Pure Kotlin so the phone's decision is unit-testable on the JVM: the transport cannot be exercised
 * on this machine (two emulators share no Google account), but *what the phone does with the bytes*
 * can, and that is where the version handshake lives.
 *
 * Why a handshake at all: the phone used to ignore the payload completely — `onMessageReceived`
 * checked the path and pushed. A watch and phone app from different versions would then exchange
 * credentials that neither side can use: the phone says "Sent", the watch installs a config its own
 * `WearConfig.CURRENT_VERSION` rejects, and the user sees a successful setup that does not work.
 * Refusing the mismatch, with a sentence that says so, is the honest failure.
 */
data class PairingRequest(
    val product: String,
    val protocol: Int,
    /**
     * The asking watch's id for this attempt. Echoed back in the ack.
     *
     * Without it the ack is an anonymous sentence, and the watch's `pairingAck` flow — a single
     * process-wide slot — cannot tell an answer to *this* request from a late answer to the previous
     * one. `filterNotNull().first()` then reported success for a request the phone never served.
     */
    val requestId: String = "",
    /** The watch's own wire schema version, so a mismatch can be named rather than guessed. */
    val schemaVersion: Int = 0,
) {
    /** True when this phone can serve the asking watch. */
    fun isSupported(): Boolean =
        protocol == PROTOCOL && product == HermesBuildInfo.PRODUCT_NAME

    companion object {
        /** Bumped whenever the config payload's shape changes. Both sides must agree. */
        const val PROTOCOL = 2

        /**
         * Parses a request body. Returns null for anything that is not a readable request — an
         * unparseable payload is refused, never treated as "close enough".
         *
         * [requestId] and [schemaVersion] are optional so a watch built before the id existed still
         * pairs: it gets an empty id, and its own ack handling is the older, weaker check. Refusing it
         * outright would strand every already-installed watch on a phone update.
         */
        fun parse(raw: String): PairingRequest? {
            val body = raw.trim()
            if (body.isEmpty()) return null
            return runCatching {
                val element = kotlinx.serialization.json.Json
                    .parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
                    ?: return null
                val product = (element["product"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content.orEmpty()
                val protocol = (element["protocol"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.toIntOrNull() ?: return null
                PairingRequest(
                    product = product,
                    protocol = protocol,
                    requestId = (element["requestId"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.content.orEmpty(),
                    schemaVersion = (element["schemaVersion"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.content?.toIntOrNull() ?: 0,
                )
            }.getOrNull()
        }

        /**
         * The ack body: the request's id, whether the phone actually configured the watch, and the
         * sentence.
         *
         * JSON rather than a bare string, because the id has to travel with the sentence — a watch
         * that receives the sentence alone cannot attribute it to a request, and an unattributable
         * answer must not be treated as a success.
         *
         * HERMES INTEGRATION POINT: `ok` was added because every refusal used to echo the request id
         * too. `NO_KEY`, `NO_ENDPOINT`, `PUSH_FAILED` and `STARTING_UP` all name the request they
         * answer, so the watch's `ack.answers(requestId)` was true for a push that configured
         * nothing, and the watch reported "Phone replied — configuration received." with no config
         * installed. The id says *which* request was answered; `ok` says whether it was **served**,
         * and only the second one means the watch is configured.
         */
        fun ackBody(
            requestId: String,
            message: String,
            ok: Boolean = true,
            schemaVersion: Int = PROTOCOL,
        ): String =
            "{\"requestId\":\"${escape(requestId)}\",\"message\":\"${escape(message)}\"," +
                "\"ok\":$ok,\"schemaVersion\":$schemaVersion}"

        private fun escape(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    }
}
