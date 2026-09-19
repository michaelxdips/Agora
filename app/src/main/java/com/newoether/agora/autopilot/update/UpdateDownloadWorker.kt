package com.newoether.agora.autopilot.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads the offered release APK and hands it to [UpdateInstaller.installStaged].
 *
 * Runs only from an explicit user tap ("Download & install" in the update dialog) — never from
 * the periodic check. The input is the [com.newoether.agora.util.UpdateInfo.version] the dialog
 * already showed, so the bytes downloaded are the version the user agreed to; the URL is
 * derived by [UpdateInstaller.apkDownloadUrl], never taken from an intent extra.
 *
 * Progress rides on the existing autopilot notification channel. On success the worker posts
 * the handoff note and dismisses it once the install session commits; the system's own
 * confirmation UI takes over from there.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
class UpdateDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // HERMES INTEGRATION POINT (Session 5 audit): the flavor gate used to cover only the
        // *check* half (UpdateCheckWorker / UpdateChannelStartup). The download half had none, and
        // because both flavors share `applicationId` and the signing config, a play build carrying
        // a stored offer from a prior fdroid install could download and install the fdroid APK —
        // exactly what UpdateChannel's own docs say must not happen. The gate is enforced here,
        // where the bytes would otherwise be fetched, not just where the offer is made.
        if (!UpdateChannel.isForkReleaseChannel(applicationContext)) {
            DebugLog.w(TAG, "not a fork release build; refusing the update download")
            return@withContext Result.failure()
        }
        val version = inputData.getString(KEY_VERSION)
        if (version.isNullOrBlank()) return@withContext Result.failure()
        // Re-resolve instead of trusting a caller-supplied URL: the only input is the version
        // string the dialog displayed, and the URL shape is fixed by UpdateInstaller.
        val fakeInfo = com.newoether.agora.util.UpdateInfo(version = version, url = "", body = "")
        val url = UpdateInstaller.apkDownloadUrl(fakeInfo)
        try {
            UpdateInstaller.notifyProgress(
                applicationContext, "Downloading update $version", "Starting…", 0,
            )
            var lastBucket = -1
            val apk = UpdateInstaller.downloadToCache(applicationContext, url) { downloaded, total ->
                if (total > 0) {
                    val bucket = ((downloaded * 100 / total).toInt()).coerceIn(0, 100)
                    if (bucket != lastBucket) {
                        lastBucket = bucket
                        UpdateInstaller.notifyProgress(
                            applicationContext,
                            "Downloading update $version",
                            "$bucket% of ${(total / 1_048_576)} MB",
                            bucket,
                        )
                    }
                }
            } ?: run {
                UpdateInstaller.notifyProgress(
                    applicationContext, "Update download failed",
                    "Check your connection and try again from About.",
                )
                return@withContext Result.failure()
            }
            UpdateInstaller.dismissProgress(applicationContext)
            val refused = UpdateInstaller.installStaged(applicationContext, apk)
            if (refused != null) {
                UpdateInstaller.notifyProgress(
                    applicationContext, "Update ready",
                    "The download finished but the install session failed.",
                )
                return@withContext Result.failure()
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            UpdateInstaller.dismissProgress(applicationContext)
            throw cancelled
        } catch (error: Exception) {
            DebugLog.w(TAG, "update download worker failed: ${error.javaClass.simpleName}")
            UpdateInstaller.dismissProgress(applicationContext)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "HermesUpdate"
        private const val MAX_ATTEMPTS = 2
        const val UNIQUE_WORK_NAME = "hermes_update_download"
        const val KEY_VERSION = "version"

        /** Enqueues a user-approved download of [version]; a second tap replaces, never doubles. */
        fun schedule(context: Context, version: String) {
            val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
                .setInputData(Data.Builder().putString(KEY_VERSION, version).build())
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
