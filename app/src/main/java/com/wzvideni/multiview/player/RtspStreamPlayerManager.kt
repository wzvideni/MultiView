package com.wzvideni.multiview.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
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
 * 4. 远端断开与断流自愈守护：
 *    - 监听流结束 (STATE_ENDED) 自动重连，防止对方断开后画面永久冻结；
 *    - 缓冲超时看门狗 (BUFFERING_TIMEOUT_MS)，防止流被动切断后卡死在缓冲状态；
 *    - 缓冲状态防抖 (2500ms)，避免摄像头正常 I 帧间隔导致 UI 频繁闪烁“连接中”；
 *    - 视频帧静止看门狗 (FRAME_STALL_TIMEOUT_MS)，检测无新帧输入时主动重建连接；
 * 5. 本地环回代理与 SDP 修复 (RtspLoopbackProxy)：
 *    - 补齐缺失的 a=fmtp，防止 ExoPlayer 因非标 SDP 抛出异常崩溃；
 *    - OPUS 音频 Ogg 头合成注入；
 * 6. 监控专用渲染器 (RtspRenderersFactory)：
 *    - 静音模式下使用 NoOpAudioRenderer 解耦主时钟，杜绝 AudioTrack 欠载导致的画面顿挫；
 * 7. 零起播等待 LoadControl (bufferForPlaybackMs = 0) 与断线保留最后一帧防黑屏闪烁。
 */
@OptIn(UnstableApi::class)
class RtspStreamPlayerManager(private val context: Context) {

    companion object {
        private const val TAG = "RtspPlayerManager"
        private const val MAX_CONCURRENT_PLAYERS = 16
        private const val STAGGER_DELAY_MS = 100L // 错峰启动延迟步长 (毫秒)
        private const val RTSP_TIMEOUT_MS = 12000L // RTSP 握手与媒体准备超时时间 (毫秒)
        private const val BUFFERING_TIMEOUT_MS = 10000L // 缓冲卡顿超时时间 (10秒)，给予网络抖动充足冗余
        private const val FRAME_STALL_TIMEOUT_MS = 15000L // 帧画面静止超时时间 (15秒)，适配低帧率与静态场景
        private const val WATCHDOG_INTERVAL_MS = 3000L // 看门狗巡检周期 (3秒)

        @Volatile
        private var loggerConfigured = false

        /**
         * 配置 Media3 日志拦截器：
         * 过滤 IP 摄像头静音跳帧 (DTX/VAD) 时触发的非致命 AudioSink$UnexpectedDiscontinuityException 刷屏日志
         */
        @Synchronized
        fun configureMedia3Logger() {
            if (loggerConfigured) return
            loggerConfigured = true
            val defaultLogger = androidx.media3.common.util.Log.Logger.DEFAULT
            androidx.media3.common.util.Log.setLogger(object : androidx.media3.common.util.Log.Logger {
                override fun d(tag: String, message: String, throwable: Throwable?) = defaultLogger.d(tag, message, throwable)
                override fun i(tag: String, message: String, throwable: Throwable?) = defaultLogger.i(tag, message, throwable)
                override fun w(tag: String, message: String, throwable: Throwable?) = defaultLogger.w(tag, message, throwable)
                override fun e(tag: String, message: String, throwable: Throwable?) {
                    if (tag == "MediaCodecAudioRenderer" && message.contains("UnexpectedDiscontinuityException")) {
                        // 该异常为摄像头静音跳帧时的自愈性时间戳重同步，底层已自动恢复，过滤该无害日志避免刷屏
                        return
                    }
                    defaultLogger.e(tag, message, throwable)
                }
            })
        }
    }

