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
 * on reconnect, at-least-once on crash. The distinction is deliberate and documented below.
 *
 * `ponytail:` file-backed list, not Room. The whole queue is a few dozen short strings; Room would
 * add a schema, a migration policy and a KSP round for a JSON array. Upgrade to Room if the queue
 * ever needs queries beyond "next N".
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
    )

    fun all(): List<Entry> = readAll()

    fun size(): Int = readAll().size

    /** Adds a question. Returns its id, which the UI keeps to match the eventual answer. */
    suspend fun enqueue(text: String, now: Long = System.currentTimeMillis()): Entry = mutex.withLock {
        val entries = readAll().toMutableList()
        val entry = Entry(id = (entries.maxOfOrNull { it.id } ?: 0L) + 1, text = text, createdAt = now)
        entries += entry
        writeAll(entries)
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

    /** Records a failed attempt; drops the entry once it exceeds [MAX_ATTEMPTS]. */
    suspend fun recordFailure(id: Long): Boolean = mutex.withLock {
        val entries = readAll().toMutableList()
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return@withLock false
        val updated = entries[index].copy(attempts = entries[index].attempts + 1)
        if (updated.attempts >= MAX_ATTEMPTS) {
            entries.removeAt(index)
            writeAll(entries)
            return@withLock true
        }
        entries[index] = updated
        writeAll(entries)
        false
    }

    suspend fun clear() = mutex.withLock { runCatching { file.delete() } }

    private fun readAll(): List<Entry> = runCatching {
        if (!file.isFile) return emptyList()
        json.decodeFromString<List<Entry>>(file.readText())
    }.getOrDefault(emptyList())

    private fun writeAll(entries: List<Entry>) {
        file.parentFile?.mkdirs()
        // Write-then-rename: a kill mid-write leaves the previous queue intact rather than a
        // truncated file that parses as "no questions".
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(json.encodeToString(entries))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    companion object {
        const val FILE_NAME = "hermes_wear_queue.json"
        const val MAX_ATTEMPTS = 3
        private val json = Json { ignoreUnknownKeys = true }
    }
}
