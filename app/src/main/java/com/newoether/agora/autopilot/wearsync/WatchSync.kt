package com.newoether.agora.autopilot.wearsync

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Phone side of the watch sync (Phase 7).
 *
 * Two pushes, both one-way:
 *  * **config** — the one-time credential transfer at setup (base URL, API key, model);
 *  * **memory** — the read-only active-memory snapshot, pushed whenever it changes and the watch is
 *    reachable. The phone is the only writer of memory in v1, so there is nothing to merge and the
 *    watch can never corrupt the phone's store.
 *
 * Both are sent through the Data Layer (`DataClient`), which is the platform's own mechanism: it
 * queues while the watch is unreachable and delivers on reconnect, so this class does not implement
 * its own retry loop.
 *
 * Failure is silent to the user and logged: a watch that is not paired, not nearby, or not installed
 * must never make the phone app show an error.
 */
object WatchSync {

    private const val CONFIG_PATH = "/hermes/config"
    private const val MEMORY_PATH = "/hermes/memory"

    /**
     * Pushes the credential payload.
     *
     * @param apiKey the user's key. It travels phone → watch over the Data Layer and is stored
     *   encrypted on the watch; it is never logged, never put in a notification, and never written to
     *   the AdaptationLog.
     */
    suspend fun pushConfig(
        context: Context,
        baseUrl: String,
        apiKey: String,
        model: String,
        version: Int = 1,
    ): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) return@withContext false
        try {
            val request = PutDataMapRequest.create(CONFIG_PATH).apply {
                dataMap.putInt("version", version)
                dataMap.putString("baseUrl", baseUrl)
                dataMap.putString("apiKey", apiKey)
                dataMap.putString("model", model)
                dataMap.putLong("updatedAt", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
            DebugLog.d(TAG, "watch config pushed")
            true
        } catch (error: Exception) {
            DebugLog.w(TAG, "watch config push failed: ${error.javaClass.simpleName}")
            false
        }
    }

    /**
     * Pushes the current active-memory snapshot (read-only on the watch).
     *
     * Persona blocks are stripped before sending: the watch derives its own core context, and persona
     * text would spend the watch's 500-token budget on instructions the watch app does not follow.
     */
    suspend fun pushMemorySnapshot(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val snapshot = MemoryManager(context).getActiveMemory()
                .let { com.newoether.agora.autopilot.PersonaStore.stripAll(it) }
            val request = PutDataMapRequest.create(MEMORY_PATH).apply {
                dataMap.putString("payload", android.util.Base64.encodeToString(
                    snapshot.toByteArray(), android.util.Base64.NO_WRAP,
                ))
                dataMap.putLong("updatedAt", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
            DebugLog.d(TAG, "watch memory snapshot pushed (${snapshot.length} chars)")
            true
        } catch (error: Exception) {
            DebugLog.w(TAG, "watch memory push failed: ${error.javaClass.simpleName}")
            false
        }
    }

    /** True when at least one watch is currently reachable — used to label the settings row. */
    suspend fun connectedWatchCount(context: Context): Int = withContext(Dispatchers.IO) {
        try {
            Wearable.getNodeClient(context).connectedNodes.await().size
        } catch (error: Exception) {
            DebugLog.w(TAG, "watch node query failed: ${error.javaClass.simpleName}")
            0
        }
    }

    private const val TAG = "AutopilotWearSync"
}
