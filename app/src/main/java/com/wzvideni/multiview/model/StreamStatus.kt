package com.wzvideni.multiview.model

/**
 * 视频流播放状态
 */
enum class StreamStatus(val label: String) {
    IDLE("空闲"),
    CONNECTING("连接中..."),
    PLAYING("播放中"),
    NO_SIGNAL("无信号"),
    ERROR("连接异常")
}
