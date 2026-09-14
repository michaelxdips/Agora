package com.newoether.agora.autopilot

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [AdaptationLogDao] for JVM tests.
 *
 * Reproduces the exact SQL semantics the production queries rely on — descending `timestamp, id`
 * ordering, `LIMIT -1 OFFSET :keep` retention slicing, and `feedbackFlags = feedbackFlags + 1` —
 * so a passing test here is evidence about the real queries, not about a simplified fake.
 */
class FakeAdaptationLogDao : AdaptationLogDao {
    private val entries = linkedMapOf<Long, AdaptationEntry>()
    private val injections = mutableListOf<AdaptationInjection>()
    private var nextId = 1L
    private var nextInjectionId = 1L

    override suspend fun insert(entry: AdaptationEntry): Long {
        val id = nextId++
        entries[id] = entry.copy(id = id)
        return id
    }

    override suspend fun find(id: Long): AdaptationEntry? = entries[id]

    override suspend fun all(): List<AdaptationEntry> = entries.values.sortedWith(order)

    override fun observe(): Flow<List<AdaptationEntry>> =
        MutableStateFlow(entries.values.sortedWith(order))

    override suspend fun updateStatus(id: Long, status: String): Int {
        val entry = entries[id] ?: return 0
        entries[id] = entry.copy(status = status)
        return 1
    }

    override suspend fun incrementFeedback(id: Long): Int {
        val entry = entries[id] ?: return 0
        entries[id] = entry.copy(feedbackFlags = entry.feedbackFlags + 1)
        return 1
    }

    override suspend fun historyFor(file: String, store: String): List<AdaptationEntry> =
        all().filter { it.targetFile == file && it.store == store }

    override suspend fun delete(id: Long): Int = if (entries.remove(id) != null) 1 else 0

    override suspend fun countSince(since: Long): Int = entries.values.count { it.timestamp >= since }

    override suspend fun idsBeyondRetention(file: String, store: String, keep: Int): List<Long> =
        historyFor(file, store).drop(keep).map { it.id }

    override suspend fun idsOlderThan(cutoff: Long): List<Long> =
        all().filter { it.timestamp < cutoff }.map { it.id }

    override suspend fun insertInjection(injection: AdaptationInjection): Long {
        val id = nextInjectionId++
        injections += injection.copy(id = id)
        return id
    }

    override suspend fun injectionsFor(adaptationId: Long): List<AdaptationInjection> =
        injections.filter { it.adaptationId == adaptationId }

    override suspend fun deleteInjections(ids: List<Long>): Int {
        val before = injections.size
        injections.removeAll { it.adaptationId in ids }
        return before - injections.size
    }

    /** Newest first, id as tie-breaker — matches `ORDER BY timestamp DESC, id DESC`. */
    private val order = compareByDescending<AdaptationEntry> { it.timestamp }
        .thenByDescending { it.id }
}
