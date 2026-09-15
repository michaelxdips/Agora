package com.newoether.agora.autopilot

import com.newoether.agora.data.MemoryManager
import com.newoether.agora.util.DebugLog

/**
 * Result of a persona write, for the UI status read-back and for tests.
 *
 * [storeContent] is the active-memory text **read back from the store** after the write, not the
 * value we think we wrote: P4 requires the status to come from the channel itself.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
data class PersonaState(
    val enabled: Map<String, Boolean>,
    val storeContent: String,
) {
    fun isActive(id: String): Boolean = PersonaStore.hasBlock(storeContent, id)

    /** True when the store carries no persona marker at all — the "zero trace" check. */
    val storeIsClean: Boolean get() = !PersonaStore.hasAnyMarker(storeContent)

    /** Summed length of the persona blocks actually present — the honest input-token cost basis. */
    val injectedChars: Int get() = PersonaStore.blocks(storeContent).values.sumOf { it.length }
}

/**
 * Phase 6 P1 — writes personas into Agora's active-memory store, through the same snapshot-first
 * journal as every other adaptation (N5).
 *
 * Why this is the native channel and not a new prompt-assembly hook: `active_memory.md` is already
 * read by `MemoryManager.getActiveMemory()` and already injected into the resolved system prompt by
 * `GenerationRequestBuilder.resolvePromptTemplate`. Personas therefore reach the model with **zero
 * upstream touchpoints** (N14) — the exact requirement P1 states, and the reason a hand-rolled
 * system-prompt hook was rejected.
 *
 * There is exactly one write path ([setEnabled]), so toggle ON, toggle OFF, master-off strip and
 * startup reconciliation cannot diverge: each recomputes the desired active-memory text from
 * (current content minus persona blocks) + (blocks for the enabled set) and journals the change.
 */
class PersonaApplier(
    private val memoryManager: MemoryManager,
    private val log: AdaptationLogDao,
) {
    /** The active-memory file name, as Agora's own Memory UI shows it. */
    private val activeMemoryName = "active_memory.md"

    fun currentState(): PersonaState {
        val content = memoryManager.getActiveMemory()
        return PersonaState(
            enabled = PersonaStore.IDS.associateWith { PersonaStore.hasBlock(content, it) },
            storeContent = content,
        )
    }

    /**
     * Desired active-memory text for [enabled] personas, given the store's current content.
     *
     * Pure and public, so the desired-vs-actual comparison that drives reconciliation is testable on
     * the JVM without Android or Room.
     */
    fun desiredContent(
        current: String,
        enabled: Map<String, Boolean>,
        bodies: Map<String, String>,
    ): String {
        var text = PersonaStore.stripAll(current)
        PersonaStore.IDS.forEach { id ->
            val body = bodies[id]
            if (enabled[id] == true && !body.isNullOrBlank()) {
                text = PersonaStore.upsertBlock(text, id, body)
            }
        }
        return text
    }

    /**
     * Makes the store match [enabled] exactly, journaling one adaptation when it has to change.
     *
     * @param bodies persona id → block body (the vendored rule text); blank/missing ids are skipped.
     * @return the state **read back** from the store after the operation.
     */
    suspend fun setEnabled(
        enabled: Map<String, Boolean>,
        bodies: Map<String, String>,
        reason: String,
        timestamp: Long = System.currentTimeMillis(),
    ): PersonaState {
        val current = memoryManager.getActiveMemory()
        val desired = desiredContent(current, enabled, bodies)
        if (desired == current) return currentState()

        // Snapshot-first: the journal row carries the exact prior bytes, so toggle-off and Undo are
        // the same code path and both are byte-exact.
        val id = log.insert(
            AdaptationEntry(
                timestamp = timestamp,
                store = AdaptationEntry.STORE_ACTIVE_MEMORY,
                targetFile = activeMemoryName,
                beforeSnapshot = current,
                afterSnapshot = desired,
                reason = reason,
                sourceSessionId = null,
                status = AdaptationEntry.STATUS_APPLIED,
            )
        )
        try {
            memoryManager.updateActiveMemory(desired)
        } catch (error: Exception) {
            log.delete(id)
            DebugLog.e(TAG, "persona write rejected", error)
            throw error
        }
        return currentState()
    }

    /**
     * Enforces the invariants the toggles promise:
     *  * master autopilot OFF → every persona block removed (P1);
     *  * a persona whose stored text drifted from the vendored rule text is rewritten.
     *
     * Runs at startup, so a store left half-written self-heals without the user toggling anything.
     */
    suspend fun reconcile(
        masterEnabled: Boolean,
        enabled: Map<String, Boolean>,
        bodies: Map<String, String>,
        reason: String = "persona reconcile",
        timestamp: Long = System.currentTimeMillis(),
    ): PersonaState {
        val effective = if (masterEnabled) enabled else PersonaStore.IDS.associateWith { false }
        val current = memoryManager.getActiveMemory()
        if (desiredContent(current, effective, bodies) == current) return currentState()
        return setEnabled(effective, bodies, reason = reason, timestamp = timestamp)
    }

    private companion object {
        const val TAG = "AutopilotPersonas"
    }
}
