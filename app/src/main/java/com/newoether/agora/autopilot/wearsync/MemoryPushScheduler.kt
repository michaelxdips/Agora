package com.newoether.agora.autopilot.wearsync

import android.content.Context
import com.newoether.agora.util.DebugLog

/**
 * The memory snapshot push, in one place, so it can be triggered by something that outlives the UI.
 *
 * The defect this closes: the only trigger was `MemorySnapshotPusher`, started from
 * `MainActivity`'s composition scope. So memory written while the phone app was **closed** — a
 * reflection pass finishing in the background, a `ReflectionWorker` run, an autopilot write while the
 * user was in another app — never reached the watch. The watch kept answering from a snapshot frozen
 * at the last time the user happened to open the phone app, which is precisely the failure the
 * read-only snapshot design exists to prevent.
 *
 * `MemorySnapshotPushWorker` is scheduled on every write, so the push happens wherever the process is,
 * and the trigger is a **unique one-time work with a delay**: a burst of writes replaces the pending
 * work rather than queueing ten pushes.
 *
 * This is not a new upstream touchpoint: it is a fork-only file under `autopilot/wearsync/`, and the
 * scheduling call is one line inside `MemoryApplier`'s existing write path... except that
 * `MemoryApplier` is fork-only too, so no upstream file is touched at all.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
object MemoryPushScheduler {

    private const val TAG = "AutopilotWearSync"

    /**
     * How long a burst of writes settles before one push goes out.
     *
     * HERMES INTEGRATION POINT: this was a second `DEBOUNCE_MS = 2_000L` living on
     * `MemorySnapshotPusher`, so the two observers of the same flow each owned a debounce window and
     * each could fire its own push. One constant, one owner.
     */
    const val DEBOUNCE_MS = 2_000L

    /**
     * Requests a push soon. Safe to call from any thread and any component; cheap when called often.
     *
     * Failures are logged and swallowed: a watch that cannot be reached must never break a memory
     * write, and the Data Layer queues the item itself when the watch is offline.
     */
    fun schedule(context: Context) {
        runCatching {
            MemorySnapshotPushWorker.schedule(context, DEBOUNCE_MS)
        }.onFailure { error ->
            DebugLog.w(TAG, "memory push scheduling failed: ${error.javaClass.simpleName}")
        }
    }
}
