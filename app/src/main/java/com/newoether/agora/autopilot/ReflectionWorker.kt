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

            val transcript = transcriptFor(container, conversationId)
                ?: return@withContext Result.success()

            val outcome = engine.run(
                transcript = transcript,
                existingFiles = container.memoryManager.listFiles().map { it.name },
                sourceSessionId = conversationId,
            )
            DebugLog.d(TAG, "reflection for $conversationId -> applied=${outcome.applied} " +
                "skipped=${outcome.skippedReason}")

            if (outcome.didApply) {
                AutopilotNotifier.notifyMemoriesUpdated(applicationContext, outcome.applied)
            }
            // Phase 4 retention runs on the same cadence as reflection.
            CircuitBreaker(log, applier).pruneRetention()
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
    ): String? {
        val topology: List<MessageContextTopology> =
            container.conversationRepository.getMessageTopologySnapshot(conversationId)
        val conversational = topology.filter {
            it.participant == Participant.USER || it.participant == Participant.MODEL
        }
        if (conversational.size < MESSAGES_PER_REFLECTION) return null

        val recent = conversational.takeLast(TRANSCRIPT_MESSAGE_LIMIT)
        val texts = container.conversationRepository.getMessagesByIds(recent.map { it.id })
            .mapNotNull { entity -> entity.text.takeIf { it.isNotBlank() } }
        return texts.joinToString("\n\n").ifBlank { null }
    }

    companion object {
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
