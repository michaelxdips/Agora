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
     * @param schedule injectable scheduler (tests pass a recorder; production uses
     *   [ReflectionWorker.schedule]).
     * @return the collection job, so a caller (or a test) can await or cancel it.
     */
    fun start(
        generatingConversationIds: Flow<Set<String>>,
        schedule: (android.content.Context, String) -> Unit = ReflectionWorker::schedule,
    ): kotlinx.coroutines.Job = scope.launch {
        var previous: Set<String>? = null
        generatingConversationIds
            .distinctUntilChanged()
            .collect { current ->
                // The first emission only establishes the baseline: whatever was already
                // generating when the observer attached did not just finish.
                previous?.let { last ->
                    (last - current).forEach { conversationId ->
                        DebugLog.d(TAG, "session idle: $conversationId")
                        schedule(context, conversationId)
                    }
                }
                previous = current
            }
    }

    private companion object {
        const val TAG = "AutopilotTriggers"
    }
}
