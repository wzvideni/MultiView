package com.wzvideni.multiview.player

internal class NotFoundRetryGuard(private val maxAutomaticRetries: Int) {
    var notFoundFailureCount: Int = 0
        private set

    fun shouldAutoRetryAfterFailure(isNotFound: Boolean): Boolean {
        if (!isNotFound) return true
        notFoundFailureCount++
        return notFoundFailureCount <= maxAutomaticRetries
    }

    fun reset() {
        notFoundFailureCount = 0
    }
}
