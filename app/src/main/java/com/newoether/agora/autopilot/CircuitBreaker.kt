package com.newoether.agora.autopilot

import com.newoether.agora.util.DebugLog

/**
 * Phase 4 circuit breaker.
 *
 * A correction is counted only when the user pushes back inside a session that actually saw the
 * adapted fact ([AdaptationInjection]). Two flags on one entry mean the adaptation was wrong often
 * enough to distrust it: it is rolled back and marked `needs_revision`.
 */
class CircuitBreaker(
    private val log: AdaptationLogDao,
    private val applier: MemoryApplier,
) {
    /**
     * Records one user correction in [sessionId].
     *
     * @return the entries that were auto-rolled back by this call.
     */
    suspend fun recordCorrection(sessionId: String): List<AdaptationEntry> {
        val candidates = log.all().filter { entry ->
            entry.status == AdaptationEntry.STATUS_APPLIED &&
                log.injectionsFor(entry.id).any { it.sessionId == sessionId }
        }
        val rolledBack = mutableListOf<AdaptationEntry>()
        for (entry in candidates) {
            log.incrementFeedback(entry.id)
            val updated = log.find(entry.id) ?: continue
            if (updated.feedbackFlags < FLAGS_BEFORE_ROLLBACK) continue
            if (!applier.undo(updated, AdaptationEntry.STATUS_AUTO_ROLLED_BACK)) {
                DebugLog.w(TAG, "auto-rollback failed for ${entry.targetFile}")
                continue
            }
            log.updateStatus(entry.id, AdaptationEntry.STATUS_NEEDS_REVISION)
            rolledBack += updated
        }
        return rolledBack
    }

    /**
     * Records the corrections named by [correctionKeys] against [sessionId], **at most once each**.
     *
     * The correction signal the roadmap promised ("2 flags → rollback") had no production caller, so
     * the rollback was unreachable. Its source is the durable transcript: a model answer that the user
     * asked again under the same parent message is a rejection of the previous answer, and that is
     * observable from `MessageContextTopology` without touching an upstream file. Each rejection
     * carries a stable id, and the ledger below makes a re-run of the same pass a no-op — otherwise
     * every reflection pass would re-count history and roll back good facts on a slow day.
     *
     * The ledger rides in `adaptation_injection` with [LEDGER_ADAPTATION_ID]: the table already means
     * "this adaptation reached this session", and a correction key is exactly that shape (a session,
     * plus one fact). Reusing it keeps the Room schema at version 1 — no migration for a bookkeeping
     * detail.
     *
     * @return the entries that were auto-rolled back by the newly counted corrections.
     */
    suspend fun recordCorrections(
        sessionId: String,
        correctionKeys: List<String>,
    ): List<AdaptationEntry> {
        val alreadyCounted = log.injectionsFor(LEDGER_ADAPTATION_ID).map { it.sessionId }.toSet()
        val fresh = correctionKeys.filterNot { it in alreadyCounted }
        var rolledBack = emptyList<AdaptationEntry>()
        for (key in fresh) {
            log.insertInjection(
                AdaptationInjection(
                    adaptationId = LEDGER_ADAPTATION_ID,
                    sessionId = key,
                    injectedAt = System.currentTimeMillis(),
                )
            )
            rolledBack = rolledBack + recordCorrection(sessionId)
        }
        return rolledBack
    }

    /** Records that [entry]'s content was injected into [sessionId]. */
    suspend fun recordInjection(entry: AdaptationEntry, sessionId: String, now: Long = System.currentTimeMillis()) {
        log.insertInjection(
            AdaptationInjection(adaptationId = entry.id, sessionId = sessionId, injectedAt = now)
        )
    }

    /**
     * Retention: at most [MAX_VERSIONS_PER_FILE] entries per file and nothing older than
     * [MAX_AGE_MILLIS], whichever comes first.
     *
     * This prunes the *journal only* — it never touches the Agora memory/skill files, so an expired
     * version simply stops being offered as an undo target while the live content is untouched.
     *
     * @return the number of entries dropped.
     */
    suspend fun pruneRetention(now: Long = System.currentTimeMillis()): Int {
        val stale = mutableSetOf<Long>()
        stale += log.idsOlderThan(now - MAX_AGE_MILLIS)

        log.all().groupBy { it.store to it.targetFile }.forEach { (key, _) ->
            val (store, file) = key
            stale += log.idsBeyondRetention(file, store, MAX_VERSIONS_PER_FILE)
        }

        if (stale.isEmpty()) return 0
        val ids = stale.toList()
        log.deleteInjections(ids)
        return ids.count { log.delete(it) > 0 }
    }

    companion object {
        const val FLAGS_BEFORE_ROLLBACK = 2
        const val MAX_VERSIONS_PER_FILE = 50
        const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000

        /**
         * Sentinel `adaptationId` for the correction ledger in `adaptation_injection`.
         *
         * Real adaptation rows use auto-generated positive ids, so a non-positive sentinel can never
         * collide with one. See [recordCorrections] for why the ledger lives there.
         */
        const val LEDGER_ADAPTATION_ID = -1L

        private const val TAG = "AutopilotBreaker"
    }
}
