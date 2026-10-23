package com.wzvideni.multiview.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotFoundRetryGuardTest {
    @Test
    fun suspendsAutomaticRetryAfterFiveNotFoundRetries() {
        val guard = NotFoundRetryGuard(maxAutomaticRetries = 5)

        repeat(5) { assertTrue(guard.shouldAutoRetryAfterFailure(isNotFound = true)) }
        assertFalse(guard.shouldAutoRetryAfterFailure(isNotFound = true))
        assertEquals(6, guard.notFoundFailureCount)
    }

    @Test
    fun ordinaryNetworkFailuresDoNotConsumeNotFoundRetryBudget() {
        val guard = NotFoundRetryGuard(maxAutomaticRetries = 5)

        repeat(20) { assertTrue(guard.shouldAutoRetryAfterFailure(isNotFound = false)) }
        assertEquals(0, guard.notFoundFailureCount)
        assertTrue(guard.shouldAutoRetryAfterFailure(isNotFound = true))
    }

    @Test
    fun manualRetryCanResetSuspendedNotFoundBudget() {
        val guard = NotFoundRetryGuard(maxAutomaticRetries = 5)
        repeat(6) { guard.shouldAutoRetryAfterFailure(isNotFound = true) }
        assertFalse(guard.shouldAutoRetryAfterFailure(isNotFound = true))

        guard.reset()

        assertEquals(0, guard.notFoundFailureCount)
        assertTrue(guard.shouldAutoRetryAfterFailure(isNotFound = true))
    }
}
