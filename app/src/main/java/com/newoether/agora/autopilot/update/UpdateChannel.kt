package com.newoether.agora.autopilot.update

import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.util.UpdateInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Which builds may self-update from the fork's GitHub Releases.
 *
 * The fdroid flavor is distributed **only** through those releases (see README "Not published
 * on any store"), so offering the release APK as an update is the channel working as designed.
 * The play flavor, if ever shipped to Play, must update through Play — a sideloaded APK over a
 * Play install splits the update path and risks the "unverified app" screen. That flavor's
 * update UI stays a release-notes link, never a download.
 *
 * The gate is a **flavor resource** (`bool/hermes_self_update_enabled`, `true` in `src/fdroid`,
 * `false` in `src/play`), not `BuildConfig.FLAVOR`: AGP 9 does not generate `BuildConfig` unless
 * a module opts in, and a resource is checked by the resource merger on every build — a flavor
 * that forgets the value fails the build instead of silently shipping the wrong default.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
object UpdateChannel {

    /** True only for the flavor the fork distributes itself. */
    fun isForkReleaseChannel(context: Context): Boolean =
        context.resources.getBoolean(R.bool.hermes_self_update_enabled)
}

/**
 * Latest update found by [UpdateCheckWorker], for the UI to pick up.
 *
 * Why a bus and not a direct dialog write: the worker runs with no composition in scope, and
 * the dialog state lives in `ChatViewModel`. The bus is the seam — the UI collects it where
 * the startup path already writes `_updateDialogData`, so both paths end in the same dialog.
 * Replay of 0 keeps it a rendezvous: a collector that arrives late gets nothing stale, and the
 * next worker run re-offers if the version is still newer.
 *
 * Pure JVM, no Android dependency — unit-testable.
 */
object UpdateCheckBus {

    private val _offers = MutableSharedFlow<UpdateInfo>(replay = 0, extraBufferCapacity = 1)
    val offers: SharedFlow<UpdateInfo> = _offers.asSharedFlow()

    /** Non-suspending offer from the worker; false only if a previous offer was never collected. */
    fun offer(info: UpdateInfo): Boolean = _offers.tryEmit(info)
}

/** Reads this build's version without a ViewModel — the worker has none. */
internal fun readVersion(context: Context): String? {
    return try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: Exception) {
        null
    }
}
