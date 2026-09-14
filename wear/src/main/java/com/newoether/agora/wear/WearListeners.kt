package com.newoether.agora.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService


/**
 * Receives the **one-time** credential push from the phone.
 *
 * After this fires the watch is standalone: nothing here is needed to answer a question. The listener
 * is `exported` because the Data Layer is an inter-process channel; the payload is the phone's own
 * config, and the store is encrypted, so a hostile app cannot read the key out of this service.
 *
 * Rejects a payload whose version the watch does not understand rather than guessing — a wrong config
 * silently produces wrong answers, which is worse than an obvious "needs phone setup" state.
 */
class ConfigListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        val store = WearConfigStore(applicationContext)
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            if (event.dataItem.uri.path != PATH) return@forEach
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            val config = WearConfig(
                version = map.getInt(KEY_VERSION, WearConfig.CURRENT_VERSION),
                baseUrl = map.getString(KEY_BASE_URL).orEmpty(),
                apiKey = map.getString(KEY_API_KEY).orEmpty(),
                model = map.getString(KEY_MODEL).orEmpty(),
                updatedAt = System.currentTimeMillis(),
            )
            if (config.version != WearConfig.CURRENT_VERSION) {
                WearLog.w("config version ${config.version} not understood; ignoring")
                return@forEach
            }
            if (!config.isValid()) {
                WearLog.w("config rejected: incomplete or non-HTTPS base URL")
                return@forEach
            }
            store.write(config)
            WearLog.w("config installed from phone")
        }
    }

    companion object {
        const val PATH = "/hermes/config"
        const val KEY_VERSION = "version"
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_API_KEY = "apiKey"
        const val KEY_MODEL = "model"
    }
}

/**
 * Receives the **read-only** active-memory snapshot.
 *
 * The phone is the only writer of memory (v1), so the watch only ever caches what it is handed. A
 * snapshot that fails to decrypt or arrives empty is ignored: keeping the last good snapshot is
 * better than replacing it with nothing, because the derived core context is what makes the watch's
 * answers about *this* user.
 */
class MemoryListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        val store = WearMemoryCache(applicationContext)
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            if (event.dataItem.uri.path != PATH) return@forEach
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            val payload = map.getString(KEY_PAYLOAD).orEmpty()
            if (payload.isBlank()) return@forEach
            val snapshot = WearCrypto.decode(payload)?.let { String(it, Charsets.UTF_8) }
            if (snapshot.isNullOrBlank()) {
                WearLog.w("memory snapshot rejected (undecodable or empty); keeping the previous one")
                return@forEach
            }
            store.write(snapshot, map.getLong(KEY_UPDATED_AT, System.currentTimeMillis()))
            WearLog.w("memory snapshot updated (${snapshot.length} chars)")
        }
    }

    companion object {
        const val PATH = "/hermes/memory"
        const val KEY_PAYLOAD = "payload"
        const val KEY_UPDATED_AT = "updatedAt"
    }
}

/**
 * The last active-memory snapshot the phone pushed, on disk so it survives a watch restart.
 *
 * Read-only by design: the watch never writes memory back, so there is no merge conflict to resolve
 * and no way for a watch to corrupt the phone's store.
 */
class WearMemoryCache(private val context: android.content.Context) {

    private val file = java.io.File(context.filesDir, FILE_NAME)

    fun read(): String = runCatching { file.takeIf { it.isFile }?.readText().orEmpty() }.getOrDefault("")

    fun updatedAt(): Long = runCatching { file.lastModified() }.getOrDefault(0L)

    fun write(snapshot: String, updatedAt: Long) {
        file.parentFile?.mkdirs()
        file.writeText(snapshot)
        runCatching { file.setLastModified(updatedAt) }
    }

    fun clear() = runCatching { file.delete() }

    companion object {
        const val FILE_NAME = "hermes_wear_memory.txt"
    }
}
