package com.newoether.agora.autopilot.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.newoether.agora.util.DebugLog

/**
 * The missing half of the [PackageInstaller] handshake.
 *
 * A normal (non-privileged) app on API 26+ does **not** get its update installed by simply
 * committing the session. The platform delivers `STATUS_PENDING_USER_ACTION` to the
 * `PendingIntent` given to `commit()`, with the system's own confirmation activity inside
 * `Intent.EXTRA_INTENT` — and it is the **app's job to launch it**. Without this receiver the
 * flow stalled silently after a ~50 MB download: the bytes were staged, the confirmation never
 * appeared, and nothing in the app ever read the status.
 *
 * What this receiver does, by status:
 *  * `STATUS_PENDING_USER_ACTION` → launch `EXTRA_INTENT` (the system's install-confirmation
 *    screen). This is the case that was broken.
 *  * `STATUS_SUCCESS` → tell the user the update installed.
 *  * anything else → post the failure sentence and log the status code. Never silent.
 *
 * The receiver is `exported="false"` (see the manifest): the only sender is the system, which
 * delivers through the PendingIntent this app created, and an explicit same-package intent needs
 * no permission.
 *
 * HERMES INTEGRATION POINT (Session 5 audit) — this file and the `getBroadcast` in
 * [UpdateInstaller.installStaged] are one fix; neither half works alone.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
class UpdateInstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The documented contract: the app launches the confirmation itself. On some OEM
                // builds the extra arrives as a nested intent, on others as a plain one; both are
                // handled by taking whichever is present.
                val confirm = confirmIntent(intent)
                if (confirm == null) {
                    DebugLog.w(TAG, "install pending user action but no confirmation intent delivered")
                    UpdateInstaller.notifyProgress(
                        context,
                        "Update ready",
                        "Tap to finish installing the update.",
                    )
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { error ->
                        DebugLog.w(TAG, "confirmation launch failed: ${error.javaClass.simpleName}")
                        UpdateInstaller.notifyProgress(
                            context,
                            "Update ready",
                            "Open the app to finish installing the update.",
                        )
                    }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                DebugLog.d(TAG, "update installed")
                UpdateInstaller.notifyProgress(
                    context,
                    "Update installed",
                    "Hermes X is up to date.",
                )
            }

            else -> {
                // STATUS_FAILURE / STATUS_FAILURE_ABORTED / STATUS_FAILURE_BLOCKED /
                // STATUS_FAILURE_INVALID / STATUS_FAILURE_CONFLICT / STATUS_FAILURE_STORAGE /
                // STATUS_FAILURE_INCOMPATIBLE — all mean "the update did not land". The user is
                // told, and the code is logged so a report can name the cause.
                DebugLog.w(TAG, "install failed with status $status")
                UpdateInstaller.notifyProgress(
                    context,
                    "Update not installed",
                    "The update could not be installed (status $status). Try again from About.",
                )
            }
        }
    }

    /**
     * The system's confirmation activity, wherever this platform put it.
     *
     * `EXTRA_INTENT` is the documented location. Older/odd builds have been seen to nest it in
     * the parcelable extra instead; both shapes are read, and `null` is a real answer (the caller
     * tells the user to open the app rather than pretending the confirmation appeared).
     */
    private fun confirmIntent(intent: Intent): Intent? {
        intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.let { return it }
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(Intent.EXTRA_INTENT)
    }

    companion object {
        private const val TAG = "HermesUpdate"
        const val ACTION_INSTALL_RESULT = "com.hermes.app.action.INSTALL_RESULT"

        /** Carried only so a future UI can correlate the result with the version it offered. */
        const val EXTRA_VERSION_FOR_UI = "version"
    }
}
