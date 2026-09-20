package com.wzvideni.multiview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.wzvideni.multiview.gl.IStreamFrameFeeder
import com.wzvideni.multiview.gl.MultiStreamGLSurfaceView
import com.wzvideni.multiview.layout.MultiViewLayoutManager
import com.wzvideni.multiview.player.RtspStreamPlayerManager
import com.wzvideni.multiview.state.MultiViewAction
import com.wzvideni.multiview.state.MultiViewState

/**
 * 多路视频流聚合播放器核心组件 (Compose Plan A 实现)
 *
 * 特性：
 * 1. 单 SurfaceView + OpenGL ES 视口复用，支持 1 ~ 32 路画面极低开销并发渲染；
 * 2. 精确命中测试：点击任意流可即时获得通道索引；
 * 3. 画面无缝全屏放大与恢复；
 * 4. 支持辅码流（流畅预览）与主码流（全屏高清）动态切换。
 */
@Composable
fun MultiStreamPlayerView(
    state: MultiViewState,
    onAction: (MultiViewAction) -> Unit,
    modifier: Modifier = Modifier,
    onFrameFeederReady: ((IStreamFrameFeeder) -> Unit)? = null,
    showControls: Boolean = true
) {
    val context = LocalContext.current
    val playerManager = remember { RtspStreamPlayerManager(context) }
    var surfaceViewRef by remember { mutableStateOf<MultiStreamGLSurfaceView?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // 计算当前布局的所有槽位几何位置
    val currentSlots = remember(state.layoutMode, state.streamCount, state.isFullscreen, state.fullscreenChannelIndex) {
        MultiViewLayoutManager.calculateSlots(
            mode = state.layoutMode,
            streamCount = state.streamCount,
            fullscreenChannelIndex = if (state.isFullscreen) state.fullscreenChannelIndex else -1
        )
    }

    // 布局模式或全屏状态变化时通知底层 OpenGL 渲染器更新视口
    LaunchedEffect(state.layoutMode, state.streamCount, state.isFullscreen, state.fullscreenChannelIndex) {
        surfaceViewRef?.updateLayout(
            mode = state.layoutMode,
            count = state.streamCount,
            fullscreenIndex = if (state.isFullscreen) state.fullscreenChannelIndex else -1
        )
    }

    // 选中通道高亮同步
    LaunchedEffect(state.selectedChannelIndex) {
        surfaceViewRef?.setSelectedChannel(state.selectedChannelIndex)
    }

    // 动态智能调度 RTSP 播放器拉流
    LaunchedEffect(state.channels, currentSlots, state.isFullscreen, state.fullscreenChannelIndex) {
        val visibleIndices = currentSlots.map { it.slotIndex }
        playerManager.updateStreams(
            channels = state.channels,
            visibleIndices = visibleIndices,
            isFullscreen = state.isFullscreen,
            fullscreenChannelIndex = state.fullscreenChannelIndex
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E14))
            .onSizeChanged { containerSize = it }
    ) {
        // 1. 底层：单一 OpenGL ES SurfaceView 负责所有 1~32 路视频画面的视口合成绘制
        AndroidView(
            factory = { ctx ->
                MultiStreamGLSurfaceView(ctx).also { view ->
                    surfaceViewRef = view
                    playerManager.bindFeeder(view.renderer)
                    onFrameFeederReady?.invoke(view.renderer)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                surfaceViewRef = view
            }
        )

        DisposableEffect(Unit) {
            onDispose {
                playerManager.releaseAll()
                surfaceViewRef?.onDestroy()
                surfaceViewRef = null
            }
        }

        // 2. 中层：Compose 交互与 OSD 覆盖层（负责手势拦截、命中测试、聚焦边框）
        MultiStreamOverlay(
            slots = currentSlots,
            channels = state.channels,
            selectedChannelIndex = state.selectedChannelIndex,
            isFullscreen = state.isFullscreen,
            containerSize = containerSize,
            onChannelClick = { channelIndex, _ ->
                onAction(MultiViewAction.SelectChannel(channelIndex))
            },
            onChannelDoubleClick = { channelIndex, _ ->
                onAction(MultiViewAction.ToggleFullscreen(channelIndex))
            }
        )

        // 3. 顶层：控制工具栏
        if (state.isFullscreen) {
            // 全屏模式控制栏
            FullscreenControls(
                channel = state.currentFullscreenChannel,
                channelIndex = state.fullscreenChannelIndex,
                isPtzVisible = state.isPtzControlVisible,
                onExitFullscreen = { onAction(MultiViewAction.ExitFullscreen) },
                onSwitchQuality = { useMain ->
                    onAction(MultiViewAction.SwitchStreamQuality(state.fullscreenChannelIndex, useMain))
                },
                onToggleAudio = {
                    onAction(MultiViewAction.ToggleAudio(state.fullscreenChannelIndex))
                },
                onTogglePtz = {
                    onAction(MultiViewAction.TogglePtzControl(!state.isPtzControlVisible))
                }
            )
        } else if (showControls) {
            // 宫格模式底部工具栏
            MultiStreamControls(
                currentMode = state.layoutMode,
                totalStreams = state.streamCount,
                selectedChannelIndex = state.selectedChannelIndex,
                onModeSelected = { mode -> onAction(MultiViewAction.ChangeLayoutMode(mode)) },
                onEnterFullscreen = { channelIndex ->
                    onAction(MultiViewAction.ToggleFullscreen(channelIndex))
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
