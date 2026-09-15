package com.newoether.agora.autopilot

import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Turns Agora's public generation signals into autopilot triggers — with **zero upstream edits**.
 *
 * `ChatViewModel.generatingConversationIds` is a public `StateFlow<Set<String>>` covering both
 * foreground and headless generation (`ChatViewModel.kt:495-500`), so "session idle" is exactly
 * "an id left that set". Nothing inside Agora has to be patched to observe it (N8-friendly).
 *
 * The "every 20 messages" trigger is not implemented here: it is evaluated inside
 * [ReflectionWorker] against the durable topology, so a dropped observation can never lose it.
 */
class AutopilotTriggerObserver(
    private val context: android.content.Context,
    private val scope: CoroutineScope,
) {
    /**
     * Starts observing [generatingConversationIds].
     *
     * Two jobs per transition:
     *  * **a session that just started** → the adaptations currently in active memory are, at that
     *    moment, inside this session's prompt. That is the only honest definition of "injected", and
     *    it is what `CircuitBreaker.recordCorrection` gates on. Before this, `recordInjection` had
     *    **no production caller at all**, so `injectionsFor(...)` was always empty, no correction was
     *    ever counted, and the auto-rollback the roadmap calls "2 flags → rollback" could not happen
     *    for any real user. A finished feature that can never fire is worse than a missing one.
     *  * **a session that just went idle** → schedule the reflection pass (unchanged behaviour).
     *
     * @param schedule injectable scheduler (tests pass a recorder; production uses
     *   [ReflectionWorker.schedule]).
     * @return the collection job, so a caller (or a test) can await or cancel it.
     */
    fun start(
        generatingConversationIds: Flow<Set<String>>,
        schedule: (android.content.Context, String) -> Unit = ReflectionWorker::schedule,
        onSessionStart: (suspend (String) -> Unit)? = null,
    ): kotlinx.coroutines.Job = scope.launch {
        var previous: Set<String>? = null
        generatingConversationIds
            .distinctUntilChanged()
            .collect { current ->
                // The first emission only establishes the baseline: whatever was already
                // generating when the observer attached did not just start or just finish.
                previous?.let { last ->
                    (current - last).forEach { conversationId ->
                        DebugLog.d(TAG, "session started: $conversationId")
                        (onSessionStart ?: ::recordInjectionFor)?.let { record ->
                            runCatching { record(conversationId) }.onFailure { error ->
                                // Never let bookkeeping break the trigger observer.
                                DebugLog.w(TAG, "injection record failed: ${error.javaClass.simpleName}")
                            }
                        }
                    }
                    (last - current).forEach { conversationId ->
                        DebugLog.d(TAG, "session idle: $conversationId")
                        schedule(context, conversationId)
                    }
                }
                previous = current
            }
    }

    /**
     * Records every adaptation that is live in active memory as injected into [conversationId].
     *
     * Uses the process-scoped container so the observer keeps its single call site in `MainActivity`
     * (no new integration point) and reads only public container members.
     */
    private suspend fun recordInjectionFor(conversationId: String) {
        val application = context.applicationContext as? com.newoether.agora.AgoraApplication ?: return
        val container = application.awaitContainer() ?: return
        val log = AdaptationDatabase.get(context).adaptationLogDao()
        val breaker = CircuitBreaker(log, MemoryApplier(container.memoryManager, container.skillManager, log))
        log.all()
            .filter { it.status == AdaptationEntry.STATUS_APPLIED }
            .forEach { breaker.recordInjection(it, conversationId) }
    }

    private companion object {
        const val TAG = "AutopilotTriggers"
    }
}
