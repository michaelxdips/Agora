package com.newoether.agora.autopilot.wearsync

import android.content.Context
import com.newoether.agora.AgoraApplication
import com.newoether.agora.di.AppContainer
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

import kotlinx.coroutines.launch

/**
 * Process-scoped memory observer: any active-memory change, in any component, queues a watch push.
 *
 * The defect this closes (F8): the only trigger was `MemorySnapshotPusher`, started from
 * `MainActivity`'s **composition** scope. So memory written while the phone UI was closed — a
 * `ReflectionWorker` run, an autopilot write while the user was in another app — never reached the
 * watch, and the watch kept answering from a snapshot frozen at the last time the phone app happened
 * to be open. The watch cannot tell that its context is stale, which is exactly why the push has to
 * happen where the write happens rather than where the user is looking.
 *
 * Why not just move `MemorySnapshotPusher` here: it and this class do the same thing with different
 * lifetimes, and the UI-scoped one is still useful as the "user is watching, push now" path. This one
 * observes the store's own `activeMemoryRevision` (no new hook inside `MemoryManager`, N8) and hands
 * the actual transfer to [MemorySnapshotPushWorker], whose work survives the process.
 *
 * `ponytail:` the observer still lives in-process; the durability comes from WorkManager's queue, not
 * from this coroutine. A push that is never requested because the process was already dead cannot be
 * missed — memory is written by that same process.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
object MemoryPushStartup {

    private const val TAG = "AutopilotWearSync"

    /** How often to re-check the startup gate while it is `Blocked`. */
    private const val CONTAINER_POLL_MS = 5_000L

    /**
     * Starts the observer on [scope]. Called once per process from `AgoraApplication.onCreate`.
     *
     * @param scope a process-scoped coroutine scope (the application's), never a composition's.
     */
    @OptIn(FlowPreview::class)
    fun start(context: Context, scope: CoroutineScope) {
        val application = context.applicationContext as? AgoraApplication ?: return
        scope.launch {
            // HERMES INTEGRATION POINT: `awaitContainer() ?: return@launch` killed the observer
            // **permanently** on a single failure. `awaitContainer` answers null the moment the
            // startup gate is `Blocked` (`DatabaseStartupGate.awaitReadyResource`), which is a state
            // the *user* resolves from the UI — so the process-scoped observer this class exists to
            // provide was never started, and nothing retried it. The watch then had no push path at
            // all for the life of that process, even after the user cleared the block. It now waits
            // for the gate to become Ready; the wait is a suspend, so it costs no thread and no wakeups.
            val container = awaitContainer(application) ?: return@launch
            DebugLog.d(TAG, "process-scoped memory push observer started")
            container.memoryManager.activeMemoryRevision
                // HERMES INTEGRATION POINT: `drop(1)` discarded the value at subscribe time as "not a
                // change" — true of the flow's own semantics, wrong for the watch's. The baseline was
                // whatever memory looked like when this subscription happened, so any write that
                // landed before it (a reflection pass during startup) was never pushed and the watch
                // kept the older snapshot indefinitely. Collecting the current value *is* the initial
                // sync that was missing; the debounce below collapses it with any early write.
                //
                // `distinctUntilChanged` is gone with it: the source is a `StateFlow`, which already
                // conflates equal values, and the operator is a deprecation *error* on one.
                .debounce(MemoryPushScheduler.DEBOUNCE_MS)
                .collect {
                    // The worker, not a direct push: it keeps the transfer alive past the UI and past
                    // this coroutine, and `ExistingWorkPolicy.REPLACE` collapses a burst of writes
                    // into one push.
                    MemoryPushScheduler.schedule(context)
                }
        }
    }

    /**
     * Waits for the app container, including across a `Blocked` startup gate.
     *
     * Returns null only if the caller's scope is cancelled — there is no deadline, because the state
     * being waited on is resolved by the user (clearing an incompatible database) and a timeout would
     * simply reintroduce the give-up-forever bug this replaces.
     */
    private suspend fun awaitContainer(application: AgoraApplication): AppContainer? {
        application.awaitContainer()?.let { return it }
        DebugLog.w(TAG, "database not ready; memory push observer waiting")
        while (true) {
            kotlinx.coroutines.delay(CONTAINER_POLL_MS)
            application.awaitContainer()?.let { return it }
        }
    }
}
