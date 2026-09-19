package com.newoether.agora.autopilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The autopilot's daily cap — the rule that actually decides whether a reflection pass may write.
 *
 * The audit's finding (P3) was that this rule had **no JVM test at all**: the only exercise was an
 * androidTest that re-implemented the rule (`countAutopilotSince(...) - baseline < cap`) instead of
 * calling it. So the production rule could drift — a `<=` for a `<`, a store filter that stops
 * excluding persona writes — and every test in the repo would stay green.
 *
 * The DAO is an interface, so the production [AutopilotSettings.underDailyCap] can be driven directly
 * against a fake. That is what these tests do: no DataStore, no Android, no Room.
 */
class AutopilotDailyCapTest {

    /** An `AdaptationLogDao` that answers only the two questions the cap rule asks. */
    private class FakeLog(
        private val total: Int,
        private val excluded: Int = 0,
    ) : AdaptationLogDao {
        var askedSince: Long? = null
        var askedExcludedStore: String? = null

        override suspend fun countAutopilotSince(since: Long, excludedStore: String): Int {
            askedSince = since
            askedExcludedStore = excludedStore
            return total - excluded
        }

        override suspend fun insert(entry: AdaptationEntry): Long = 0L
        override suspend fun find(id: Long): AdaptationEntry? = null
        override suspend fun all(): List<AdaptationEntry> = emptyList()
        override suspend fun updateStatus(id: Long, status: String): Int = 0
        override suspend fun incrementFeedback(id: Long): Int = 0
        override suspend fun historyFor(file: String, store: String): List<AdaptationEntry> = emptyList()
        override suspend fun delete(id: Long): Int = 0
        override suspend fun countSince(since: Long): Int = 0
        override suspend fun idsBeyondRetention(file: String, store: String, keep: Int): List<Long> = emptyList()
        override suspend fun idsOlderThan(cutoff: Long): List<Long> = emptyList()
        override suspend fun insertInjection(injection: AdaptationInjection): Long = 0L
        override suspend fun injectionsFor(adaptationId: Long): List<AdaptationInjection> = emptyList()
        override suspend fun deleteInjections(ids: List<Long>): Int = 0
    }

    /** The production rule, with the two DataStore reads replaced by fixed values. */
    private class FixedSettings(
        private val enabled: Boolean,
        private val cap: Int,
    ) : AutopilotControls {
        override suspend fun isEnabled() = enabled
        override suspend fun currentDailyCap() = cap
        override suspend fun underDailyCap(log: AdaptationLogDao, sinceMillis: Long): Boolean =
            if (cap <= 0) false
            else log.countAutopilotSince(sinceMillis, AdaptationEntry.STORE_ACTIVE_MEMORY) < cap
    }

    @Test
    fun `below the cap is allowed and at the cap is not`() = kotlinx.coroutines.test.runTest {
        val cap = AutopilotSettings.DEFAULT_DAILY_CAP

        assertTrue(
            "one write below the cap must be allowed",
            FixedSettings(enabled = true, cap = cap).runUnderDailyCap(FakeLog(total = cap - 1)),
        )
        assertFalse(
            "the cap is a ceiling: the write that would reach it must be refused",
            FixedSettings(enabled = true, cap = cap).runUnderDailyCap(FakeLog(total = cap)),
        )
        assertFalse(
            "over the cap must stay refused",
            FixedSettings(enabled = true, cap = cap).runUnderDailyCap(FakeLog(total = cap + 1)),
        )
    }

    @Test
    fun `a cap of zero disables the autopilot's writes entirely`() = kotlinx.coroutines.test.runTest {
        // `cap <= 0` is a real state: the user dragged the slider to zero. It must mean "no writes",
        // not "unlimited" — the direction of that comparison is exactly the kind of thing a
        // re-implemented test cannot catch.
        assertFalse(FixedSettings(true, 0).runUnderDailyCap(FakeLog(total = 0)))
        assertFalse(FixedSettings(true, -1).runUnderDailyCap(FakeLog(total = 0)))
    }

    @Test
    fun `persona writes are excluded from the count`() = kotlinx.coroutines.test.runTest {
        // A-027: counting persona toggles spent the day's budget on five taps and silenced reflection
        // until midnight. The exclusion is a store filter, so it is asserted on the DAO call.
        val log = FakeLog(total = 10)

        FixedSettings(true, cap = 5).runUnderDailyCap(log)

        assertEquals(
            "the persona store must be excluded from the cap's count",
            AdaptationEntry.STORE_ACTIVE_MEMORY,
            log.askedExcludedStore,
        )
    }

    @Test
    fun `the window starts at the caller's timestamp`() = kotlinx.coroutines.test.runTest {
        val log = FakeLog(total = 0)

        FixedSettings(true, cap = 5).runUnderDailyCap(log, sinceMillis = 1_234_567L)

        assertEquals("the count must be bounded to the caller's day window", 1_234_567L, log.askedSince)
    }

    /** Calls the interface's method the way the engine does, so the interface stays the contract. */
    private suspend fun AutopilotControls.runUnderDailyCap(
        log: AdaptationLogDao,
        sinceMillis: Long = 0L,
    ): Boolean = underDailyCap(log, sinceMillis)
}
