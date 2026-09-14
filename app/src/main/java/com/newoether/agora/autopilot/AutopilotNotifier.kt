package com.newoether.agora.autopilot

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Activity
import android.content.Context
import android.content.Intent
import com.newoether.agora.MainActivity
import com.newoether.agora.R
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * The one low-priority notification the autopilot may post: "N memories updated".
 *
 * POST_NOTIFICATIONS is requested from the UI (see the autopilot settings row); this object only
 * checks it. A missing grant is not an error — the adaptation already happened, so the notification
 * is simply skipped (N6: never block on a human-setup item).
 *
 * The tap target is the placeholder activity that Phase 4 replaces with the real Adaptation History
 * screen.
 */
object AutopilotNotifier {

    const val CHANNEL_ID = "hermes_autopilot"
    const val EXTRA_ADAPTATION_COUNT = "hermes.adaptation.count"

    /** HERMES INTEGRATION POINT: intent flag that opens Settings → Adaptation History on tap. */
    const val EXTRA_OPEN_ADAPTATION_HISTORY = "hermes.open.adaptation.history"

    /** True when the app may legally post a notification right now. */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun notifyMemoriesUpdated(context: Context, count: Int) {
        if (count <= 0) return
        if (!canNotify(context)) return
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_ADAPTATION_COUNT, count)
            .putExtra(EXTRA_OPEN_ADAPTATION_HISTORY, true)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("$count memories updated")
            .setContentText("Hermes adapted your saved memory from this conversation.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Autopilot",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Memory adaptations performed by Hermes"
                setShowBadge(false)
            }
        )
    }

    private const val NOTIFICATION_ID = 4101
}
