package com.newoether.agora.wear

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-wide state the Data Layer pushes into and the UI reads.
 *
 * Before this existed, `ConfigListenerService.onDataChanged` wrote the config to the store and told
 * nobody: the config was on disk but the UI still showed the setup screen, so the user had to close
 * and reopen the app. The fix is a flow, not a poll — the listener publishes, the composition
 * collects.
 *
 * Kept as a plain object rather than a DI graph because the watch has exactly three pieces of shared
 * state and no container; a container for three fields would be structure without a reader.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
object WearSignals {

    /** Last config the store holds, pushed by the phone or typed on the watch. Null = not set up. */
    val config = MutableStateFlow<WearConfig?>(null)

    /** Live pairing status, so the setup screen shows what is actually happening. */
    val pairing = MutableStateFlow(PairingStatus.Idle)

    /**
     * The phone's answer to a pairing request, or null when none has arrived this process.
     *
     * Typed since the request-id change: the answer names the request it belongs to, which is what
     * lets [WearPairing] refuse a *stale* answer. As a bare `String?` the flow could only ever say
     * "something arrived", and `filterNotNull().first()` therefore satisfied a brand-new request with
     * the previous request's answer.
     */
    val pairingAck = MutableStateFlow<PairingAck?>(null)

    /** Timestamp of the last memory snapshot the phone pushed (0 = never). */
    val memoryUpdatedAt = MutableStateFlow(0L)
}
