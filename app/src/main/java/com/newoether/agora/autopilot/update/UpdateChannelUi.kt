package com.newoether.agora.autopilot.update

import android.content.Context
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.UpdateChecker
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The UI half of the fork's update channel: collects [UpdateCheckBus] offers, announces them,
 * and writes the same dialog state the startup check uses.
 *
 * Why this object exists: the periodic worker has no composition in scope, so it cannot touch
 * `_updateDialogData` directly. `ChatViewModel.showUpdateDialog` is public, so no upstream
 * file needs a new method — this collect is the whole bridge, started from `MainActivity`'s
 * existing autopilot `LaunchedEffect` (touchpoint #18).
 *
 * Both paths end in one dialog (`MainApplicationDialogs`): cold-start offers from
 * `StartupMaintenanceCoordinator`, background offers from here. A stale offer (user already
 * updated, or dialog already showing a newer version) is dropped by re-checking, not shown.
 *
 * The dialog itself is upstream-owned, so its buttons cannot grow a fork download action. The
 * download trigger lives where the fork owns the UI instead: the notification posted here
 * ("Download & install" action) and the update card in Adaptation History. Either tap enqueues
 * [UpdateDownloadWorker] — never the dialog.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
object UpdateChannelUi {

    private const val TAG = "HermesUpdate"

    /** Collects worker offers into the update dialog. Call once from the autopilot scope. */
    fun collectOffers(viewModel: ChatViewModel, scope: CoroutineScope, appContext: Context) {
        scope.launch {
            // Catch-up: the worker may have found an update while the app was closed and its bus
            // emission was dropped (a rendezvous has no collector then). The persisted offer is
            // re-checked against the live repository before it reaches the dialog, so a stale
            // stored version cannot offer something that no longer exists.
            runCatching {
                val stored = UpdateCheckStore(appContext).lastOfferedVersion()
                if (stored != null) {
                    val fresh = viewModel.checkForUpdates()
                    if (fresh != null && fresh.version == stored) {
                        viewModel.showUpdateDialog(fresh)
                        UpdateDownloadAction.notifyOffer(appContext, fresh)
                    } else if (fresh != null && UpdateChecker.isNewer(fresh.version, stored)) {
                        viewModel.showUpdateDialog(fresh)
                        UpdateDownloadAction.notifyOffer(appContext, fresh)
                        UpdateCheckStore(appContext).saveOfferedVersion(fresh.version)
                    } else {
                        // No longer newer than what is installed (the user updated, or the release
                        // was withdrawn): drop the stored offer instead of re-showing it forever.
                        UpdateCheckStore(appContext).clearOfferedVersion()
                    }
                }
            }.onFailure { error ->
                DebugLog.w(TAG, "stored update offer catch-up failed: ${error.javaClass.simpleName}")
            }

            UpdateCheckBus.offers.collect { info ->
                // Drop the offer if the dialog already shows this version or newer — the user
                // already said later, or a fresher worker run superseded it. Compared with
                // isNewer, not `>=`: versions are dotted numbers, not strings ("10" < "9").
                val current = viewModel.updateDialogData.value
                if (current != null && !UpdateChecker.isNewer(info.version, current.version)) return@collect
                // Re-check before showing: the worker's finding may be hours old.
                val fresh = runCatching { viewModel.checkForUpdates() }.getOrNull() ?: info
                viewModel.showUpdateDialog(fresh)
                // Announce with a download action: the dialog is upstream-owned and cannot grow
                // a fork button, so the notification carries the "Download & install" tap.
                runCatching {
                    UpdateDownloadAction.notifyOffer(appContext, fresh)
                }.onFailure { error ->
                    DebugLog.w(TAG, "update offer notify failed: ${error.javaClass.simpleName}")
                }
            }
        }
    }
}
