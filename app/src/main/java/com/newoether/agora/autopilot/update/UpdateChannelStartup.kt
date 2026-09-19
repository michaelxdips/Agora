package com.newoether.agora.autopilot.update

import android.content.Context
import com.newoether.agora.AgoraApplication
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Starts the fork's update channel once per process: the daily periodic check.
 *
 * Called from `AgoraApplication.onCreate` — the same one-line pattern as `MemoryPushStartup`,
 * and for the same reason: the update check must not depend on the user opening a particular
 * screen. The startup coordinator (`StartupMaintenanceCoordinator`) still does the
 * check-at-cold-start; this is the backstop for long-lived processes.
 *
 * No upstream file is touched: this object, the workers, and the installer are all fork-only
 * files under `autopilot/update/`, and the single call site is `AgoraApplication.onCreate`,
 * which already carries a touchpoint entry (see UPSTREAM_TOUCHPOINTS.md #17).
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
object UpdateChannelStartup {

    private const val TAG = "HermesUpdate"

    /** Starts the periodic update check on [scope]. Called once per process. */
    fun start(context: Context, scope: CoroutineScope) {
        val application = context.applicationContext as? AgoraApplication ?: return
        scope.launch {
            runCatching {
                if (!UpdateChannel.isForkReleaseChannel(application)) return@launch
                UpdateCheckWorker.schedule(application)
                DebugLog.d(TAG, "periodic update check scheduled")
            }.onFailure { error ->
                DebugLog.w(TAG, "update channel start failed: ${error.javaClass.simpleName}")
            }
        }
    }
}
