package com.newoether.agora.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService


/**
 * Receives the **one-time** credential push from the phone.
 *
 * After this fires the watch is standalone: nothing here is needed to answer a question.
 *
 * Trust boundary, stated accurately: the Wearable Data Layer enforces that **the package name and the
 * signing certificate must match** on both devices, and the platform's own documentation states that
 * no other app has access to the data regardless of connection type. So the exported listener is not
 * a channel another app on the watch can write into — the earlier comment here claimed the opposite
 * ("a hostile app cannot read the key out of this service") and the audit read it as an unproven
 * assertion, which it was. What *is* a real exposure is persistence: the credential used to stay in
 * the shared Data Layer store forever, so this service now deletes the item as soon as it has stored
 * it, and the value lives only in the encrypted, app-private `filesDir`.
 *
 * Rejects a payload whose version the watch does not understand rather than guessing — a wrong config
 * silently produces wrong answers, which is worse than an obvious "needs phone setup" state.
 *
 * The config is also published to [WearSignals] so the UI moves to the chat screen the moment it
 * arrives. Writing the store alone was not enough: the store was correct while the screen still showed
 * setup, and the only way out was to close and reopen the app.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
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
            // Consume the item: the key now lives only in the encrypted app-private store, not in the
            // Data Layer's replicated store where it would otherwise sit indefinitely.
            deleteConsumed(event.dataItem.uri)
            // Publish, don't just persist: this is what moves the UI off the setup screen.
            WearSignals.config.value = config
            WearSignals.pairing.value = PairingStatus.Connected
            WearLog.w("config installed from phone")
        }
    }

    /**
     * Deletes the credential item after it has been stored locally.
     *
     * `await()`ed, not fired and forgotten. `deleteDataItems` returns a `Task`; the previous version
     * dropped it, so the caller had no way to know whether the item was actually gone — and the
     * failure mode this method exists to prevent is precisely "the key is still in the replicated
     * store". The task runs on a background executor inside Play Services, so awaiting it here (the
     * listener is already off the main thread) is what makes the deletion ordered before the ack the
     * phone is waiting for.
     *
     * A failure is logged, never fatal: the config is already installed, and the worst case is that
     * the item stays until the phone pushes again.
     */
    private fun deleteConsumed(uri: android.net.Uri) {
        runCatching {
            // `Tasks.await` (blocking), not the coroutine `await()`: `onDataChanged` is not a
            // coroutine, and the whole point of awaiting here is that the deletion is ordered before
            // the listener returns. The Data Layer callback already runs off the main thread, so a
            // blocking wait on it is the correct tool — the coroutine extension would need a scope
            // this service does not have.
            com.google.android.gms.tasks.Tasks.await(
                com.google.android.gms.wearable.Wearable.getDataClient(applicationContext)
                    .deleteDataItems(uri)
            )
        }.onFailure { WearLog.w("config item not deleted: ${it.javaClass.simpleName}") }
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
 * The phone is the only writer of memory (v1), so the watch only ever caches what it is handed.
 *
 * An **empty** snapshot is applied, not ignored. The previous rule ("a snapshot that arrives empty is
 * ignored; keeping the last good snapshot is better than replacing it with nothing") is right about a
 * *failed* delivery and wrong about a *successful* one: when the user deletes everything from the
 * phone's active memory, the phone pushes an empty snapshot, and a watch that ignores it keeps
 * answering with facts the user has just erased — memory the user can no longer see, in a place they
 * cannot edit. Undecodable payloads are still ignored (that is a transport failure, not an empty
 * memory), and the difference is exactly the `WearCrypto.decode` result.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
class MemoryListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        val store = WearMemoryCache(applicationContext)
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            if (event.dataItem.uri.path != PATH) return@forEach
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            // Presence, not blankness: the phone sends an empty payload when the user's active memory
            // is empty, and that is a delivered fact rather than a missing one. See WearMemoryRules.
            val present = map.containsKey(KEY_PAYLOAD)
            val payload = map.getString(KEY_PAYLOAD).orEmpty()
            val update = WearMemoryRules.decide(present, WearCrypto.decode(payload))
            if (update !is MemoryUpdate.Apply) {
                // Missing key or undecodable bytes: a transport failure, not an empty memory.
                WearLog.w("memory snapshot rejected (missing or undecodable); keeping the previous one")
                return@forEach
            }
            val snapshot = update.snapshot
            // O3: the phone may already have sent a derived core context. `cacheValue` is idempotent,
            // so both the derived and the raw form end up as the same cacheable text.
            val cached = WearMemoryRules.cacheValue(snapshot.toByteArray())
            val updatedAt = map.getLong(KEY_UPDATED_AT, System.currentTimeMillis())
            store.write(cached, updatedAt)
            WearSignals.memoryUpdatedAt.value = updatedAt
            if (cached.isBlank()) {
                // An empty snapshot is a *delivered* fact: the user's active memory is empty now.
                WearLog.w("memory snapshot cleared by the phone")
            } else {
                WearLog.w("memory snapshot updated (${cached.length} chars)")
            }
        }
    }

    companion object {
        const val PATH = "/hermes/memory"
        const val KEY_PAYLOAD = "payload"
        const val KEY_UPDATED_AT = "updatedAt"
    }
}

/**
 * Receives the phone's answer to a pairing request, and the push result of a config the watch asked
 * for. The phone sends it on [WearPairing.ACK_PATH]; the watch turns it into UI state and nothing
 * else — an ack is a status string, never a credential.
 *
 * The payload is parsed into a [PairingAck] rather than kept as text: the request id inside it is what
 * tells the waiting request whether this answer is even its own. A payload that does not parse at all
 * is dropped with a log line, because an unreadable answer is not an answer.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
class PairingAckListenerService : WearableListenerService() {

    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        if (event.path != WearPairing.ACK_PATH) return
        val raw = runCatching { String(event.data, Charsets.UTF_8) }.getOrDefault("")
        val ack = PairingRequestSchema.decodeAck(raw)
        if (ack == null) {
            WearLog.w("pairing ack unreadable; ignored")
            return
        }
        WearSignals.pairingAck.value = ack
        WearLog.w("pairing ack from phone (request ${ack.requestId.ifBlank { "unversioned" }})")
    }
}

/**
 * The last active-memory snapshot the phone pushed, on disk so it survives a watch restart.
 *
 * Read-only by design: the watch never writes memory back, so there is no merge conflict to resolve
 * and no way for a watch to corrupt the phone's store.
 *
 * Written through [WearAtomicFile]: a truncated snapshot file is a core context built from half the
 * user's memory, and the watch would answer confidently from it.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
class WearMemoryCache(private val context: android.content.Context) {

    private val file = java.io.File(context.filesDir, FILE_NAME)

    fun read(): String = runCatching { file.takeIf { it.isFile }?.readText().orEmpty() }.getOrDefault("")

    fun write(snapshot: String, updatedAt: Long) {
        WearAtomicFile.write(file, snapshot)
        runCatching { file.setLastModified(updatedAt) }
    }

    companion object {
        const val FILE_NAME = "hermes_wear_memory.txt"
    }
}
