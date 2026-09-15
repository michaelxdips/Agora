package com.newoether.agora.autopilot

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.newoether.agora.AgoraApplication
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The single reflection scheduler.
 *
 * Triggers (all funnelled through [schedule], so one unique work name serializes them):
 *  * **session idle** — the app observes a conversation leaving `generatingConversationIds`;
 *  * **every 20 messages** — checked here against the durable topology;
 *  * **manual debug trigger** — debug builds only, via [scheduleNow].
 *
 * Battery-aware: the work is constrained to a non-low battery state, so reflection never competes
 * with a foreground generation on a dying phone.
 *
 * Failure is never user-visible: an unconfigured provider or a transport error ends the run as
 * `Result.success()` with a log line, because retrying a misconfiguration would just drain battery.
 */
class ReflectionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = (applicationContext as AgoraApplication).awaitContainer()
            ?: return@withContext Result.retry()

        val settings = AutopilotSettings(applicationContext)
        if (!settings.isEnabled()) return@withContext Result.success()

        val conversationId = inputData.getString(KEY_CONVERSATION_ID)
            ?: return@withContext Result.success()

        try {
            val log = AdaptationDatabase.get(applicationContext).adaptationLogDao()
            val applier = MemoryApplier(container.memoryManager, container.skillManager, log)
            val engine = ReflectionEngine(
                reflect = ReflectionCaller(
                    settings = container.settingsRepository,
                    providers = container.providerRegistry,
                )::reflect,
                applier = applier,
                log = log,
                settings = settings,
            )

            // Phase 4 auto-rollback, given the caller it never had (audit A-008). A model answer the
            // user asked again under the same parent is a rejection; each one counts at most once,
            // ledger-backed, so a re-run cannot inflate the flags.
            val topology = container.conversationRepository.getMessageTopologySnapshot(conversationId)
            val breaker = CircuitBreaker(log, applier)
            val rejections = rejectedAnswerIds(topology)
            if (rejections.isNotEmpty()) {
                val rolledBack = breaker.recordCorrections(conversationId, rejections)
                DebugLog.d(TAG, "corrections=$rejections rolledBack=${rolledBack.size}")
            }

            val transcript = transcriptFor(container, conversationId, topology)
                ?: return@withContext Result.success()

            val outcome = engine.run(
                transcript = transcript,
                existingFiles = container.memoryManager.listFiles().map { it.name },
                sourceSessionId = conversationId,
            )
            DebugLog.d(TAG, "reflection for $conversationId -> applied=${outcome.applied} " +
                "skipped=${outcome.skippedReason}")

            // Phase 5 Skills v1, given the caller it never had (audit A-009). `SkillSynthesizer` and
            // `SkillCandidateDetector` had zero production callers, so "≥3 chained tool calls OR ≥2
            // corrections → draft skill" only ever ran inside its own tests: the feature was shipped
            // as code and never as behaviour. The signals are both readable from the same durable
            // topology the trigger already loads.
            val toolCalls = chainedToolCalls(topology)
            if (SkillCandidateDetector.isCandidate(
                    chainedToolCalls = toolCalls,
                    userCorrections = rejections.size,
                    succeeded = outcome.didApply,
                )
            ) {
                val caller = ReflectionCaller(
                    settings = container.settingsRepository,
                    providers = container.providerRegistry,
                )
                val draftId = SkillSynthesizer(applier, log, settings).synthesize(
                    transcript = transcript,
                    existingSkills = container.skillManager.listFiles().map { it.name },
                    reflect = caller::reflect,
                    sourceSessionId = conversationId,
                )
                DebugLog.d(
                    TAG,
                    "skill candidate (${SkillCandidateDetector.reason(toolCalls, rejections.size)}) " +
                        "-> draft=${draftId ?: "none"}",
                )
            }

            if (outcome.didApply) {
                AutopilotNotifier.notifyMemoriesUpdated(applicationContext, outcome.applied)
            }
            // Phase 4 retention runs on the same cadence as reflection.
            breaker.pruneRetention()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Never surface an autopilot failure to the user; never block on it either.
            DebugLog.e(TAG, "reflection run failed for $conversationId", error)
            Result.success()
        }
    }

    /**
     * The conversation text to reflect on, or null when this conversation is not eligible yet.
     *
     * Eligibility is the "every 20 messages" trigger: below the threshold there is not enough
     * material for a conservative extraction, so nothing runs.
     */
    private suspend fun transcriptFor(
        container: com.newoether.agora.di.AppContainer,
        conversationId: String,
        topology: List<MessageContextTopology>,
    ): String? {
        val conversational = topology.filter {
            it.participant == Participant.USER || it.participant == Participant.MODEL
        }
        if (conversational.size < MESSAGES_PER_REFLECTION) return null

        val recent = conversational.takeLast(TRANSCRIPT_MESSAGE_LIMIT)
        val requested = recent.map { it.id }
        val texts = container.conversationRepository.getMessagesByIds(requested)
            // `getMessagesByIds` has no ORDER BY, so its result order is whatever the query planner
            // returns — and a transcript in the wrong order reads as a different conversation to the
            // extraction prompt. Re-ordered here, in Hermes-owned code, rather than by editing the
            // upstream DAO (N8: no upstream touchpoint for a fix this small).
            .associateBy { it.id }
        return requested.mapNotNull { id -> texts[id]?.text?.takeIf { it.isNotBlank() } }
            .joinToString("\n\n")
            .ifBlank { null }
    }

    companion object {
        /**
         * The ids of model answers the user rejected by asking again under the same parent.
         *
         * Regenerating an answer (the `regenerate` action in the chat UI) creates a second MODEL row with
         * the same `parentId` as the first. That is a durable, upstream-visible signal of "this answer was
         * not what I wanted", and it is what gives the circuit breaker a correction to count. A sibling is
         * attributed to the first answer, because that is the one the user rejected.
         *
         * Pure and static so it is unit-testable on the JVM without Room or WorkManager.
         */
        internal fun rejectedAnswerIds(topology: List<MessageContextTopology>): List<String> =
            topology.filter { it.participant == Participant.MODEL }
                .groupBy { it.parentId }
                .filterKeys { it != null }
                .values
                .filter { siblings -> siblings.size > 1 }
                .mapNotNull { siblings -> siblings.minByOrNull { it.timestamp }?.id }

        /**
         * The longest run of consecutive tool-calling rows in the session.
         *
         * A tool call is durable: Agora stores the answer row as `MessageStatus.TOOL_CALLING` while the
         * model is chaining tools, so "the agent did real multi-step work here" is readable from the same
         * topology `SkillCandidateDetector` already expects — no upstream counter to add. The **longest
         * run**, not the total, because four isolated single calls are not a procedure; three in a row are.
         */
        internal fun chainedToolCalls(topology: List<MessageContextTopology>): Int {
            var longest = 0
            var current = 0
            topology.sortedWith(compareBy({ it.timestamp }, { it.id })).forEach { row ->
                if (row.participant == Participant.MODEL && row.status == MessageStatus.TOOL_CALLING) {
                    current += 1
                    if (current > longest) longest = current
                } else {
                    current = 0
                }
            }
            return longest
        }

        private const val TAG = "AutopilotWorker"
        private const val KEY_CONVERSATION_ID = "conversationId"
        private const val UNIQUE_WORK_NAME = "hermes_autopilot_reflection"

        /** The "every 20 messages" trigger threshold. */
        const val MESSAGES_PER_REFLECTION = 20

        /** Bounded transcript window so one reflection never reads an unbounded conversation. */
        const val TRANSCRIPT_MESSAGE_LIMIT = 40

        /**
         * Enqueue one reflection pass for [conversationId].
         *
         * Unique work with [ExistingWorkPolicy.REPLACE] keeps the queue to one pending pass while
         * still letting a newer session supersede an older pending request.
         */
        fun schedule(context: Context, conversationId: String) {
            val request = OneTimeWorkRequestBuilder<ReflectionWorker>()
                .setInputData(
                    androidx.work.workDataOf(KEY_CONVERSATION_ID to conversationId)
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Manual debug trigger ("Run reflection now"). Debug builds only at the call site. */
        fun scheduleNow(context: Context, conversationId: String) = schedule(context, conversationId)
    }
}
