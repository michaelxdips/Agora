package com.newoether.agora.wear

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The watch's LLM configuration: base URL, API key, model name.
 *
 * Transferred **once** from the phone over the Data Layer at setup, then the watch is standalone —
 * it never needs the phone again to answer a question (that is the whole product claim, and it is
 * proved with the phone in airplane mode).
 *
 * Stored with [WearCrypto], not in plain DataStore: an API key is a bearer credential, and the watch
 * is the device most likely to be lost. The file format is versioned so a future field addition does
 * not silently invalidate a paired watch's config.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
@Serializable
data class WearConfig(
    val version: Int = CURRENT_VERSION,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val updatedAt: Long = 0L,
) {
    fun isValid(): Boolean {
        // HERMES INTEGRATION POINT (Session 4): this used to be
        // `baseUrl.startsWith("https://") || isLocalDevBaseUrl(baseUrl)`, which accepted
        // `"https://"` — no host at all — and any unparseable string with that prefix. Such a config
        // passed validation, was stored, and then failed at send time as `NOT_CONFIGURED`, which is
        // **non-retryable**: the question went to the dead letter. It also rejected a valid
        // `HTTPS://…` (case-sensitive prefix) and, with no trim, rejected `" https://…"` although
        // `WearChatClient` trims before use. The host is now parsed and required, the scheme compared
        // case-insensitively, and the value trimmed — matching what the client actually accepts.
        val url = baseUrl.trim()
        if (apiKey.isBlank() || model.isBlank()) return false
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        if (host.isNullOrBlank()) return false
        return url.startsWith("https://", ignoreCase = true) || isLocalDevBaseUrl(url)
    }

    /**
     * The localhost escape hatch, for a dev-side model server (Ollama, LM Studio, llama.cpp).
     *
     * `127.0.0.1` is included because the first version listed only `localhost` and `10.0.2.2`, which
     * rejected the more common literal — a user pointing BYOK at `http://127.0.0.1:11434` got
     * "Need an https base URL, key and model" with no hint why, while the identical server spelled
     * `localhost` was accepted. Caught by `WearChatClientTest`, which is why that test suite exists.
     * `10.0.2.2` is the emulator's alias for the host machine.
     *
     * The **host is parsed, not prefix-matched**. `url.startsWith("http://localhost")` accepted
     * `http://localhost.evil.com/v1`, which is a plain-HTTP public host: validation said yes and the
     * platform's `network_security_config` said no, so the request failed with a confusing
     * `UnknownServiceException` instead of the honest "need an https base URL" message.
     */
    private fun isLocalDevBaseUrl(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        return url.startsWith("http://", ignoreCase = true) && host in LOCAL_DEV_HOSTS
    }

    companion object {
        const val CURRENT_VERSION = 1

        /** Hosts allowed over plain HTTP — the local dev escape hatch. Parsed as hosts, never prefixes. */
        private val LOCAL_DEV_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

        /** HTTPS only, except an explicit localhost escape hatch for a dev-side model server. */
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}

/**
 * Encrypted-at-rest config store.
 *
 * Deliberately not AndroidX Security (`EncryptedSharedPreferences` is deprecated and its keystore
 * handling is a maintenance liability): this is an AES-GCM ciphertext in the app's private
 * `filesDir`, with the key held in the Android keystore. The ceiling is documented rather than
 * hidden — see the `ponytail:` note in [WearCrypto].
 */
class WearConfigStore(private val context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    fun read(): WearConfig? {
        val raw = runCatching { file.readBytes() }.getOrNull() ?: return null
        val plain = WearCrypto.decrypt(context, raw) ?: return null
        return runCatching { WearCrypto.json.decodeFromString<WearConfig>(plain) }.getOrNull()
    }

    /**
     * Writes the config, returning whether it landed.
     *
     * HERMES INTEGRATION POINT (Session 4): this returned `Unit`, so `ConfigListenerService` could
     * not tell a successful write from a failed one — and it deleted the Data Layer item (the only
     * other copy of the credential) either way. On a watch out of space, the phone's push was
     * consumed and the key existed nowhere. The boolean is what lets the caller leave the item in
     * place so the next push retries.
     */
    fun write(config: WearConfig): Boolean {
        val plain = WearCrypto.json.encodeToString(WearConfig.serializer(), config)
        // Atomic: a config file that is half-written fails to decrypt, and the user is dropped back
        // on the setup screen with their key apparently gone. See WearAtomicFile.
        return WearAtomicFile.write(file, WearCrypto.encrypt(context, plain))
    }

    companion object {
        const val FILE_NAME = "hermes_wear_config.bin"
    }
}
