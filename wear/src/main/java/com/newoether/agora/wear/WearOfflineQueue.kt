package com.newoether.agora.wear

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The watch's offline queue.
 *
 * A watch loses connectivity constantly (Bluetooth range, Wi-Fi sleep, airplane mode). A question
 * asked in that state must not be lost and must not be sent twice: the queue persists to disk, and
 * each entry carries a monotonic id that is only removed **after** a successful send — exactly-once
 * on reconnect, at-least-once on crash.
 *
 * **Scope of that promise, stated precisely** (it was overstated here before): exactly-once covers
 * *this queue's own bookkeeping*. The send itself is not transactional with the removal, so a crash
 * between the provider answering and `complete()` re-sends on the next pass — and a re-sent
 * completion is **billed twice** by the provider. The queue cannot close that window without a
 * provider-side idempotency key, which the OpenAI-compatible shape has no field for. What it does
 * guarantee is the half that matters to the user: a question is never lost, and it is never sent
 * twice *without a crash in between*.
 *
 * `ponytail:` file-backed list, not Room. The whole queue is a few dozen short strings; Room would
 * add a schema, a migration policy and a KSP round for a JSON array. Upgrade to Room if the queue
 * ever needs queries beyond "next N".
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
class WearOfflineQueue(private val context: android.content.Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val mutex = Mutex()
    @Serializable
    data class Entry(
        val id: Long,
        val text: String,
        val createdAt: Long,
        /** Bounded retries: a permanently failing request must not block the queue forever. */
        val attempts: Int = 0,
        /**
         * Wall-clock deadline before which this entry must not be retried, set from the server's
         * `Retry-After`. Null means "no server-imposed wait". Persisted, not held in memory: a
         * drain pass ends, but the throttle has to outlive it (Session 4 — the pass used to sleep
         * inside its own lock instead, which throttled nothing and froze the send button).
         */
        val notBefore: Long? = null,
    )

    fun all(): List<Entry> = readAll()

    fun size(): Int = readAll().size

    /** Adds a question. Returns its id, which the UI keeps to match the eventual answer. */
    suspend fun enqueue(text: String, now: Long = System.currentTimeMillis()): Entry = mutex.withLock {
        val entries = readAll().toMutableList()
        val entry = Entry(id = (entries.maxOfOrNull { it.id } ?: 0L) + 1, text = text, createdAt = now)
        entries += entry
        // Oldest first, and never unbounded: a queue that grows without a ceiling is a queue that
        // eventually fails to write (and then loses everything). The newest entry is the one the user
        // is waiting for, so the *oldest* is the one that goes.
        writeAll(entries.takeLast(MAX_ENTRIES))
        entry
    }

    /**
     * Removes an entry after its send succeeded.
     *
     * This is the exactly-once half: the entry survives a crash *during* the send, so the worst case
     * on crash is one duplicate send, never a lost question. Callers must not remove before the
     * response is in hand.
     */
    suspend fun complete(id: Long): Boolean = mutex.withLock {
        val entries = readAll()
        val remaining = entries.filterNot { it.id == id }
        if (remaining.size == entries.size) return@withLock false
        writeAll(remaining)
        true
    }

    /**
     * Records a failed attempt. Returns true when the entry left the queue.
     *
     * Two ways out, and they are not the same thing:
     *  * **permanent failure** ([permanent] = true) — the provider answered 401, or 404, or a body
     *    this build cannot read. Retrying cannot change that answer, so the entry is moved to the
     *    dead-letter file immediately and reported as dropped. Spending three attempts and three
     *    provider calls on a revoked key is not "resilience", it is three identical failures.
     *  * **attempt ceiling** — a transient failure that keeps failing (a dead endpoint, a captive
     *    portal) is dropped at [MAX_ATTEMPTS] as before.
     *
     * Either way the text is preserved in the dead-letter file rather than deleted. The previous
     * version removed the entry and the text was gone with it: a user whose key expired lost every
     * question they had asked while offline, with one error line as the only trace.
     */
    suspend fun recordFailure(id: Long, permanent: Boolean = false, notBefore: Long? = null): Boolean = mutex.withLock {
        val entries = readAll().toMutableList()
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return@withLock false
        val updated = entries[index].copy(
            attempts = entries[index].attempts + 1,
            // Session 4: a server-requested back-off is persisted with the entry. The drainer used
            // to `sleep(wait)` instead, which throttled nothing (the next pass retried at once) and
            // held the send lock for the whole wait. The queue is the only place that survives the
            // pass, so the deadline lives here.
            notBefore = notBefore?.let { System.currentTimeMillis() + it } ?: entries[index].notBefore,
        )
        if (permanent || updated.attempts >= MAX_ATTEMPTS) {
            entries.removeAt(index)
            writeAll(entries)
            deadLetter(updated)
            return@withLock true
        }
        entries[index] = updated
        writeAll(entries)
        false
    }

    /**
     * Reads the queue, quarantining a file that does not parse.
     *
     * The first version returned an empty list and left the corrupt file in place — so the next
     * `enqueue` wrote a one-entry queue over it and **every held question was gone**. A file we
     * cannot read is a file we must not overwrite: it is renamed aside (`.corrupt`) so the next
     * write starts clean while the bytes stay on disk for a later recovery attempt.
     */
    private fun readAll(): List<Entry> = runCatching {
        if (!file.isFile) return emptyList()
        json.decodeFromString<List<Entry>>(file.readText())
    }.getOrElse { error ->
        WearLog.w("offline queue unreadable (${error.javaClass.simpleName}); quarantined")
        runCatching { file.renameTo(File(file.parentFile, "$FILE_NAME.corrupt")) }
        emptyList()
    }

    private fun writeAll(entries: List<Entry>) {
        // One implementation, shared with the config store and the memory cache: temp file, fsync,
        // rename. See WearAtomicFile for why the fsync matters on a device that is killed without
        // warning.
        WearAtomicFile.write(file, json.encodeToString(entries))
    }

    /**
     * Keeps a dropped question's text.
     *
     * Append-only and bounded: the file is the record of what was *lost*, so it is not rotated away
     * silently, but it also cannot grow without limit on a watch. One JSON object per line keeps the
     * append cheap and the file readable with `cat`.
     */
    private fun deadLetter(entry: Entry) {
        runCatching {
            val target = File(file.parentFile, DEAD_LETTER_FILE)
            val existing = target.takeIf { it.isFile }?.readLines().orEmpty()
            val line = json.encodeToString(Entry.serializer(), entry).replace("\n", " ")
            val kept = (existing + line).takeLast(MAX_DEAD_LETTER)
            WearAtomicFile.write(target, kept.joinToString("\n") + "\n")
            WearLog.w("question moved to dead letter after ${entry.attempts} attempt(s)")
        }.onFailure { WearLog.w("dead letter write failed: ${it.javaClass.simpleName}") }
    }

    /** The questions that were given up on, newest last. Empty when none ever were. */
    fun deadLetters(): List<Entry> = runCatching {
        val target = File(file.parentFile, DEAD_LETTER_FILE)
        if (!target.isFile) return emptyList()
        target.readLines().filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.decodeFromString<Entry>(it) }.getOrNull() }
    }.getOrDefault(emptyList())

    companion object {
        const val FILE_NAME = "hermes_wear_queue.json"
        const val DEAD_LETTER_FILE = "hermes_wear_queue.dead.jsonl"
        const val MAX_ATTEMPTS = 3

        /** Ceiling on held questions. Oldest are evicted first; the newest is what the user waits on. */
        const val MAX_ENTRIES = 50

        /** Ceiling on retained dead letters, so the record cannot fill the watch's storage. */
        const val MAX_DEAD_LETTER = 50

        private val json = Json { ignoreUnknownKeys = true }
    }
}
