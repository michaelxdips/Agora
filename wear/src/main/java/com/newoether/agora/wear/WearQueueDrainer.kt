package com.newoether.agora.wear

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sends one question. The drainer does not know how — that is what makes it testable.
 *
 * `WearChatClient` needs a real HTTP stack and a real config; the *decision* of which held question to
 * send next, when to give up, and whether an answer may take over the screen does not. Before this
 * seam existed that decision lived inside a composable local function, so no unit test could reach it
 * and the launch-drain bug (a held question that was never delivered) shipped.
 */
fun interface QuestionSender {
    suspend fun ask(question: String, coreContext: String): Result<String>
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
 *  * **give up only at [WearOfflineQueue.MAX_ATTEMPTS]** — a permanently failing question must not
 *    block the ones behind it forever;
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
     */
    suspend fun drain(
        queue: WearOfflineQueue,
        sender: QuestionSender,
        coreContext: String,
        showResult: Boolean,
        onFailure: (String) -> Unit = {},
    ): DrainReport = drainMutex.withLock {
        withContext(Dispatchers.IO) {
            val pending = queue.all()
            if (pending.isEmpty()) return@withContext DrainReport(0, emptyList(), null, null)
            var delivered = 0
            val answers = mutableListOf<String>()
            var lastAnswer: String? = null
            var droppedText: String? = null
            for (entry in pending) {
                val outcome = sender.ask(entry.text, coreContext)
                if (outcome.isSuccess) {
                    queue.complete(entry.id)
                    delivered += 1
                    val answer = outcome.getOrNull().orEmpty()
                    answers += answer
                    if (showResult) {
                        lastAnswer = answer
                    }
                } else {
                    onFailure(outcome.exceptionOrNull()?.message.orEmpty())
                    if (queue.recordFailure(entry.id)) droppedText = entry.text
                    break
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
}
