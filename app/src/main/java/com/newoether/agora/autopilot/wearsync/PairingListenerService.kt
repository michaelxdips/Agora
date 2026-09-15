package com.newoether.agora.autopilot.wearsync

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.newoether.agora.AgoraApplication
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Phone half of pairing.
 *
 * The watch sends `/hermes/pair` when the user taps "Pair with phone". This service answers by running
 * the same [WatchSync.sendConfigToWatch] the settings screen uses, then replies on `/hermes/pair/ack`
 * with a sentence that is true of the outcome. The user is **not** required to press anything on the
 * phone for a watch-initiated request — the whole point of the request is that the watch asked.
 *
 * The config itself travels on the Data Layer (`/hermes/config`), the channel that already works
 * whether or not this service is alive. A Data Layer *message* is best-effort and would be the wrong
 * transport for a credential: a dropped message is a dropped key with no retry.
 *
 * Two things this service deliberately does not do:
 *  * it never reads the request payload as anything but a version tag — the watch asks, it does not
 *    authenticate, and the phone does not accept a credential *from* the watch;
 *  * it never puts the key, the base URL or the ack body into a log. Only exception class names.
 *
 * The ack text comes from [PushReason.wireText], not from `strings.xml`: the watch has no access to the
 * phone's resources, so the sentence has to travel as text. The *screen* resolves the same reason
 * through its resource, so there is one decision and two renderings rather than two copies.
 *
 * The `hermes_phone` capability the watch looks for is declared in `res/values/wear.xml` of both
 * flavors, not added here at runtime: a capability that only exists after the first request cannot be
 * used to find the phone for that first request.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
class PairingListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != REQUEST_PATH) return
        DebugLog.d(TAG, "pairing request from node ${event.sourceNodeId}")
        val application = application as? AgoraApplication ?: return
        scope.launch {
            val ack = runCatching { handle(application) }
                .getOrElse { error ->
                    DebugLog.w(TAG, "pairing failed: ${error.javaClass.simpleName}")
                    UNREACHABLE
                }
            reply(event.sourceNodeId, ack)
        }
    }

    private suspend fun handle(application: AgoraApplication): String {
        val container = application.awaitContainer()
            ?: return PushReason.STARTING_UP.wireText
        val settings = container.settingsRepository
        val registry = container.providerRegistry
        val modelId = settings.selectedModel.value.orEmpty()
        val providerName = if (modelId.isBlank()) "" else registry.providerForModel(modelId)
        val baseUrl = if (providerName.isBlank()) {
            ""
        } else {
            registry.getEffectiveBaseUrl(providerName).orEmpty()
        }
        val outcome = WatchSync.sendConfigToWatch(
            context = application,
            settingsRepo = settings,
            registry = registry,
            baseUrl = baseUrl,
            model = modelId,
        )
        DebugLog.d(TAG, "pairing push ok=${outcome.ok}")
        return outcome.wireText
    }

    private suspend fun reply(nodeId: String, ack: String) {
        runCatching {
            Wearable.getMessageClient(applicationContext)
                .sendMessage(nodeId, ACK_PATH, ack.toByteArray(Charsets.UTF_8))
                .await()
        }.onFailure { error ->
            DebugLog.w(TAG, "ack not delivered: ${error.javaClass.simpleName}")
        }
    }

    companion object {
        const val REQUEST_PATH = "/hermes/pair"
        const val ACK_PATH = "/hermes/pair/ack"

        /**
         * A Data Layer failure that is not one of [PushReason]'s outcomes: the push itself never got a
         * chance to run. Kept as text because it is wire-only — the phone screen never shows it.
         */
        const val UNREACHABLE = "The phone could not reach the watch to send the config."

        private const val TAG = "AutopilotWearPairing"
    }
}