    init {
        configureMedia3Logger()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var frameFeeder: IStreamFrameFeeder? = null
    private val activePlayers = ConcurrentHashMap<Int, ExoPlayer>()
    private val activeUrls = ConcurrentHashMap<Int, String>()
    private val activeMutedStates = ConcurrentHashMap<Int, Boolean>()
    private val pendingSurfaces = ConcurrentHashMap<Int, Surface>()

    // 重试机制与平滑启动任务管理
    private val retryCounts = ConcurrentHashMap<Int, Int>()
    private val retryTasks = ConcurrentHashMap<Int, Runnable>()
    private val pendingStartTasks = ConcurrentHashMap<Int, Runnable>()

    // 缓冲超时与帧冻结看门狗
    private val bufferingTimeoutTasks = ConcurrentHashMap<Int, Runnable>()
    private val bufferingDebounceTasks = ConcurrentHashMap<Int, Runnable>()
    private val stallWatchdogTasks = ConcurrentHashMap<Int, Runnable>()
    private val lastFrameTimes = ConcurrentHashMap<Int, Long>()

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
        feeder.setOnFrameRenderedListener { channelIndex ->
            // 收到真实解码帧，刷新时间戳并清除缓冲卡顿超时与状态抖动防抖
            lastFrameTimes[channelIndex] = SystemClock.uptimeMillis()
            cancelBufferingTimeout(channelIndex)
            cancelBufferingDebounce(channelIndex)
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
            val currentMuted = activeMutedStates[idx]

            if (currentPlayer == null || currentUrl != url || currentMuted != channel.isMuted) {
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

    private fun getTargetUrlForChannel(channelIndex: Int): String {
        val channel = lastChannels?.getOrNull(channelIndex) ?: return ""
        return if (lastIsFullscreen && channelIndex == lastFullscreenChannelIndex) {
            if (channel.isMainStream && channel.mainRtspUrl.isNotBlank()) channel.mainRtspUrl else channel.rtspUrl
        } else {
            if (channel.subRtspUrl.isNotBlank()) channel.subRtspUrl else channel.rtspUrl
        }
    }

    private fun startBufferingTimeout(channelIndex: Int) {
        cancelBufferingTimeout(channelIndex)
        val task = Runnable {
            bufferingTimeoutTasks.remove(channelIndex)
            if (isChannelStillActive(channelIndex) && activePlayers.containsKey(channelIndex)) {
                Log.w(TAG, "Ch $channelIndex RTSP player buffering timeout (> $BUFFERING_TIMEOUT_MS ms), triggering self-healing reconnect")
                handleStreamFailure(channelIndex, "Buffering timeout")
            }
        }
        bufferingTimeoutTasks[channelIndex] = task
        mainHandler.postDelayed(task, BUFFERING_TIMEOUT_MS)
    }

    private fun cancelBufferingTimeout(channelIndex: Int) {
        bufferingTimeoutTasks.remove(channelIndex)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun startStallWatchdog(channelIndex: Int) {
        cancelStallWatchdog(channelIndex)
        val watchdog = object : Runnable {
            override fun run() {
                if (!isChannelStillActive(channelIndex) || !activePlayers.containsKey(channelIndex)) {
                    stallWatchdogTasks.remove(channelIndex)
                    return
                }
                val lastTime = lastFrameTimes[channelIndex]
                val now = SystemClock.uptimeMillis()
                if (lastTime != null && (now - lastTime) > FRAME_STALL_TIMEOUT_MS) {
                    stallWatchdogTasks.remove(channelIndex)
                    Log.w(TAG, "Ch $channelIndex RTSP frame stall detected (> $FRAME_STALL_TIMEOUT_MS ms without frames), restarting...")
                    handleStreamFailure(channelIndex, "Frame stall watchdog timeout")
                    return
                }
                mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
        stallWatchdogTasks[channelIndex] = watchdog
        mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun cancelStallWatchdog(channelIndex: Int) {
        stallWatchdogTasks.remove(channelIndex)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun startBufferingDebounce(channelIndex: Int) {
        cancelBufferingDebounce(channelIndex)
        val task = Runnable {
            bufferingDebounceTasks.remove(channelIndex)
            if (isChannelStillActive(channelIndex) && activePlayers.containsKey(channelIndex)) {
                onChannelStatusChanged?.invoke(channelIndex, StreamStatus.CONNECTING)
            }
        }
        bufferingDebounceTasks[channelIndex] = task
        mainHandler.postDelayed(task, 2500L) // 2500ms 抖动防抖：应对摄像头I帧间隔(通常2秒)与短时网络抖动，避免UI频繁闪烁"连接中"
    }

    private fun cancelBufferingDebounce(channelIndex: Int) {
        bufferingDebounceTasks.remove(channelIndex)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun cancelAllTimeouts(channelIndex: Int) {
        cancelBufferingDebounce(channelIndex)
        cancelBufferingTimeout(channelIndex)
        cancelStallWatchdog(channelIndex)
    }

    /**
     * 统一的流异常与断连自愈重试机制：
     * 涵盖对方断流 (STATE_ENDED)、拉流错误 (onPlayerError)、缓冲卡死 (buffering timeout)、无新帧假死 (stall watchdog)。
     * 立即释放异常实例、保留最后一帧画面在视口上（clearFrame = false 避免黑屏闪烁），按指数退避策略自动重连。
     */
    private fun handleStreamFailure(channelIndex: Int, reason: String) {
        if (!isChannelStillActive(channelIndex)) return

        // 避免重复触发正在排队的重试任务
        if (retryTasks.containsKey(channelIndex)) return

        val attempt = (retryCounts[channelIndex] ?: 0) + 1
        retryCounts[channelIndex] = attempt

        val url = activeUrls[channelIndex]?.takeIf { it.isNotBlank() } ?: getTargetUrlForChannel(channelIndex)
        val channel = lastChannels?.getOrNull(channelIndex)
        val isMuted = channel?.isMuted ?: true

        Log.w(TAG, "Ch $channelIndex RTSP stream failure [$reason] (attempt #$attempt) -> $url")

        // 1. 取消正在运行的超时与看门狗任务
        cancelAllTimeouts(channelIndex)

        // 2. 停止并释放已有播放器，保留最后一帧画面（clearFrame = false 避免黑屏闪烁）
        internalStopPlayer(channelIndex, clearFrame = false)

        // 3. 标记为连接中状态，通知 UI 及时展示重连中提示
        onChannelStatusChanged?.invoke(channelIndex, StreamStatus.CONNECTING)

        if (url.isBlank()) {
            Log.w(TAG, "Cannot auto-retry ch $channelIndex: target RTSP URL is blank")
            return
        }

        // 4. 自动指数退避重试 (1.5s, 3s, 5s...)，持续尝试直至对方服务恢复
        val delayMs = (1500L * attempt).coerceIn(1500L, 5000L)
        val retryTask = Runnable {
            retryTasks.remove(channelIndex)
            if (isChannelStillActive(channelIndex)) {
                Log.i(TAG, "Auto-retrying Ch $channelIndex (attempt #$attempt) -> $url")
                startPlayer(channelIndex, url, isMuted)
            }
        }
        retryTasks[channelIndex] = retryTask
        mainHandler.postDelayed(retryTask, delayMs)
    }

    private fun startPlayer(channelIndex: Int, rtspUrl: String, isMuted: Boolean) {
        if (rtspUrl.isBlank()) return

        // 取消重试定时器与看门狗
        retryTasks.remove(channelIndex)?.let { mainHandler.removeCallbacks(it) }
        cancelAllTimeouts(channelIndex)
        lastFrameTimes.remove(channelIndex)

        // 停止并清理该通道已有播放器（保留上一帧画面，直至新流解码出首帧后无缝替换，杜绝黑屏闪烁）
        internalStopPlayer(channelIndex, clearFrame = false)

        val surface = frameFeeder?.getChannelSurface(channelIndex) ?: pendingSurfaces[channelIndex]
        Log.i(TAG, "Starting RTSP Player for ch $channelIndex -> $rtspUrl (hasSurface=${surface != null})")

        // 标记为连接中
        onChannelStatusChanged?.invoke(channelIndex, StreamStatus.CONNECTING)

        try {
            // 通过本地环回代理自动修复/补齐 SDP 中的 fmtp 配置，防止非标流导致 ExoPlayer 崩溃
            val playUrl = RtspLoopbackProxy.getProxyUrl(rtspUrl)
            val mediaSource = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setDebugLoggingEnabled(false)
                .setTimeoutMs(RTSP_TIMEOUT_MS)
                .createMediaSource(MediaItem.fromUri(playUrl))

            // 专为 RTSP 监控流优化的 LoadControl：
            // 1. 设置 bufferForPlaybackMs = 0, bufferForPlaybackAfterRebufferMs = 0，
            //    实现真正对齐 VLC 的起播与快速跳帧恢复策略（收到首个关键帧即可立即渲染，无需等待积攒 500ms 缓存）；
            // 2. 维持 1000ms~3000ms 的平滑抗抖动容量，关闭 backBuffer 节省多路内存。
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 1000,
                    /* maxBufferMs = */ 3000,
                    /* bufferForPlaybackMs = */ 0,
                    /* bufferForPlaybackAfterRebufferMs = */ 0
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(0, false)
                .build()

            val renderersFactory = RtspRenderersFactory(context, isMuted)

            val player = ExoPlayer.Builder(context, renderersFactory)
                .setLoadControl(loadControl)
                .build().apply {
                    this.volume = if (isMuted) 0f else 1f
                    setMediaSource(mediaSource)
                    if (surface != null && surface.isValid) {
                        setVideoSurface(surface)
                    }
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_READY -> {
                                    // 播放成功，清除重试计数、缓冲防抖与看门狗，记录首帧时间并启动静止看门狗
                                    Log.i(TAG, "Ch $channelIndex RTSP Stream PLAYING")
                                    retryCounts.remove(channelIndex)
                                    cancelBufferingDebounce(channelIndex)
                                    cancelBufferingTimeout(channelIndex)
                                    lastFrameTimes[channelIndex] = SystemClock.uptimeMillis()
                                    startStallWatchdog(channelIndex)
                                    onChannelStatusChanged?.invoke(channelIndex, StreamStatus.PLAYING)
                                }
                                Player.STATE_BUFFERING -> {
                                    Log.d(TAG, "Ch $channelIndex RTSP Stream BUFFERING")
                                    // 启动缓冲抖动防抖与超时看门狗：微小网络抖动快速恢复时不闪烁 UI
                                    startBufferingDebounce(channelIndex)
                                    startBufferingTimeout(channelIndex)
                                }
                                Player.STATE_ENDED -> {
                                    // 核心修复：直播 RTSP 流非正常结束（远端关闭/断开），立即自愈重连
                                    Log.w(TAG, "Ch $channelIndex RTSP stream ended unexpectedly, triggering self-healing reconnect")
                                    handleStreamFailure(channelIndex, "Stream ended (STATE_ENDED)")
                                }
                                Player.STATE_IDLE -> {
                                    Log.d(TAG, "Ch $channelIndex RTSP Stream IDLE")
                                    onChannelStatusChanged?.invoke(channelIndex, StreamStatus.IDLE)
                                }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            handleStreamFailure(channelIndex, "ExoPlayer error: ${error.message}")
                        }
                    })
                    prepare()
                    playWhenReady = true
                }

            activePlayers[channelIndex] = player
            activeUrls[channelIndex] = rtspUrl
            activeMutedStates[channelIndex] = isMuted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create player for ch $channelIndex: ${e.message}", e)
            onChannelStatusChanged?.invoke(channelIndex, StreamStatus.ERROR)
        }
    }

    /**
     * 停止播放器内部实例
     * @param clearFrame 是否清空 OpenGL 帧画面。
     *        重试/断连时设为 false：保留最后一帧画面在视口上，避免黑屏闪烁；
     *        主动关闭通道/退出页面时设为 true：彻底清空画面。
     */
    private fun internalStopPlayer(channelIndex: Int, clearFrame: Boolean = false) {
        val player = activePlayers.remove(channelIndex)
        activeUrls.remove(channelIndex)
        activeMutedStates.remove(channelIndex)
        if (clearFrame) {
            frameFeeder?.clearChannel(channelIndex)
        }
        if (player != null) {
            try {
                player.stop()
                if (clearFrame) {
                    player.clearVideoSurface()
                }
                player.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing player for ch $channelIndex: ${e.message}")
            }
        }
    }

    /**
     * 主动停止单个通道（外部调用时取消所有待定重试与看门狗，并清空画面）
     */
    fun stopPlayer(channelIndex: Int) {
        retryTasks.remove(channelIndex)?.let { mainHandler.removeCallbacks(it) }
        retryCounts.remove(channelIndex)
        pendingStartTasks.remove(channelIndex)?.let { mainHandler.removeCallbacks(it) }
        cancelAllTimeouts(channelIndex)
        lastFrameTimes.remove(channelIndex)
        internalStopPlayer(channelIndex, clearFrame = true)
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

        for ((_, task) in bufferingTimeoutTasks) {
            mainHandler.removeCallbacks(task)
        }
        bufferingTimeoutTasks.clear()

        for ((_, task) in bufferingDebounceTasks) {
            mainHandler.removeCallbacks(task)
        }
        bufferingDebounceTasks.clear()

        for ((_, task) in stallWatchdogTasks) {
            mainHandler.removeCallbacks(task)
        }
        stallWatchdogTasks.clear()
        lastFrameTimes.clear()

        for ((idx, player) in activePlayers) {
            try {
                player.stop()
                player.clearVideoSurface()
                player.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing player for ch $idx: ${e.message}")
            }
            frameFeeder?.clearChannel(idx)
        }
        activePlayers.clear()
        activeUrls.clear()
        activeMutedStates.clear()
        pendingSurfaces.clear()
        frameFeeder = null
    }
}
