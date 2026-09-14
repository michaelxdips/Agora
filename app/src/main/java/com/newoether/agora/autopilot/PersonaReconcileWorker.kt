package com.newoether.agora.autopilot

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 6 P1 — startup reconciliation of the persona channel.
 *
 * Two invariants are enforced here rather than only at the toggle sites:
 *  * **master autopilot OFF strips personas** (P1). A user who kills the autopilot expects the fork
 *    to stop shaping replies, and the master toggle lives on a different screen than the persona
 *    toggles — so the strip has to happen somewhere that cannot be skipped.
 *  * **a drifted or half-written store self-heals** (P8 adversarial: kill mid-write). The desired
 *    content is recomputed from the store itself, so any state converges.
 *
 * Cheap: it reads two files and writes only when the content actually differs, so the common case is
 * a read and an early return.
 */
class PersonaReconcileWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val autopilot = AutopilotSettings(applicationContext)
            val settings = PersonaSettings(applicationContext)
            val repo = PersonaRepository(applicationContext, settings)
            val applier = PersonaApplier(
                memoryManager = MemoryManager(applicationContext),
                log = AdaptationDatabase.get(applicationContext).adaptationLogDao(),
            )
            val state = applier.reconcile(
                masterEnabled = autopilot.isEnabled(),
                enabled = settings.enabledMap(),
                bodies = repo.bodies(),
            )
            DebugLog.d(TAG, "persona reconcile: clean=${state.storeIsClean} " +
                "active=${state.enabled.filterValues { it }.keys}")
            Result.success()
        } catch (error: Exception) {
            // Never surface a persona failure to the user; a broken persona must not break launch.
            DebugLog.e(TAG, "persona reconcile failed", error)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "AutopilotPersonas"
        private const val UNIQUE_WORK_NAME = "hermes_persona_reconcile"

        /** Enqueued once per process start from `MainActivity`. */
        fun schedule(context: Context) {
            androidx.work.WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    androidx.work.ExistingWorkPolicy.KEEP,
                    androidx.work.OneTimeWorkRequestBuilder<PersonaReconcileWorker>().build(),
                )
        }
    }
}
