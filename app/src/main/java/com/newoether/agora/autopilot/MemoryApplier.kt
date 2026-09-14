package com.newoether.agora.autopilot

import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.util.DebugLog

/**
 * One addressable file inside an Agora-owned store.
 *
 * v1 scope: **saved** memory files (`memory_db/<name>.md`) and skill files (`skill_db/<name>.md`). The
 * singleton `active_memory.md` is deliberately excluded — Agora's `MemoryManager` exposes no
 * create/delete primitive for it, so adapting it would require a second, parallel write path
 * (prohibited by N4: no custom formats, no parallel pipelines).
 */
data class AdaptationTarget(
    val store: String,
    val fileName: String,
) {
    init {
        require(store == AdaptationEntry.STORE_MEMORY || store == AdaptationEntry.STORE_SKILL)
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
        } catch (error: Exception) {
            DebugLog.e(TAG, "undo failed for ${entry.targetFile}", error)
            return false
        }
        log.updateStatus(entry.id, status)
        return true
    }

    // ── store adapters ──────────────────────────────────────────────────────

    private fun read(target: AdaptationTarget): String = when (target.store) {
        AdaptationEntry.STORE_MEMORY -> memoryManager.readFile(target.fileName)
        else -> skillManager.readFile(target.fileName)
    }

    private fun write(target: AdaptationTarget, content: String, existedBefore: Boolean) {
        when (target.store) {
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
        when (target.store) {
            AdaptationEntry.STORE_MEMORY -> memoryManager.deleteFile(target.fileName)
            else -> skillManager.deleteFile(target.fileName)
        }
    }

    private companion object {
        const val TAG = "AutopilotApplier"
    }
}
