package com.wzvideni.multiview.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderStartupGuardTest {
    @Test
    fun stopsAfterThreeInitializedAttemptsWithoutFirstFrame() {
        val guard = DecoderStartupGuard()
        repeat(3) { attempt ->
            guard.beginAttempt("rtsp://camera", true)
            guard.onDecoderInitialized("c2.rk.avc.decoder")
            assertEquals(attempt == 2, guard.onAttemptFailure())
        }
        assertEquals(3, guard.noFrameFailures)
    }

    @Test
    fun connectionFailuresWithoutDecoderDoNotExhaustLimit() {
        val guard = DecoderStartupGuard()
        repeat(10) {
            guard.beginAttempt("rtsp://camera", true)
            assertFalse(guard.onAttemptFailure())
        }
        assertEquals(0, guard.noFrameFailures)
    }

    @Test
    fun firstFrameRestoresRetryBudgetAndLaterPlaybackFailureDoesNotCount() {
        val guard = DecoderStartupGuard()
        repeat(2) {
            guard.beginAttempt("rtsp://camera", true)
            guard.onDecoderInitialized("c2.rk.avc.decoder")
            guard.onAttemptFailure()
        }
        guard.beginAttempt("rtsp://camera", true)
        guard.onDecoderInitialized("c2.rk.avc.decoder")
        guard.onFirstFrame()
        assertFalse(guard.onAttemptFailure())
        assertEquals(0, guard.noFrameFailures)
        assertTrue(guard.hasRenderedFirstFrame)
        guard.beginAttempt("rtsp://camera", true)
        guard.onDecoderInitialized("c2.rk.avc.decoder")
        assertFalse(guard.onAttemptFailure())
        assertEquals(1, guard.noFrameFailures)
    }

    @Test
    fun countsOneFailurePerAttemptEvenWhenMultipleCallbacksArrive() {
        val guard = DecoderStartupGuard()
        guard.beginAttempt("rtsp://camera", true)
        guard.onDecoderInitialized("c2.rk.avc.decoder")
        repeat(4) { assertFalse(guard.onAttemptFailure()) }
        assertEquals(1, guard.noFrameFailures)
    }

    @Test
    fun networkOnlyAttemptDoesNotResetPreviousDecoderFailures() {
        val guard = DecoderStartupGuard()
        guard.beginAttempt("rtsp://camera", true)
        guard.onDecoderInitialized("c2.rk.avc.decoder")
        guard.onAttemptFailure()
        guard.beginAttempt("rtsp://camera", true)
        assertFalse(guard.onAttemptFailure())
        assertEquals(1, guard.noFrameFailures)
        guard.beginAttempt("rtsp://camera", true)
        guard.onDecoderInitialized("c2.rk.avc.decoder")
        assertFalse(guard.onAttemptFailure())
        assertEquals(2, guard.noFrameFailures)
    }

    @Test
    fun changingUrlResetsFailureBudget() {
        val guard = blockedGuard()
        guard.beginAttempt("rtsp://other-camera", true)
        assertEquals(0, guard.noFrameFailures)
        assertEquals(null, guard.decoderName)
        assertFalse(guard.hasRenderedFirstFrame)
        guard.onDecoderInitialized("c2.rk.avc.decoder")
        assertFalse(guard.onAttemptFailure())
    }

    @Test
    fun changingMuteConfigurationResetsFailureBudget() {
        val guard = blockedGuard()
        guard.beginAttempt("rtsp://camera", false)
        guard.onDecoderInitialized("c2.rk.avc.decoder")
        assertFalse(guard.onAttemptFailure())
        assertEquals(1, guard.noFrameFailures)
    }

    private fun blockedGuard() = DecoderStartupGuard().apply {
        repeat(3) {
            beginAttempt("rtsp://camera", true)
            onDecoderInitialized("c2.rk.avc.decoder")
            onAttemptFailure()
        }
    }
}
