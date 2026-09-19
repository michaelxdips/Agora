package com.newoether.agora.autopilot.wearsync

import android.content.Context
import com.newoether.agora.AgoraApplication
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Pushes the memory snapshot to the watch **whenever active memory changes**, not only at setup.
 *
 * `WatchSync`'s own KDoc has always claimed the snapshot is "pushed whenever it changes"; the only
 * caller was `sendConfigToWatch`, which runs once, when the user presses Send to watch. So the watch's
 * core context was frozen at whatever memory looked like on setup day — a user who taught the phone
 * three new facts got answers on the watch that ignored all three, with nothing on either screen
 * explaining why. That is the exact failure the watch's "read-only snapshot" design is meant to avoid.
 *
 * Cheap and self-limiting:
 *  * it observes `MemoryManager.activeMemoryRevision`, which the store already increments on every
 *    write — no new hook inside `MemoryManager` (N8: no upstream edit);
 *  * it drops the first emission, because that is the current value at subscribe time, not a change;
 *  * it debounces, because a reflection pass writes one file per op and the watch needs the result,
 *    not each intermediate step;
 *  * a push failure is logged and forgotten — the Data Layer queues while the watch is unreachable,
 *    so a watch that is not nearby simply gets it later.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
object MemorySnapshotPusher {

    private const val TAG = "AutopilotWearSync"

    /** How long a burst of writes is allowed to settle before one push goes out. */
    const val DEBOUNCE_MS = 2_000L

    /**
     * Starts observing active memory. Returns the job so the caller's scope owns its lifetime.
     *
     * HERMES INTEGRATION POINT: this used to push **directly** (`WatchSync.pushMemorySnapshot`) on the
     * composition scope, while `MemoryPushStartup` scheduled a worker for the *same* `activeMemoryRevision`
     * change. With the phone UI open, one memory write therefore travelled to the watch twice — the same
     * bytes over Bluetooth twice, and two Data Layer items for one fact. It now only *requests* the push
     * through the one scheduler, which collapses the burst into a single worker run; the direct path is
     * gone, so there is exactly one owner of "a push happened".
     *
     * @param scope the composition scope, which cancels the *observer* with the UI. The push itself is
     *   WorkManager's, so it survives that cancellation — see `MemoryPushStartup` for why that matters.
     */
    @OptIn(FlowPreview::class)
    fun start(context: Context, scope: CoroutineScope) = scope.launch {
        val application = context.applicationContext as? AgoraApplication ?: return@launch
        val container = application.awaitContainer() ?: return@launch
        container.memoryManager.activeMemoryRevision
            .drop(1)                       // the value at subscribe time is not a change
            // No `distinctUntilChanged()`: the source is a `StateFlow`, which already conflates equal
            // values, so the operator was redundant here. Removed to match `MemoryPushStartup`.
            .debounce(DEBOUNCE_MS)
            .collect {
                // Scheduling, not pushing: `MemoryPushScheduler` owns the debounce and the worker.
                MemoryPushScheduler.schedule(context)
                DebugLog.d(TAG, "memory snapshot push requested (ui observer)")
            }
    }
}
