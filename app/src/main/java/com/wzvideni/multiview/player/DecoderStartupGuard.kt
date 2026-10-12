package com.wzvideni.multiview.player

internal class DecoderStartupGuard(private val failureLimit: Int = 3) {
    private var config: Pair<String, Boolean>? = null
    private var failureRecorded = false
    var decoderName: String? = null
        private set
    var hasRenderedFirstFrame = false
        private set
    var noFrameFailures = 0
        private set

    fun beginAttempt(url: String, isMuted: Boolean) {
        val nextConfig = url to isMuted
        if (config != nextConfig) noFrameFailures = 0
        config = nextConfig
        decoderName = null
        hasRenderedFirstFrame = false
        failureRecorded = false
    }

    fun onDecoderInitialized(name: String) {
        decoderName = name
    }

    fun onFirstFrame() {
        hasRenderedFirstFrame = true
        noFrameFailures = 0
    }

    fun onAttemptFailure(): Boolean {
        // Retain the count across network-only retries: they do not recover a failed decoder startup.
        if (!failureRecorded && decoderName != null && !hasRenderedFirstFrame) {
            noFrameFailures++
            failureRecorded = true
        }
        return noFrameFailures >= failureLimit
    }
}
