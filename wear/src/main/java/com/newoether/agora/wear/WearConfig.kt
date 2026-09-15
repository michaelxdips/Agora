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
    fun isValid(): Boolean =
        baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank() &&
            (baseUrl.startsWith("https://") || isLocalDevBaseUrl(baseUrl))

    /**
     * The localhost escape hatch, for a dev-side model server (Ollama, LM Studio, llama.cpp).
     *
     * `127.0.0.1` is included because the first version listed only `localhost` and `10.0.2.2`, which
     * rejected the more common literal — a user pointing BYOK at `http://127.0.0.1:11434` got
     * "Need an https base URL, key and model" with no hint why, while the identical server spelled
     * `localhost` was accepted. Caught by `WearChatClientTest`, which is why that test suite exists.
     * `10.0.2.2` is the emulator's alias for the host machine.
     */
    private fun isLocalDevBaseUrl(url: String): Boolean {
        val hosts = listOf("http://localhost", "http://127.0.0.1", "http://10.0.2.2")
        return hosts.any { url.startsWith(it) }
    }

    companion object {
        const val CURRENT_VERSION = 1

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

    fun write(config: WearConfig) {
        val plain = WearCrypto.json.encodeToString(WearConfig.serializer(), config)
        file.parentFile?.mkdirs()
        file.writeBytes(WearCrypto.encrypt(context, plain))
    }

    fun clear() {
        runCatching { file.delete() }
    }

    companion object {
        const val FILE_NAME = "hermes_wear_config.bin"
    }
}
