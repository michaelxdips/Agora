package com.newoether.agora.autopilot.wearsync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Pushes the active-memory snapshot to the watch from the **background**.
 *
 * Why a worker rather than only the UI observer: `MemorySnapshotPusher` lives on the composition
 * scope, so it stops the moment the phone UI is closed. Memory, however, is written by
 * `ReflectionWorker` and by the autopilot while the app is not on screen — and the watch's core
 * context was frozen at whatever memory looked like the last time the user opened the phone. The
 * watch then answered confidently from a snapshot the user had already moved past, with nothing on
 * either screen explaining why.
 *
 * Deliberately *not* `REPLACE`-unique-with-no-delay: writes arrive in bursts (one per reflection op),
 * and a push per op would spend the Data Layer's bandwidth and the watch's battery on intermediate
 * states the user never sees. The debounce is the same window the UI observer uses.
 *
 * Failure is a `Result.success()`, not a retry: the Data Layer itself queues an item while the watch
 * is unreachable, so a "failed" push here usually means the watch is simply not nearby — and
 * WorkManager retrying it would duplicate the queueing the platform already does.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
class MemorySnapshotPushWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // HERMES INTEGRATION POINT: no timeout here, and the chain below it (`WatchSync` →
            // `putDataItem().await()`) has none either, so a Data Layer call that never returns left
            // this worker running until WorkManager's 10-minute stop — and `Result.success()` below
            // meant the next write started the whole thing again. Bounded now.
            val pushed = withTimeoutOrNull(PUSH_TIMEOUT_MS) {
                WatchSync.pushMemorySnapshot(applicationContext, MemoryManager(applicationContext))
            }
            if (pushed == null) {
                DebugLog.w(TAG, "background memory snapshot push timed out")
                // HERMES INTEGRATION POINT: this used to be `Result.success()` for *every* outcome,
                // including a read or derive failure. `MemoryManager.getActiveMemory()` throwing meant
                // the watch silently kept a stale snapshot until the next memory write — and if the
                // user made no further writes, forever, with nothing on either screen saying so. The
                // Data Layer already queues an item while the watch is unreachable, so a *transport*
                // failure is still a success; a failure to even produce the payload is not.
                // Bounded: an unbounded retry loop on a permanently broken store is a battery drain,
                // and the next memory write schedules a fresh attempt anyway.
                return@withContext if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }
            DebugLog.d(TAG, "background memory snapshot push: $pushed")
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled   // a cancelled push is not a failed push; see WatchSync
        } catch (error: Exception) {
            DebugLog.w(TAG, "background memory snapshot push failed: ${error.javaClass.simpleName}")
            // Retry, bounded by WorkManager's own backoff policy — not `success`, which discarded the
            // only signal that the watch is now behind. Capped so a permanently failing push cannot
            // spin forever; a new memory write schedules a fresh attempt regardless.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "AutopilotWearSync"

        /** Upper bound on one push attempt, under WorkManager's 10-minute stop. */
        private const val PUSH_TIMEOUT_MS = 60_000L

        /** Retry ceiling. Matches the cap the other fork workers use for the same reason. */
        private const val MAX_ATTEMPTS = 2

        const val UNIQUE_WORK_NAME = "hermes_watch_memory_push"
        const val KEY_REASON = "reason"

        /** Queues one push, [delayMs] from now, replacing any pending one. */
        fun schedule(context: Context, delayMs: Long, reason: String = "memory-write") {
            val request = OneTimeWorkRequestBuilder<MemorySnapshotPushWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_REASON to reason))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
