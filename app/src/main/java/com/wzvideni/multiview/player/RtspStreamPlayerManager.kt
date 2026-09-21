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
import com.wzvideni.multiview.model.StreamStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * RTSP 监控流多路并发播放管理器
 * 基于 Media3 ExoPlayer RTSP 实现硬件解码与 TCP 稳定传输
 *
 * 稳定性增强特性：
 * 1. 自动重连与指数退避：网络抖动或服务端 on-demand 启动延迟时不中断死锁，自动重试拉流；
 * 2. 平滑错峰启动 (Staggered Startup)：避免多路同时并发启动导致 CPU 满载及服务端 FFmpeg 瞬时压力；
 * 3. 增强超时配置：允许 on-demand 生成流有充足的准备时间；
 * 4. 严谨的生命周期与 Surface 释放管理。
 */
class RtspStreamPlayerManager(private val context: Context) {

    companion object {
        private const val TAG = "RtspPlayerManager"
        private const val MAX_CONCURRENT_PLAYERS = 16
        private const val STAGGER_DELAY_MS = 100L // 错峰启动延迟步长 (毫秒)
        private const val RTSP_TIMEOUT_MS = 12000L // RTSP 握手与媒体准备超时时间 (毫秒)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var frameFeeder: IStreamFrameFeeder? = null
    private val activePlayers = ConcurrentHashMap<Int, ExoPlayer>()
    private val activeUrls = ConcurrentHashMap<Int, String>()
    private val pendingSurfaces = ConcurrentHashMap<Int, Surface>()

    // 重试机制与平滑启动任务管理
    private val retryCounts = ConcurrentHashMap<Int, Int>()
    private val retryTasks = ConcurrentHashMap<Int, Runnable>()
    private val pendingStartTasks = ConcurrentHashMap<Int, Runnable>()

    private var lastChannels: List<StreamChannel>? = null
    private var lastVisibleIndices: List<Int> = emptyList()
    private var lastIsFullscreen: Boolean = false
    private var lastFullscreenChannelIndex: Int = -1

    var onChannelStatusChanged: ((channelIndex: Int, status: StreamStatus) -> Unit)? = null

    /**
     * 绑定渲染器的 FrameFeeder
     */
    fun bindFeeder(feeder: IStreamFrameFeeder) {
        this.frameFeeder = feeder
        feeder.setOnSurfaceAvailableListener { channelIndex, surface ->
            mainHandler.post {
                Log.d(TAG, "Channel $channelIndex surface available")
                pendingSurfaces[channelIndex] = surface
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
     * 根据当前可见槽位与全屏状态动态调度拉流
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

        if (frameFeeder == null) return

        // 确定当前应该播放的通道索引集合
        val targetIndices = if (isFullscreen && fullscreenChannelIndex >= 0) {
            listOf(fullscreenChannelIndex)
        } else {
            visibleIndices.filter { it in channels.indices }.take(MAX_CONCURRENT_PLAYERS)
        }

        val targetSet = targetIndices.toSet()

        // 1. 停止不需要的通道与未执行的任务
        val toStop = activePlayers.keys.filter { it !in targetSet }
        for (idx in toStop) {
            stopPlayer(idx)
        }
        for ((idx, task) in pendingStartTasks) {
            if (idx !in targetSet) {
                mainHandler.removeCallbacks(task)
                pendingStartTasks.remove(idx)
            }
        }
        for ((idx, task) in retryTasks) {
            if (idx !in targetSet) {
                mainHandler.removeCallbacks(task)
                retryTasks.remove(idx)
                retryCounts.remove(idx)
            }
        }

        // 2. 启动或更新需要的通道（使用错峰平滑启动机制）
        var staggerIndex = 0
        for (idx in targetIndices) {
            val channel = channels.getOrNull(idx) ?: continue
            val url = if (isFullscreen && idx == fullscreenChannelIndex) {
                if (channel.isMainStream && channel.mainRtspUrl.isNotBlank()) channel.mainRtspUrl else channel.rtspUrl
            } else {
                if (channel.subRtspUrl.isNotBlank()) channel.subRtspUrl else channel.rtspUrl
            }

            val currentUrl = activeUrls[idx]
            val currentPlayer = activePlayers[idx]

            if (currentPlayer == null || currentUrl != url) {
                pendingStartTasks.remove(idx)?.let { mainHandler.removeCallbacks(it) }

                if (staggerIndex == 0) {
                    startPlayer(idx, url, channel.isMuted)
                } else {
                    val delay = staggerIndex * STAGGER_DELAY_MS
                    val task = Runnable {
                        pendingStartTasks.remove(idx)
                        if (isChannelStillActive(idx)) {
                            startPlayer(idx, url, channel.isMuted)
                        }
                    }
                    pendingStartTasks[idx] = task
                    mainHandler.postDelayed(task, delay)
                }
                staggerIndex++
            } else {
                val targetVolume = if (channel.isMuted) 0f else 1f
                if (currentPlayer.volume != targetVolume) {
                    currentPlayer.volume = targetVolume
                }
            }
        }
    }

    private fun isChannelStillActive(channelIndex: Int): Boolean {
        return if (lastIsFullscreen && lastFullscreenChannelIndex >= 0) {
            channelIndex == lastFullscreenChannelIndex
        } else {
            channelIndex in lastVisibleIndices
        }
    }

    @OptIn(UnstableApi::class)
    private fun startPlayer(channelIndex: Int, rtspUrl: String, isMuted: Boolean) {
        if (rtspUrl.isBlank()) return

        // 取消重试定时器，避免多次并发启动
        retryTasks.remove(channelIndex)?.let { mainHandler.removeCallbacks(it) }

        // 停止并清理该通道已有播放器
        internalStopPlayer(channelIndex)

        val surface = frameFeeder?.getChannelSurface(channelIndex) ?: pendingSurfaces[channelIndex]
        Log.i(TAG, "Starting RTSP Player for ch $channelIndex -> $rtspUrl (hasSurface=${surface != null})")

        // 标记为连接中
        onChannelStatusChanged?.invoke(channelIndex, StreamStatus.CONNECTING)

        try {
            val mediaSource = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setDebugLoggingEnabled(false)
                .setTimeoutMs(RTSP_TIMEOUT_MS)
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
                            Player.STATE_READY -> {
                                Log.i(TAG, "Ch $channelIndex RTSP Stream PLAYING")
                                retryCounts.remove(channelIndex)
                                onChannelStatusChanged?.invoke(channelIndex, StreamStatus.PLAYING)
                            }
                            Player.STATE_BUFFERING -> {
                                Log.d(TAG, "Ch $channelIndex RTSP Stream BUFFERING")
                                onChannelStatusChanged?.invoke(channelIndex, StreamStatus.CONNECTING)
                            }
                            Player.STATE_ENDED -> {
                                Log.d(TAG, "Ch $channelIndex RTSP Stream ENDED")
                                onChannelStatusChanged?.invoke(channelIndex, StreamStatus.IDLE)
                            }
                            Player.STATE_IDLE -> {
                                Log.d(TAG, "Ch $channelIndex RTSP Stream IDLE")
                                onChannelStatusChanged?.invoke(channelIndex, StreamStatus.IDLE)
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val attempt = (retryCounts[channelIndex] ?: 0) + 1
                        retryCounts[channelIndex] = attempt
                        Log.w(TAG, "Ch $channelIndex RTSP Player error: ${error.message} (attempt #$attempt)", error)

                        // 释放出错的实例
                        internalStopPlayer(channelIndex)
                        onChannelStatusChanged?.invoke(channelIndex, StreamStatus.CONNECTING)

                        // 自动指数退避重试 (1.5s, 3s, 4.5s...)
                        val delayMs = (1500L * attempt).coerceIn(1500L, 5000L)
                        val retryTask = Runnable {
                            retryTasks.remove(channelIndex)
                            if (isChannelStillActive(channelIndex)) {
                                Log.i(TAG, "Auto-retrying Ch $channelIndex (attempt #$attempt) -> $rtspUrl")
                                startPlayer(channelIndex, rtspUrl, isMuted)
                            }
                        }
                        retryTasks[channelIndex] = retryTask
                        mainHandler.postDelayed(retryTask, delayMs)
                    }
                })
                prepare()
                playWhenReady = true
            }

            activePlayers[channelIndex] = player
            activeUrls[channelIndex] = rtspUrl
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create player for ch $channelIndex: ${e.message}", e)
            onChannelStatusChanged?.invoke(channelIndex, StreamStatus.ERROR)
        }
    }

