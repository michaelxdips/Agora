package com.newoether.agora.autopilot

/**
 * Pure presentation logic for Adaptation History: status chip copy and a bounded line diff.
 *
 * Kept free of Android types so the whole history projection is unit-testable on the JVM.
 */
object AdaptationHistoryPresenter {

    fun statusLabel(status: String): String = when (status) {
        AdaptationEntry.STATUS_APPLIED -> "Applied"
        AdaptationEntry.STATUS_AUTO_ROLLED_BACK -> "Auto rolled back"
        AdaptationEntry.STATUS_USER_ROLLED_BACK -> "Undone"
        AdaptationEntry.STATUS_NEEDS_REVISION -> "Needs revision"
        else -> status
    }

    /** Whether [entry] can still be undone from the history screen. */
    fun canUndo(entry: AdaptationEntry): Boolean =
        entry.status == AdaptationEntry.STATUS_APPLIED ||
            entry.status == AdaptationEntry.STATUS_NEEDS_REVISION

    /**
     * A line diff between the snapshots, bounded to [maxLines] per side.
     *
     * Lines are compared as an ordered set: removed lines first, then added lines. That is enough
     * for a review list and cannot be wrong about what changed.
     */
    fun diff(entry: AdaptationEntry, maxLines: Int = 40): List<DiffLine> {
        val before = entry.beforeSnapshot?.lines().orEmpty().filter { it.isNotBlank() }
        val after = entry.afterSnapshot?.lines().orEmpty().filter { it.isNotBlank() }
        val beforeSet = before.toSet()
        val afterSet = after.toSet()
        val removed = before.filter { it !in afterSet }.take(maxLines)
        val added = after.filter { it !in beforeSet }.take(maxLines)
        return removed.map { DiffLine(it, added = false) } + added.map { DiffLine(it, added = true) }
    }

    data class DiffLine(val text: String, val added: Boolean)
}
