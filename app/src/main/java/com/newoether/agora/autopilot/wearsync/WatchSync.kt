package com.newoether.agora.autopilot.wearsync

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.ProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
 * must never make the phone app show an error. The one place a result *is* surfaced is the Watch setup
 * screen, via [lastPushOutcome] — a user who asked for a transfer deserves to know whether it went.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
object WatchSync {

    private const val CONFIG_PATH = "/hermes/config"
    private const val MEMORY_PATH = "/hermes/memory"

    /** Capability the watch looks for when it wants to pair; advertised by PairingListenerService. */
    const val PHONE_CAPABILITY = "hermes_phone"

    /**
     * The last transfer result, for the setup screen. Null until the first attempt this process.
     *
     * HERMES INTEGRATION POINT (Session 5 cleanup): the setup page consumes this with
     * `getAndUpdate { null }` rather than reading `.value`, so a sentence is shown once. The comment
     * on the field names that contract because a plain read would silently reintroduce the
     * stale-status bug it fixes.
     */
    val lastPushOutcome = MutableStateFlow<PushOutcome?>(null)

    /**
     * What a transfer attempt did.
     *
     * [reason] is an enum so the screen can resolve a translatable resource and the wire ack can carry
     * text — see [PushReason] for why those have to differ.
     */
    data class PushOutcome(val reason: PushReason) {
        /**
         * HERMES INTEGRATION POINT (Session 5 cleanup): derived from [PushReason.ok] instead of a
         * second constructor parameter. The two could disagree — `PushOutcome(ok = true, reason =
         * PushReason.NO_KEY)` compiled fine and would have acked a failure as a success — and the
         * parameter was written at six call sites that all repeated what the enum already said.
         * One source of truth.
         */
        val ok: Boolean get() = reason.ok

        /** The sentence for the watch: the phone's resources are not reachable from the watch. */
        val wireText: String get() = reason.wireText
    }

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
        } catch (cancelled: CancellationException) {
            // A cancelled push is not a failed push. Swallowing it (CancellationException IS an
            // Exception) makes a cancelled coroutine look like a transport error, and the caller
            // then reports "no watch reachable" for work that was simply abandoned.
            throw cancelled
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
     *
     * An **empty** snapshot is pushed, not skipped. When the user deletes everything from the phone's
     * active memory the correct message is "there is nothing now" — skipping the push leaves the watch
     * answering from facts the user has already erased. The watch side distinguishes this from a
     * missing/undecodable payload by the *presence* of the key, not by its length.
     */
    suspend fun pushMemorySnapshot(context: Context): Boolean =
        pushMemorySnapshot(context, MemoryManager(context))

    /** As above, with the memory store supplied by the caller (the worker resolves its own). */
    suspend fun pushMemorySnapshot(context: Context, memoryManager: MemoryManager): Boolean =
        withContext(Dispatchers.IO) {
        try {
            val snapshot = memoryManager.getActiveMemory()
            // O3: the *derived* core context travels, not the raw snapshot. The phone base64'd the
            // whole memory file (Data Layer items are ~100 KB, +33% for base64) and the watch then
            // truncated it to 500 tokens — so most of that payload existed only to be thrown away on
            // a Bluetooth link. Deriving here costs nothing and the watch keeps its own truncation as
            // the safety net for a snapshot from an older phone.
            val payload = CoreContextDerivation.payloadFor(snapshot)
            CoreContextDerivation.logSaving(snapshot.length, payload)
            val request = PutDataMapRequest.create(MEMORY_PATH).apply {
                dataMap.putString("payload", android.util.Base64.encodeToString(
                    payload.toByteArray(), android.util.Base64.NO_WRAP,
                ))
                dataMap.putLong("updatedAt", System.currentTimeMillis())
                // HERMES INTEGRATION POINT (Session 5 cleanup): `sourceChars` used to travel here
                // (the raw snapshot length, for a watch debug line). The watch never read it —
                // `grep -rn sourceChars wear/src` → 0 hits — so it was a Data Layer key whose only
                // consumer was a unit test asserting it is *written*. It is no longer written; the
                // watch's debug screen reports the cached snapshot's own length.
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
            DebugLog.d(TAG, "watch memory snapshot pushed (${payload.length} chars)")
            true
        } catch (cancelled: CancellationException) {
            throw cancelled   // see pushConfig: cancellation is not a transport failure
        } catch (error: Exception) {
            DebugLog.w(TAG, "watch memory push failed: ${error.javaClass.simpleName}")
            false
        }
    }

    /** True when at least one watch is currently reachable — used to label the settings row. */
    suspend fun connectedWatchCount(context: Context): Int = withContext(Dispatchers.IO) {
        try {
            Wearable.getNodeClient(context).connectedNodes.await().size
        } catch (cancelled: CancellationException) {
            throw cancelled   // see pushConfig: cancellation is not a transport failure
        } catch (error: Exception) {
            DebugLog.w(TAG, "watch node query failed: ${error.javaClass.simpleName}")
            0
        }
    }

    /**
     * One full transfer: resolve the key the user already configured, push config, push memory, and
     * return what happened.
     *
     * Shared by the settings screen's button and by [PairingListenerService] — the watch's request must
     * run the *same* code, or the two paths could disagree about what "sent" means.
     *
     * @param settingsRepo key source; the user is never asked to re-type a key the phone already has.
     */
    suspend fun sendConfigToWatch(
        context: Context,
        settingsRepo: SettingsRepository,
        registry: ProviderRegistry,
        baseUrl: String,
        model: String,
    ): PushOutcome {
        val modelId = model.ifBlank { settingsRepo.selectedModel.value.orEmpty() }
        val providerName = if (modelId.isBlank()) "" else registry.providerForModel(modelId)
        val key = withContext(Dispatchers.IO) {
            runCatching {
                settingsRepo.awaitActiveKey(providerName)?.takeIf { it.isNotBlank() }
                    ?: settingsRepo.resolveActiveKey(providerName)
            }.getOrNull().orEmpty()
        }
        val outcome = when {
            baseUrl.isBlank() || modelId.isBlank() ->
                PushOutcome(PushReason.NO_ENDPOINT)
            key.isBlank() -> PushOutcome(PushReason.NO_KEY)
            else -> {
                val pushed = withContext(Dispatchers.IO) {
                    pushConfig(context, baseUrl, key, modelId)
                }
                if (!pushed) {
                    PushOutcome(PushReason.PUSH_FAILED)
                } else {
                    val memory = withContext(Dispatchers.IO) { pushMemorySnapshot(context) }
                    PushOutcome(if (memory) PushReason.PUSHED else PushReason.CONFIG_ONLY)
                }
            }
        }
        lastPushOutcome.value = outcome
        return outcome
    }

    private const val TAG = "AutopilotWearSync"
}
