package com.newoether.agora.autopilot.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.newoether.agora.autopilot.AutopilotNotifier
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.UpdateInfo

/**
 * The "Download & install" action for an offered update.
 *
 * Why a [BroadcastReceiver] and not an activity: the tap means "start the download", not "open
 * a screen". The receiver enqueues [UpdateDownloadWorker] with the version the notification
 * showed and dismisses the offer note. No new activity, no new permission, no manifest line —
 * an explicit intent to this package's own receiver needs none.
 *
 * The version travels in the intent, but it is **not trusted**: the worker re-derives the URL
 * from that version string via [UpdateInstaller.apkDownloadUrl], and the dialog already showed
 * the same version, so a forged extra can at most re-download the offered release.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
class UpdateDownloadAction : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DOWNLOAD_UPDATE) return
        val version = intent.getStringExtra(EXTRA_VERSION)
        if (version.isNullOrBlank()) {
            DebugLog.w(TAG, "download action without a version; ignoring")
            return
        }
        UpdateDownloadWorker.schedule(context.applicationContext, version)
        runCatching {
            NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_OFFER_ID)
        }
    }

    companion object {
        private const val TAG = "HermesUpdate"
        const val ACTION_DOWNLOAD_UPDATE = "com.hermes.app.action.DOWNLOAD_UPDATE"
        const val EXTRA_VERSION = "version"
        private const val NOTIFICATION_OFFER_ID = 4103
        private const val REQUEST_DOWNLOAD = 4203

        /** Posts "Update <version> available" with a Download & install action. */
        fun notifyOffer(context: Context, info: UpdateInfo) {
            if (!AutopilotNotifier.canNotify(context)) return
            // The channel is created here, not assumed: it was previously only created by the
            // memory notification, so on a device that had never received one this notification
            // was dropped by the system and the whole update path was silently dead (Session 5).
            AutopilotNotifier.ensureChannelForUpdates(context)
            val appContext = context.applicationContext
            val download = Intent(appContext, UpdateDownloadAction::class.java)
                .setAction(ACTION_DOWNLOAD_UPDATE)
                .putExtra(EXTRA_VERSION, info.version)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val downloadPending = PendingIntent.getBroadcast(
                appContext, REQUEST_DOWNLOAD, download, flags,
            )
            // A tap on the body used to dismiss the notification and do nothing, while the text
            // said "Tap to download" — the body now triggers the same download as the action.
            val notification = NotificationCompat.Builder(appContext, AutopilotNotifier.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Update ${info.version} available")
                .setContentText("Tap to download and install Hermes X ${info.version}.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(downloadPending)
                .addAction(
                    android.R.drawable.stat_sys_download,
                    "Download & install",
                    downloadPending,
                )
                .build()
            runCatching {
                NotificationManagerCompat.from(appContext).notify(NOTIFICATION_OFFER_ID, notification)
            }.onFailure { error ->
                DebugLog.w(TAG, "update offer notify failed: ${error.javaClass.simpleName}")
            }
        }
    }
}
