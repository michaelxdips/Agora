package com.newoether.agora.wear

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sends one question. The drainer does not know how — that is what makes it testable.
 *
 * `WearChatClient` needs a real HTTP stack and a real config; the *decision* of which held question to
 * send next, when to give up, and whether an answer may take over the screen does not. Before this
 * seam existed that decision lived inside a composable local function, so no unit test could reach it
 * and the launch-drain bug (a held question that was never delivered) shipped.
 *
 * A sender reports **why** it failed, not only that it did: [AskOutcome] carries the retry verdict and
 * the server's own back-off, which is what lets the drainer stop retrying a revoked key and honour a
 * `Retry-After` instead of hammering a rate-limited endpoint.
 */
fun interface QuestionSender {
    suspend fun ask(question: String, coreContext: String): AskOutcome
}

/**
 * One send attempt's result.
 *
 * `Result<String>` could not express the two things the queue needs — whether retrying can help, and
 * how long to wait — so a `Result.failure(WearChatException)` was translated back into those answers
 * at every call site. Carrying them here means the translation happens once, next to the HTTP call
 * that knows.
 */
sealed interface AskOutcome {
    data class Answer(val text: String) : AskOutcome

    /**
     * @param retryable false for a permanent failure (401/404/parse/too-large): the queue drops the
     *   entry to the dead letter immediately instead of spending its attempts.
     * @param retryAfterMs server-provided back-off, when there was one.
     */
    data class Failed(
        val message: String,
        val retryable: Boolean = true,
        val retryAfterMs: Long? = null,
    ) : AskOutcome

    companion object {
        /** Bridges the client's own result shape into this one, without losing the verdict. */
        fun from(result: Result<String>): AskOutcome = result.fold(
            onSuccess = { Answer(it) },
            onFailure = { error ->
                val typed = error as? WearChatException
                Failed(
                    message = error.message.orEmpty(),
                    retryable = typed?.retryable ?: true,
                    retryAfterMs = typed?.retryAfterMs,
                )
            },
        )
    }
}

/** What one drain pass did, so the caller can render it without re-deriving anything. */
data class DrainReport(
    val delivered: Int,
    /**
     * Every answer this pass produced, oldest first.
     *
     * The first version kept only the newest, so draining 5 held questions showed one answer and
     * **deleted the other 4 without ever displaying them** — the user could not tell the difference
     * between "answered" and "lost". The list is what lets the UI say how many there were.
     */
    val answers: List<String>,
    val droppedText: String?,
    val lastAnswer: String?,
) {
    /** True when held questions were answered without their answers being shown. */
    val answersNotShown: Int get() = if (lastAnswer == null) answers.size else (answers.size - 1).coerceAtLeast(0)
}

