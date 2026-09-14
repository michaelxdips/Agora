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

        private const val TAG = "AutopilotBreaker"
    }
}
