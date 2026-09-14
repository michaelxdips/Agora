package com.newoether.agora.autopilot

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The trigger contract: a conversation is reflected exactly when it leaves Agora's public
 * `generatingConversationIds` set, and never while it is still generating.
 *
 * An unconfined test dispatcher runs each emission inline, so every assertion below is about the
 * trigger decision itself and not about scheduler timing.
 */
class AutopilotTriggerObserverTest {

    /** `DebugLog` delegates to `android.util.Log`, which is a stub in JVM tests. */
    @Before
    fun silenceAndroidLog() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0
    }

    @Test
    fun aConversationIsScheduledExactlyWhenItGoesIdle() {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val generating = MutableStateFlow<Set<String>>(emptySet())
            val scheduled = mutableListOf<String>()
            AutopilotTriggerObserver(context(), scope).start(
                generatingConversationIds = generating,
                schedule = { _, id -> scheduled += id },
            )

            generating.value = setOf("conv-1")
            assertTrue("must not reflect while generating", scheduled.isEmpty())

            generating.value = emptySet()
            assertEquals(listOf("conv-1"), scheduled)

            // A repeated identical emission must not schedule twice.
            generating.value = emptySet()
            assertEquals(listOf("conv-1"), scheduled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun twoConversationsGoingIdleTogetherAreBothScheduled() {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val generating = MutableStateFlow<Set<String>>(emptySet())
            val scheduled = mutableListOf<String>()
            AutopilotTriggerObserver(context(), scope).start(
                generatingConversationIds = generating,
                schedule = { _, id -> scheduled += id },
            )

            generating.value = setOf("a", "b")
            assertTrue(scheduled.isEmpty())

            generating.value = setOf("b")
            assertEquals(listOf("a"), scheduled)

            generating.value = emptySet()
            assertEquals(listOf("a", "b"), scheduled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun anIdleConversationThatNeverStartedSchedulesNothing() {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val generating = MutableStateFlow<Set<String>>(emptySet())
            val scheduled = mutableListOf<String>()
            AutopilotTriggerObserver(context(), scope).start(
                generatingConversationIds = generating,
                schedule = { _, id -> scheduled += id },
            )

            assertTrue(scheduled.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    private fun context(): Context = mockk { every { applicationContext } returns this }
}
