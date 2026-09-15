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
) {
    /** True when this phone can serve the asking watch. */
    fun isSupported(): Boolean =
        protocol == PROTOCOL && product == HermesBuildInfo.PRODUCT_NAME

    companion object {
        /** Bumped whenever the config payload's shape changes. Both sides must agree. */
        const val PROTOCOL = 1

        /**
         * Parses a request body. Returns null for anything that is not a readable request — an
         * unparseable payload is refused, never treated as "close enough".
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
                PairingRequest(product = product, protocol = protocol)
            }.getOrNull()
        }
    }
}
