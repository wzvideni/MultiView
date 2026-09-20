package com.wzvideni.multiview.model

/**
 * 单路视频流通道模型
 */
data class StreamChannel(
    val id: String,
    val channelIndex: Int,
    val name: String,
    val rtspUrl: String = "",
    val subRtspUrl: String = "",
    val mainRtspUrl: String = "",
    val isMainStream: Boolean = false,
    val status: StreamStatus = StreamStatus.IDLE,
    val resolution: String = "640x360",
    val fps: Int = 15,
    val bitrateKbps: Int = 512,
    val isAudioEnabled: Boolean = false,
    val isMuted: Boolean = true,
    val isSelected: Boolean = false,
    val customData: Any? = null
)
