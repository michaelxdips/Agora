package com.newoether.agora.autopilot

import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException

/** Outcome of one reflection pass, for logging and tests. */
data class ReflectionOutcome(
    val applied: Int = 0,
    val skippedReason: String? = null,
) {
    val didApply: Boolean get() = applied > 0
}

/**
 * Orchestrates one reflection pass: prompt → provider call → conservative validation → apply.
 *
 * Everything that can refuse a write refuses here, so the worker stays a thin scheduler and the
 * whole decision is unit-testable without WorkManager or a Provider.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
class ReflectionEngine(
    /** Reflection call seam: production passes [ReflectionCaller::reflect]; tests pass a stub. */
    private val reflect: suspend (transcript: String, existingFiles: List<String>) -> String?,
    private val applier: MemoryApplier,
    private val log: AdaptationLogDao,
    private val settings: AutopilotControls,
) {
    /**
     * @param transcript the conversation text to reflect on.
     * @param existingFiles memory file names already present (for the prompt).
     * @param sourceSessionId conversation id, stored on every entry for provenance.
     * @param now injectable clock for tests.
     */
    suspend fun run(
        transcript: String,
        existingFiles: List<String>,
        sourceSessionId: String?,
        now: Long = System.currentTimeMillis(),
    ): ReflectionOutcome {
        if (!settings.isEnabled()) return ReflectionOutcome(skippedReason = "disabled")
        if (transcript.isBlank()) return ReflectionOutcome(skippedReason = "empty transcript")
        if (!settings.underDailyCap(log, AutopilotSettings.startOfToday(now))) {
            return ReflectionOutcome(skippedReason = "daily cap reached")
        }

        val reply = reflect(transcript, existingFiles)
            ?: return ReflectionOutcome(skippedReason = "no reflection reply")
        val plan = ReflectionProtocol.parse(reply)
            ?: return ReflectionOutcome(skippedReason = "unparseable reply")
        if (plan.ops.isEmpty()) return ReflectionOutcome(skippedReason = "no durable facts")

        var applied = 0
        for (op in plan.ops) {
            if (!settings.underDailyCap(log, AutopilotSettings.startOfToday(now))) {
                DebugLog.w(TAG, "daily cap reached mid-pass; stopping")
                break
            }
            val target = AdaptationTarget(AdaptationEntry.STORE_MEMORY, op.targetFile)
            val content = if (op.content.contains(PROVENANCE_TAG)) op.content
            else op.content.trimEnd() + "\n" + PROVENANCE_TAG + "\n"
            try {
                applier.apply(
                    target = target,
                    after = content,
                    reason = "${op.op}: ${op.category} (confidence ${op.confidence})",
                    sourceSessionId = sourceSessionId,
                    timestamp = now,
                )
                applied += 1
            } catch (cancelled: CancellationException) {
                // A cancelled pass must stop writing, not finish the loop. Catching bare Exception
                // here (CancellationException is one) kept applying every remaining op after the
                // work had been cancelled — memory written by a pass nobody is waiting for.
                throw cancelled
            } catch (error: Exception) {
                // One rejected op must not abandon the rest of the pass.
                DebugLog.w(TAG, "op rejected for ${op.targetFile}: ${error.message}")
            }
        }
        return ReflectionOutcome(applied = applied)
    }

    private companion object {
        const val TAG = "AutopilotEngine"
    }
}
