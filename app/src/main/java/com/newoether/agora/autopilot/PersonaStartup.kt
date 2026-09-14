package com.newoether.agora.autopilot

import android.content.Context

/**
 * One-line startup hook for the persona channel (Phase 6 P1/P2).
 *
 * Called from the single existing `HERMES INTEGRATION POINT` block in `MainActivity`, so the persona
 * feature costs **one line** of upstream diff instead of its own integration point.
 *
 * Two jobs, both cheap and both idempotent:
 *  1. seed the vendored persona files out of assets into app storage on first run (assets are
 *     read-only, and the update flow must be able to replace them at runtime);
 *  2. enqueue the reconcile worker, which enforces "master off ⇒ personas stripped" and heals a
 *     store left half-written by a killed process.
 */
object PersonaStartup {
    fun run(context: Context) {
        val settings = PersonaSettings(context)
        PersonaRepository(context, settings).seedVendoredFromAssets(context.assets)
        PersonaReconcileWorker.schedule(context)
    }
}
