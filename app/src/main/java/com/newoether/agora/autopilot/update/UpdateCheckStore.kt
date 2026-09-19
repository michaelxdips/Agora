package com.newoether.agora.autopilot.update

import android.content.Context

/**
 * Remembers the last update the background worker found, so an offer is not lost when the UI
 * is not collecting.
 *
 * Why this exists: [UpdateCheckBus] is a rendezvous (`replay = 0`), which is right for a live
 * dialog hand-off but wrong for the worker's real use case — the worker runs while the app is
 * closed, and a `SharedFlow` with no collector drops the emission. Without this store, a user
 * could go weeks without being told about a release the worker had already found, because the
 * offer only ever lived for the microseconds between the worker's emit and its exit.
 *
 * The store keeps the **version string only** — not a URL, not an APK — so a tampered file can
 * at most make the UI re-run its own update check, which queries the fork's own repository and
 * re-derives the download URL. No new preference file schema is exposed to upstream (N3/N4).
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
class UpdateCheckStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The version last offered by the background worker, or null. */
    fun lastOfferedVersion(): String? = prefs.getString(KEY_OFFERED_VERSION, null)

    /** Records an offer. Called by the worker; also called when the UI has shown it. */
    fun saveOfferedVersion(version: String) {
        prefs.edit().putString(KEY_OFFERED_VERSION, version).apply()
    }

    /** Clears the offer — the user acted on it (or the version is no longer newer). */
    fun clearOfferedVersion() {
        prefs.edit().remove(KEY_OFFERED_VERSION).apply()
    }

    companion object {
        private const val PREFS_NAME = "hermes_update_channel"
        private const val KEY_OFFERED_VERSION = "offered_version"
    }
}