/**
 * The offline queue's drain pass.
 *
 * Extracted verbatim from `WearChatActivity.drainQueue` (same order, same give-up rule, same
 * showResult rule) so a regression test can hold it still. The rules, and why they are what they are:
 *
 *  * **oldest first** — the queue is a queue; a question asked on the train must not be overtaken by
 *    one asked at the café;
 *  * **complete only after the answer is in hand** — exactly-once on reconnect, at-least-once on crash,
 *    never lost;
 *  * **stop at the first failure** — if the network is down, sending the rest would burn their attempt
 *    budget for nothing;
 *  * **give up on a permanent failure at once, and on a transient one only at
 *    [WearOfflineQueue.MAX_ATTEMPTS]** — a permanently failing question must not block the ones behind
 *    it forever, and a 401 must not be retried three times to prove it is a 401;
 *  * **a dropped question is reported, not logged** — a question that disappears with only a log line
 *    is the worst failure mode this queue exists to prevent;
 *  * **[DrainReport.lastAnswer] is only set when `showResult`** — the queue holds the *oldest*
 *    questions, so letting a drained answer win would replace the answer the user just asked for with
 *    an answer to a question they asked minutes ago.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
object WearQueueDrainer {

    /**
     * Serializes drain passes process-wide.
     *
     * Without it, two passes can snapshot the same entries before either removes them — and since
     * the snapshot is taken outside the queue's own mutex, "launch drain" and "drain after a
     * successful send" running concurrently would **send the same held question twice**. The queue
     * guarantees exactly-once per entry, so the pass that reads it has to be exclusive too.
     */
    private val drainMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * @param queue the persisted queue; entries are removed here, never by the caller.
     * @param sender how to send one question.
     * @param coreContext the derived core context for this pass.
     * @param showResult whether a drained answer may take over the visible answer slot.
     * @param onWait called with the server-requested back-off before the pass gives up, so the UI can
     *   say "the provider asked us to wait" instead of the generic offline line.
     */
    suspend fun drain(
        queue: WearOfflineQueue,
        sender: QuestionSender,
        coreContext: String,
        showResult: Boolean,
        onFailure: (String) -> Unit = {},
        onWait: (Long) -> Unit = {},
    ): DrainReport = drainMutex.withLock {
        withContext(Dispatchers.IO) {
        val pending = queue.all()
        if (pending.isEmpty()) return@withContext DrainReport(0, emptyList(), null, null)
        var delivered = 0
        val answers = mutableListOf<String>()
        var lastAnswer: String? = null
        var droppedText: String? = null
        var retryAfterForEntry: Long? = null
        // A server-requested wait is honoured by *skipping the entry*, not by sleeping the pass:
        // the deadline was persisted on the entry by the previous failure, so the pass can end
        // immediately and the next pass — minutes later, after a reboot even — still respects it.
        // The scan stops (not `continue`) because a rate limit is per-account: sending the next
        // question into a throttled endpoint would burn its attempt budget for the same 429.
        val now = System.currentTimeMillis()
        for (entry in pending) {
            if (entry.notBefore != null && entry.notBefore > now) break
            val outcome = sender.ask(entry.text, coreContext)
            when (outcome) {
                is AskOutcome.Answer -> {
                    // HERMES INTEGRATION POINT (Session 5 audit): the removal is the commit point,
                    // and it can fail (disk full). Sending more entries on a filesystem that cannot
                    // record their removal would charge the user again on the next pass for every
                    // question the queue still holds, so the pass stops at the first failed commit
                    // instead of spending more provider calls.
                    if (!queue.complete(entry.id)) {
                        WearLog.w("could not persist the queue; stopping this drain pass")
                        break
                    }
                    delivered += 1
                    answers += outcome.text
                    if (showResult) {
                        lastAnswer = outcome.text
                    }
                }
                is AskOutcome.Failed -> {
                    onFailure(outcome.message)
                    // A 429/503 with `Retry-After` is the server telling us when it will listen
                    // again. Ignoring it and retrying immediately is how a rate-limited watch turns
                    // one throttled question into a longer throttle — and the wait is bounded by the
                    // same ceiling the HTTP call uses, so a hostile `Retry-After: 86400` cannot park
                    // the pass (and the user's queue) for a day.
                    val wait = outcome.retryAfterMs?.takeIf { it > 0L }?.coerceAtMost(MAX_RETRY_AFTER_MS)
                    if (wait != null && outcome.retryable) {
                        onWait(wait)
                        // HERMES INTEGRATION POINT (Session 4): the wait used to be `sleep(wait)`
                        // *inside* `drainMutex`, and it did not throttle anything: the sleep ended,
                        // the pass gave up, the lock released, and the very next drain — a second
                        // later, after any successful send — retried the same entry immediately.
                        // What it did do was hold `sending = true` for the whole wait, so the
                        // button read "Sending…" long after the answer was on screen. The throttle
                        // is now *persisted with the entry* ([WearOfflineQueue.recordFailure]'s
                        // `notBefore`), so the next pass skips it until the server is ready, and the
                        // pass itself returns immediately.
                        retryAfterForEntry = wait
                    }
                    if (queue.recordFailure(entry.id, permanent = !outcome.retryable, notBefore = retryAfterForEntry)) {
                        droppedText = entry.text
                    }
                    break
                }
            }
        }
        DrainReport(
            delivered = delivered,
            answers = answers,
            droppedText = droppedText,
            lastAnswer = lastAnswer,
        )
        }
    }

    /** Ceiling on an honoured `Retry-After`: the pass may not be parked longer than this. */
    const val MAX_RETRY_AFTER_MS = 30_000L
}
