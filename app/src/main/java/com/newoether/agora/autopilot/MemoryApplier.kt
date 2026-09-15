package com.newoether.agora.autopilot

import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException

/**
 * One addressable file inside an Agora-owned store.
 *
 * v1 scope: **saved** memory files (`memory_db/<name>.md`), skill files (`skill_db/<name>.md`), and
 * the singleton active-memory file ([AdaptationEntry.STORE_ACTIVE_MEMORY]). The singleton needs its
 * own store id because `MemoryManager` resolves every other name under `memory_db/` — see the
 * constant's KDoc for the data loss that caused.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
data class AdaptationTarget(
    val store: String,
    val fileName: String,
) {
    init {
        require(
            store == AdaptationEntry.STORE_MEMORY ||
                store == AdaptationEntry.STORE_SKILL ||
                store == AdaptationEntry.STORE_ACTIVE_MEMORY
        )
        require(fileName.isNotBlank())
    }
}

/**
 * Applies one adaptation with the mandatory snapshot-first invariant (N5) and can undo it.
 *
 * Ordering is fixed: read current content → write the `AdaptationLog` row → only then touch the
 * Agora store. A crash between the two leaves a log row describing a write that did not happen,
 * which is recoverable; the reverse order would lose the prior content irrecoverably.
 */
class MemoryApplier(
    private val memoryManager: MemoryManager,
    private val skillManager: SkillManager,
    private val log: AdaptationLogDao,
) {
    /** Content currently stored for [target], or null when the file does not exist yet. */
    fun snapshot(target: AdaptationTarget): String? = try {
        read(target)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Writes [after] to [target], logging the exact prior content first.
     *
     * @return the new `AdaptationLog` row id.
     * @throws IllegalArgumentException when the write is rejected by the owning store; the log row
     *   is removed again so the journal never claims an adaptation that did not happen.
     */
    suspend fun apply(
        target: AdaptationTarget,
        after: String,
        reason: String,
        sourceSessionId: String? = null,
        timestamp: Long = System.currentTimeMillis(),
    ): Long {
        val before = snapshot(target)
        require(before != after) { "no-op adaptation for ${target.fileName}" }

        val id = log.insert(
            AdaptationEntry(
                timestamp = timestamp,
                store = target.store,
                targetFile = target.fileName,
                beforeSnapshot = before,
                afterSnapshot = after,
                reason = reason,
                sourceSessionId = sourceSessionId,
                status = AdaptationEntry.STATUS_APPLIED,
            )
        )

        try {
            write(target, after, existedBefore = before != null)
        } catch (error: Exception) {
            log.delete(id)
            throw error
        }
        return id
    }

    /**
     * Restores the exact prior bytes recorded by [entry].
     *
     * @param status one of [AdaptationEntry.STATUS_USER_ROLLED_BACK] or
     *   [AdaptationEntry.STATUS_AUTO_ROLLED_BACK].
     * @return true when the store now holds the prior bytes again.
     */
    suspend fun undo(
        entry: AdaptationEntry,
        status: String = AdaptationEntry.STATUS_USER_ROLLED_BACK,
    ): Boolean {
        require(status == AdaptationEntry.STATUS_USER_ROLLED_BACK ||
            status == AdaptationEntry.STATUS_AUTO_ROLLED_BACK) {
            "undo status must be a rolled-back status"
        }
        val target = AdaptationTarget(entry.store, entry.targetFile)
        val before = entry.beforeSnapshot
        try {
            if (before == null) {
                delete(target)
            } else {
                write(target, before, existedBefore = true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled   // a cancelled undo is not a failed undo; see ReflectionEngine
        } catch (error: Exception) {
            DebugLog.e(TAG, "undo failed for ${entry.targetFile}", error)
            return false
        }
        log.updateStatus(entry.id, status)
        return true
    }

    // ── store adapters ──────────────────────────────────────────────────────

    /**
     * The store a target really lives in.
     *
     * Rows written by the shipped persona code used `store = STORE_MEMORY, targetFile =
     * "active_memory.md"`, which resolves under `memory_db/`. Undoing one of those rows would hit the
     * wrong file, so the legacy pair is mapped to the active-memory store here rather than left to
     * data loss. New rows carry [AdaptationEntry.STORE_ACTIVE_MEMORY] directly.
     */
    private fun effectiveStore(store: String, fileName: String): String =
        if (store == AdaptationEntry.STORE_MEMORY && fileName == ACTIVE_MEMORY_NAME) {
            AdaptationEntry.STORE_ACTIVE_MEMORY
        } else {
            store
        }

    private fun read(target: AdaptationTarget): String = when (
        effectiveStore(target.store, target.fileName)
    ) {
        AdaptationEntry.STORE_ACTIVE_MEMORY -> memoryManager.getActiveMemory()
        AdaptationEntry.STORE_MEMORY -> memoryManager.readFile(target.fileName)
        else -> skillManager.readFile(target.fileName)
    }

    private fun write(target: AdaptationTarget, content: String, existedBefore: Boolean) {
        when (effectiveStore(target.store, target.fileName)) {
            // The active-memory file is a singleton with no create/delete primitive upstream, and
            // `getActiveMemory()` reports "" for a missing file — so an empty write is the exact
            // inverse of "there was nothing here".
            AdaptationEntry.STORE_ACTIVE_MEMORY -> memoryManager.updateActiveMemory(content)
            AdaptationEntry.STORE_MEMORY -> if (existedBefore) {
                memoryManager.editFile(name = target.fileName, content = content)
            } else {
                memoryManager.createFile(target.fileName, content)
            }
            else -> if (existedBefore) {
                skillManager.editFile(name = target.fileName, content = content)
            } else {
                skillManager.createFile(target.fileName, content)
            }
        }
    }

    private fun delete(target: AdaptationTarget) {
        when (effectiveStore(target.store, target.fileName)) {
            // No delete primitive for the singleton: clearing it is the closest exact inverse, and
            // `getActiveMemory()` cannot tell an empty file from an absent one.
            AdaptationEntry.STORE_ACTIVE_MEMORY -> memoryManager.updateActiveMemory("")
            AdaptationEntry.STORE_MEMORY -> memoryManager.deleteFile(target.fileName)
            else -> skillManager.deleteFile(target.fileName)
        }
    }

    private companion object {
        const val TAG = "AutopilotApplier"

        /** The singleton active-memory file name, as Agora's own Memory UI shows it. */
        const val ACTIVE_MEMORY_NAME = "active_memory.md"
    }
}
