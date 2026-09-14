package com.newoether.agora.autopilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 presentation contract: status chips read correctly, only reversible entries offer Undo,
 * and the diff names every changed line.
 */
class AdaptationHistoryPresenterTest {

    @Test
    fun statusLabelsCoverEveryStatusTheJournalCanStore() {
        assertEquals("Applied", AdaptationHistoryPresenter.statusLabel(AdaptationEntry.STATUS_APPLIED))
        assertEquals(
            "Auto rolled back",
            AdaptationHistoryPresenter.statusLabel(AdaptationEntry.STATUS_AUTO_ROLLED_BACK),
        )
        assertEquals("Undone", AdaptationHistoryPresenter.statusLabel(AdaptationEntry.STATUS_USER_ROLLED_BACK))
        assertEquals(
            "Needs revision",
            AdaptationHistoryPresenter.statusLabel(AdaptationEntry.STATUS_NEEDS_REVISION),
        )
        AdaptationEntry.ALL_STATUSES.forEach { status ->
            assertTrue(
                "every stored status must have a label",
                AdaptationHistoryPresenter.statusLabel(status) != status,
            )
        }
    }

    @Test
    fun onlyEntriesThatStillHoldContentOfferUndo() {
        assertTrue(AdaptationHistoryPresenter.canUndo(entry(AdaptationEntry.STATUS_APPLIED)))
        assertTrue(AdaptationHistoryPresenter.canUndo(entry(AdaptationEntry.STATUS_NEEDS_REVISION)))
        assertFalse(AdaptationHistoryPresenter.canUndo(entry(AdaptationEntry.STATUS_USER_ROLLED_BACK)))
        assertFalse(AdaptationHistoryPresenter.canUndo(entry(AdaptationEntry.STATUS_AUTO_ROLLED_BACK)))
    }

    @Test
    fun theDiffNamesRemovedAndAddedLines() {
        val diff = AdaptationHistoryPresenter.diff(
            entry(
                status = AdaptationEntry.STATUS_APPLIED,
                before = "- old fact\n- kept fact\n",
                after = "- kept fact\n- new fact\n",
            )
        )

        assertEquals(2, diff.size)
        assertEquals("- old fact", diff.first { !it.added }.text)
        assertEquals("- new fact", diff.first { it.added }.text)
    }

    @Test
    fun aNewlyCreatedFileShowsEveryLineAsAdded() {
        val diff = AdaptationHistoryPresenter.diff(
            entry(status = AdaptationEntry.STATUS_APPLIED, before = null, after = "- a\n- b\n")
        )

        assertEquals(2, diff.size)
        assertTrue(diff.all { it.added })
    }

    @Test
    fun theDiffIsBoundedSoOneHugeAdaptationCannotFreezeTheList() {
        val big = (1..500).joinToString("\n") { "- line $it" }
        val diff = AdaptationHistoryPresenter.diff(
            entry(status = AdaptationEntry.STATUS_APPLIED, before = null, after = big),
            maxLines = 10,
        )

        assertEquals(10, diff.size)
    }

    private fun entry(
        status: String,
        before: String? = "- before\n",
        after: String? = "- after\n",
    ) = AdaptationEntry(
        id = 1L,
        timestamp = 1_700_000_000_000L,
        store = AdaptationEntry.STORE_MEMORY,
        targetFile = "prefs",
        beforeSnapshot = before,
        afterSnapshot = after,
        reason = "update: test",
        sourceSessionId = "s1",
        status = status,
    )
}
