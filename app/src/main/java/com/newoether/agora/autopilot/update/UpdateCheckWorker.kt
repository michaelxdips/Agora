package com.newoether.agora.autopilot.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.newoether.agora.AgoraApplication
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.UpdateChecker
import java.util.concurrent.TimeUnit

/**
 * The periodic half of the fork's update channel: once a day, on any network, ask the fork's
 * own releases for something newer and surface it through the existing update dialog.
 *
 * Why a worker rather than only [com.newoether.agora.viewmodel.StartupMaintenanceCoordinator]:
 * the startup check runs only when the conversation list becomes visible and only every 24h
 * from the last check. A user who leaves the app open for days — or who dismisses the dialog
 * once — hears nothing until the next cold start. This worker is the backstop: same check,
 * same dialog state, same user toggle, no new UI.
 *
 * The worker never downloads by itself. A ~50 MB APK on metered data without an explicit tap
 * is a bill, not a feature — the dialog offers, the user taps "Download & install", and only
 * then does [UpdateDownloadWorker] run. `ponytail:` prefetch on unmetered Wi-Fi when the
 * dialog was already shown once for that version; ceiling is a per-version "offered" flag.
 *
 * Respects the same `autoUpdateCheck` toggle as the startup path: off means neither runs.
 * Play builds must update through Play, so the worker exits early there — see [UpdateChannel].
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!UpdateChannel.isForkReleaseChannel(applicationContext)) {
            DebugLog.d(TAG, "not a fork release build; skipping periodic update check")
            return Result.success()
        }
        val app = applicationContext as? AgoraApplication ?: return Result.failure()
        val container = app.awaitContainer() ?: return Result.retry()
        return try {
            if (!container.settingsRepository.getAutoUpdateCheck()) return Result.success()
            val current = readVersion(applicationContext) ?: return Result.success()
            val info = UpdateChecker.check(current)
            container.settingsRepository.saveLastUpdateCheckTime(System.currentTimeMillis())
            if (info != null) {
                // Persist before offering: the offer itself is a rendezvous and is dropped when no
                // UI is collecting (the app is closed — the worker's whole reason to exist). The
                // store is what makes the offer survive until the next time the UI is up.
                UpdateCheckStore(applicationContext).saveOfferedVersion(info.version)
                UpdateCheckBus.offer(info)
                DebugLog.d(TAG, "update available: ${info.version}")
            }
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DebugLog.w(TAG, "periodic update check failed: ${error.javaClass.simpleName}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "HermesUpdate"
        const val UNIQUE_WORK_NAME = "hermes_update_check_periodic"

        /** One check a day; WorkManager's minimum interval is 15 minutes, this is far above it. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
