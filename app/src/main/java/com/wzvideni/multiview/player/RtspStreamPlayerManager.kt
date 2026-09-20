package com.wzvideni.multiview.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.wzvideni.multiview.gl.IStreamFrameFeeder
import com.wzvideni.multiview.model.StreamChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * RTSP 多路并发流播放管理器
 *
 * 核心特性：
 * 1. 管理 Media3 ExoPlayer RTSP 播放器实例；
 * 2. 自动对接 OpenGL ES 渲染器的 Surface 硬件输出端口；
 * 3. 智能调度：单路全屏时聚焦高清主码流、分屏时按需拉取辅码流，防范低端手机硬件解码器超限崩溃；
 * 4. 强制启用 TCP 传输（RTP over RTSP Interleaved），避免局域网丢包与花屏。
 */
class RtspStreamPlayerManager(private val context: Context) {

    companion object {
        private const val TAG = "RtspPlayerManager"
        // 针对低端机型（如骁龙 400 系列），限制后台同时并发解码的硬件播放器上限（防 MediaCodec 资源耗尽）
        private const val MAX_CONCURRENT_PLAYERS = 4
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var frameFeeder: IStreamFrameFeeder? = null
    private val activePlayers = ConcurrentHashMap<Int, ExoPlayer>()
    private val activeUrls = ConcurrentHashMap<Int, String>()
    private val pendingSurfaces = ConcurrentHashMap<Int, Surface>()

    private var lastChannels: List<StreamChannel>? = null
    private var lastVisibleIndices: List<Int> = emptyList()
    private var lastIsFullscreen: Boolean = false
    private var lastFullscreenChannelIndex: Int = -1

    /**
     * 绑定渲染器的 FrameFeeder
     */
    fun bindFeeder(feeder: IStreamFrameFeeder) {
        this.frameFeeder = feeder
        feeder.setOnSurfaceAvailableListener { channelIndex, surface ->
            mainHandler.post {
                Log.d(TAG, "Channel $channelIndex surface available")
                pendingSurfaces[channelIndex] = surface
                // 如果已有该通道的播放器且未设置过 surface，则挂载
                activePlayers[channelIndex]?.setVideoSurface(surface)
            }
        }

        // 如果之前已有流列表，立即调度
        val currentChannels = lastChannels
        if (currentChannels != null) {
            updateStreams(currentChannels, lastVisibleIndices, lastIsFullscreen, lastFullscreenChannelIndex)
        }
    }

    /**
     * 根据当前布局与通道状态智能调度播放器
     */
    fun updateStreams(
        channels: List<StreamChannel>,
        visibleIndices: List<Int>,
        isFullscreen: Boolean,
        fullscreenChannelIndex: Int
    ) {
        this.lastChannels = channels
        this.lastVisibleIndices = visibleIndices
        this.lastIsFullscreen = isFullscreen
        this.lastFullscreenChannelIndex = fullscreenChannelIndex

        val feeder = frameFeeder ?: return

        // 确定当前应该播放的通道索引集合
        val targetIndices = if (isFullscreen && fullscreenChannelIndex >= 0) {
            listOf(fullscreenChannelIndex)
        } else {
            // 优先播放当前选中的通道及其相邻可见通道（最多限制 MAX_CONCURRENT_PLAYERS 路）
            visibleIndices.take(MAX_CONCURRENT_PLAYERS)
        }

        val targetSet = targetIndices.toSet()

        // 1. 停止不再需要的通道播放器（节省带宽与硬件解码器）
        val toStop = activePlayers.keys.filter { it !in targetSet }
        for (idx in toStop) {
            stopPlayer(idx)
        }

        // 2. 启动或更新需要播放的通道
        for (idx in targetIndices) {
            val channel = channels.getOrNull(idx) ?: continue
            val url = if (isFullscreen && idx == fullscreenChannelIndex) {
                if (channel.isMainStream) channel.mainRtspUrl else channel.subRtspUrl
            } else {
                channel.subRtspUrl
            }

            val currentUrl = activeUrls[idx]
            val currentPlayer = activePlayers[idx]

            if (currentPlayer == null || currentUrl != url) {
                startPlayer(idx, url, channel.isMuted)
            } else {
                // 仅更新伴音状态
                val targetVolume = if (channel.isMuted) 0f else 1f
                if (currentPlayer.volume != targetVolume) {
                    currentPlayer.volume = targetVolume
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun startPlayer(channelIndex: Int, rtspUrl: String, isMuted: Boolean) {
        if (rtspUrl.isBlank()) return
        // 先释放旧的
        stopPlayer(channelIndex)

        val surface = frameFeeder?.getChannelSurface(channelIndex) ?: pendingSurfaces[channelIndex]
        Log.i(TAG, "Starting RTSP Player for Channel $channelIndex -> $rtspUrl (hasSurface=${surface != null})")

        try {
            // 构建强制 TCP 传输的 RTSP 媒体源（工业安防标准，极低丢包与高抗干扰）
            val mediaSource = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setDebugLoggingEnabled(true)
                .createMediaSource(MediaItem.fromUri(rtspUrl))

            val player = ExoPlayer.Builder(context).build().apply {
                this.volume = if (isMuted) 0f else 1f
                setMediaSource(mediaSource)
                if (surface != null && surface.isValid) {
                    setVideoSurface(surface)
                }
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> Log.i(TAG, "Channel $channelIndex RTSP Stream PLAYING")
                            Player.STATE_BUFFERING -> Log.d(TAG, "Channel $channelIndex RTSP Stream BUFFERING")
                            Player.STATE_ENDED -> Log.d(TAG, "Channel $channelIndex RTSP Stream ENDED")
                            Player.STATE_IDLE -> Log.d(TAG, "Channel $channelIndex RTSP Stream IDLE")
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(TAG, "Channel $channelIndex RTSP Player Error: ${error.errorCodeName} - ${error.message}", error)
                    }
                })
                prepare()
                playWhenReady = true
            }

            activePlayers[channelIndex] = player
            activeUrls[channelIndex] = rtspUrl
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create player for channel $channelIndex: ${e.message}", e)
        }
    }

    /**
     * 停止单个通道的播放器
     */
    fun stopPlayer(channelIndex: Int) {
        val player = activePlayers.remove(channelIndex)
        activeUrls.remove(channelIndex)
        frameFeeder?.clearChannel(channelIndex)
        if (player != null) {
            Log.d(TAG, "Stopping RTSP Player for channel $channelIndex")
            try {
                player.stop()
                player.clearVideoSurface()
                player.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing player for channel $channelIndex: ${e.message}")
            }
        }
    }

    /**
     * 释放所有播放器资源（在 Activity 销毁或页面退出时调用）
     */
    fun releaseAll() {
        Log.i(TAG, "Releasing all RTSP players (${activePlayers.size})")
        for ((idx, player) in activePlayers) {
            try {
                player.stop()
                player.clearVideoSurface()
                player.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing player for channel $idx: ${e.message}")
            }
        }
        activePlayers.clear()
        activeUrls.clear()
        pendingSurfaces.clear()
        frameFeeder = null
    }
}
