package com.newoether.agora.autopilot.wearsync

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.newoether.agora.AgoraApplication
import com.newoether.agora.autopilot.wearsync.WatchSync.PushOutcome
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
        val request = PairingRequest.parse(
            runCatching { String(event.data, Charsets.UTF_8) }.getOrDefault("")
        )
        if (request == null || !request.isSupported()) {
            // Refuse before touching the credential. The payload used to be ignored entirely, so a
            // watch running a different build got a config it could not use while this side reported
            // success — a setup that looks finished and does not work.
            DebugLog.w(
                TAG,
                "pairing refused: unsupported request (protocol=${request?.protocol}, " +
                    "schema=${request?.schemaVersion})",
            )
            scope.launch {
                reply(event.sourceNodeId, request?.requestId.orEmpty(), PROTOCOL_MISMATCH, ok = false)
            }
            return
        }
        val application = application as? AgoraApplication ?: return
        scope.launch {
            // HERMES INTEGRATION POINT (Session 5 audit): `runCatching` swallows
            // `CancellationException` too, and this scope is cancelled in `onDestroy` — so a
            // teardown mid-pairing used to surface as a *false* refusal ("no watch reachable" /
            // "no key configured") for work that was simply abandoned, contradicting this module's
            // own documented rule that a cancelled push is not a failed push. Cancellation is
            // rethrown; only real errors become a refusal.
            val ack = try {
                handle(application)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DebugLog.w(TAG, "pairing failed: ${error.javaClass.simpleName}")
                PushOutcome(PushReason.PUSH_FAILED)
            }
            // HERMES INTEGRATION POINT: the outcome travels with the sentence. `reply` used to send
            // the text alone, so the watch could not tell "your key is missing" from "your key was
            // installed" — both named the request, both rendered as a successful pairing.
            reply(event.sourceNodeId, request.requestId, ack.reason.wireText, ok = ack.ok)
        }
    }

    private suspend fun handle(application: AgoraApplication): PushOutcome {
        val container = application.awaitContainer()
            ?: return PushOutcome(PushReason.STARTING_UP)
        val settings = container.settingsRepository
        val registry = container.providerRegistry
        // HERMES INTEGRATION POINT (Session 5 audit): this service can be **cold-started by the
        // watch's own request** — that is its whole point. `selectedModel` starts at the repository
        // default (`gemini-1.5-flash`) and only receives the real DataStore value asynchronously,
        // so reading `.value` in the same slice as construction reads the default: the phone then
        // resolves a Google key and pushes a complete, valid, *wrong* config that the watch installs
        // and acks as success. `awaitInitialLoad` is the repository's own barrier for exactly this
        // (its KDoc names the cold-start case); the key half already awaited via `awaitActiveKey`.
        runCatching { settings.awaitInitialLoad() }
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
        return outcome
    }

    /**
     * Sends the answer, tagged with the request it belongs to.
     *
     * The id is not decoration: the watch keeps one process-wide ack slot, so an answer that does not
     * name its request cannot be told apart from a late answer to the previous one — and the watch
     * would then report a successful setup for a request this phone never served.
     *
     * @param requestId the asking watch's id, echoed verbatim. Empty when the request was unreadable
     *   (the watch then treats the answer as unattributable, which is the truth).
     */
    private suspend fun reply(nodeId: String, requestId: String, ack: String, ok: Boolean) {
        runCatching {
            Wearable.getMessageClient(applicationContext)
                .sendMessage(
                    nodeId,
                    ACK_PATH,
                    PairingRequest.ackBody(requestId, ack, ok = ok).toByteArray(Charsets.UTF_8),
                )
                .await()
        }.onFailure { error ->
            DebugLog.w(TAG, "ack not delivered: ${error.javaClass.simpleName}")
        }
    }

    companion object {
        const val REQUEST_PATH = "/hermes/pair"
        const val ACK_PATH = "/hermes/pair/ack"

        /**
         * The watch asked with a request this build does not speak. Wire-only: the sentence has to
         * travel as text, and the phone's own screen never shows it.
         *
         * HERMES INTEGRATION POINT (Session 5 cleanup): this companion used to carry a second wire
         * sentence, `UNREACHABLE`, that no code path could ever send — every real outcome already
         * travels as a [PushReason]. A constant nothing can produce is dead weight, so it is gone.
         */
        const val PROTOCOL_MISMATCH =
            "This watch and phone app are different versions. Update both, then pair again."

        private const val TAG = "AutopilotWearPairing"
    }
}
