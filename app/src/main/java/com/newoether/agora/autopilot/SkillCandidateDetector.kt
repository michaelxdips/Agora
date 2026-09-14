package com.newoether.agora.autopilot

/**
 * Phase 5 — candidate detection for a skill draft.
 *
 * Pure policy: given a finished session's shape, decide whether it is worth synthesizing a skill
 * from. Deliberately narrow — a candidate must show real, repeated problem-solving work.
 */
object SkillCandidateDetector {

    /** Minimum chained tool calls in one session before it looks like a procedure. */
    const val MIN_CHAINED_TOOL_CALLS = 3

    /** Minimum user corrections before a successful outcome, i.e. a discovered pitfall. */
    const val MIN_USER_CORRECTIONS = 2

    /**
     * @param chainedToolCalls how many tool calls ran back-to-back in the session.
     * @param userCorrections how many times the user corrected the agent before it succeeded.
     * @param succeeded whether the session actually reached a successful outcome.
     */
    fun isCandidate(
        chainedToolCalls: Int,
        userCorrections: Int,
        succeeded: Boolean,
    ): Boolean = succeeded && (
        chainedToolCalls >= MIN_CHAINED_TOOL_CALLS || userCorrections >= MIN_USER_CORRECTIONS
        )

    /** Why [isCandidate] returned true, for the `AdaptationLog` reason field. */
    fun reason(chainedToolCalls: Int, userCorrections: Int): String = buildList {
        if (chainedToolCalls >= MIN_CHAINED_TOOL_CALLS) add("$chainedToolCalls chained tool calls")
        if (userCorrections >= MIN_USER_CORRECTIONS) add("$userCorrections user corrections")
    }.joinToString(", ").ifBlank { "no candidate signal" }
}
