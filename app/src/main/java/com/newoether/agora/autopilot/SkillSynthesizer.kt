package com.newoether.agora.autopilot

import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException

/**
 * Phase 5 — writes a synthesized skill draft through `SkillManager`, with full snapshot/rollback
 * parity and the same caps and provenance as memory.
 *
 * The draft is written to the **skill store** (`STORE_SKILL`), so the resulting `AdaptationLog` row
 * is undoable from the same Adaptation History screen.
 */
class SkillSynthesizer(
    private val applier: MemoryApplier,
    private val log: AdaptationLogDao,
    private val settings: AutopilotControls,
) {
    /**
     * @param transcript the finished session's text.
     * @param reflect the synthesis call (same provider path as reflection).
     * @param existingSkills current skill file names, for the prompt.
     */
    suspend fun synthesize(
        transcript: String,
        existingSkills: List<String>,
        reflect: suspend (String, List<String>) -> String?,
        sourceSessionId: String?,
        now: Long = System.currentTimeMillis(),
    ): Long? {
        if (!settings.isEnabled()) return null
        if (!settings.underDailyCap(log, AutopilotSettings.startOfToday(now))) {
            DebugLog.w(TAG, "skill synthesis skipped: daily cap reached")
            return null
        }
        val reply = reflect(
            // HERMES P5 ISOLATION: same rule as reflection — persona blocks never reach synthesis,
            // so the synthesized skill draft is written in normal style and parses as a draft.
            SkillSynthesisProtocol.synthesisPrompt(PersonaStore.stripAll(transcript), existingSkills),
            existingSkills,
        ) ?: return null
        val draft = SkillSynthesisProtocol.parse(reply) ?: return null

        // A draft is a NEW skill. Overwriting an existing skill would silently destroy user-authored
        // content, so a name collision is refused instead of applied (never lose user data).
        if (existingSkills.any { it.equals(draft.name, ignoreCase = true) }) {
            DebugLog.w(TAG, "skill draft rejected: '${draft.name}' already exists")
            return null
        }

        return try {
            applier.apply(
                target = AdaptationTarget(AdaptationEntry.STORE_SKILL, draft.name),
                after = draft.toMarkdown(),
                reason = "skill draft: ${draft.trigger.take(120)}",
                sourceSessionId = sourceSessionId,
                timestamp = now,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled   // a cancelled pass is not a rejected draft; see ReflectionEngine
        } catch (error: Exception) {
            // A name collision or an invalid skill name must not break the pass.
            DebugLog.w(TAG, "skill draft rejected (${draft.name}): ${error.message}")
            null
        }
    }

    private companion object {
        const val TAG = "AutopilotSkills"
    }
}