    /**
     * 仅停止播放器内部实例，不清理重试上下文
     */
    private fun internalStopPlayer(channelIndex: Int) {
        val player = activePlayers.remove(channelIndex)
        activeUrls.remove(channelIndex)
        frameFeeder?.clearChannel(channelIndex)
        if (player != null) {
            try {
                player.stop()
                player.clearVideoSurface()
                player.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing player for ch $channelIndex: ${e.message}")
            }
        }
    }

    /**
     * 主动停止单个通道（外部调用时取消所有待定重试）
     */
    fun stopPlayer(channelIndex: Int) {
        retryTasks.remove(channelIndex)?.let { mainHandler.removeCallbacks(it) }
        retryCounts.remove(channelIndex)
        pendingStartTasks.remove(channelIndex)?.let { mainHandler.removeCallbacks(it) }
        internalStopPlayer(channelIndex)
    }

    /**
     * 释放所有播放器与异步任务（页面退出或销毁）
     */
    fun releaseAll() {
        for ((_, task) in retryTasks) {
            mainHandler.removeCallbacks(task)
        }
        retryTasks.clear()
        retryCounts.clear()

        for ((_, task) in pendingStartTasks) {
            mainHandler.removeCallbacks(task)
        }
        pendingStartTasks.clear()

        for ((idx, player) in activePlayers) {
            try {
                player.stop()
                player.clearVideoSurface()
                player.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing player for ch $idx: ${e.message}")
            }
        }
        activePlayers.clear()
        activeUrls.clear()
        pendingSurfaces.clear()
        frameFeeder = null
    }
}
