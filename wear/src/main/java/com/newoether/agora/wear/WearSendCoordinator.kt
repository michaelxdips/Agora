package com.newoether.agora.wear

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One question, one send.
 *
 * The bug this closes: **Send** (and the IME's Send key, and a voice result) each launch their own
 * coroutine, and nothing owned "a send is already in flight". A double tap therefore ran two full
 * send paths for the same text — two queue entries, two HTTP calls, two provider charges — and the
 * second one's answer overwrote the first's on screen. On a watch, where the button is 40 px and the
 * user's other hand is holding a shopping bag, a double tap is the normal case, not the edge case.
 *
 * The rule is deliberately narrow, so it cannot become a UI that ignores the user:
 *  * the **same** text may not be sent while it is already being sent (a duplicate tap is dropped);
 *  * a **different** question is never blocked — it queues behind the lock and goes out in order;
 *  * the guard is released in a `finally`, so a cancelled send (wrist-down) frees it rather than
 *    wedging the button forever.
 *
 * Kept as a plain object with a mutex rather than a ViewModel: the watch has no ViewModel, and the
 * thing that needs to be provable is exactly this three-branch decision.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
object WearSendCoordinator {

    private val mutex = Mutex()
    private val inFlight = mutableSetOf<String>()

    /** What one [send] call did, so the caller can say so on screen. */
    sealed interface Outcome<out T> {
        /** The send ran and returned this value. */
        data class Ran<T>(val value: T) : Outcome<T>

        /** An identical send was already in flight; nothing was sent. */
        data object Duplicate : Outcome<Nothing>
    }

    /** True when [question] is currently being sent. Exposed for the UI's own disabled state. */
    suspend fun isInFlight(question: String): Boolean = mutex.withLock { question in inFlight }

    /** How many sends are in flight right now (any question). */
    suspend fun inFlightCount(): Int = mutex.withLock { inFlight.size }

    /**
     * Runs [block] for [question] unless an identical question is already running.
     *
     * [question] is the raw text the caller intends to send; it is trimmed here so `"hello"` and
     * `"hello "` are recognised as the same question rather than as two.
     */
    suspend fun <T> send(question: String, block: suspend () -> T): Outcome<T> {
        val key = question.trim()
        mutex.withLock {
            if (key in inFlight) return Outcome.Duplicate
            inFlight += key
        }
        return try {
            Outcome.Ran(block())
        } finally {
            // Always released: a cancelled send must not leave the question permanently "in flight",
            // which would silently swallow every later attempt at the same text.
            mutex.withLock { inFlight -= key }
        }
    }
}
