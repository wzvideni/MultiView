package com.wzvideni.multiview.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.wzvideni.multiview.gl.IStreamFrameFeeder
import com.wzvideni.multiview.gl.MultiStreamGLSurfaceView
import com.wzvideni.multiview.layout.MultiViewLayoutManager
import com.wzvideni.multiview.model.LayoutMode
import com.wzvideni.multiview.model.StreamChannel
import com.wzvideni.multiview.player.RtspStreamPlayerManager
import com.wzvideni.multiview.state.MultiViewAction
import com.wzvideni.multiview.state.MultiViewState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 多路视频流聚合播放器核心组件 (Compose Plan A 实现)
 *
 * 特性：
 * 1. 单 SurfaceView + OpenGL ES 视口复用，支持 1 ~ 32 路画面极低开销并发渲染；
 * 2. 单画面模式（全屏或 1画面）下支持流畅手势左右滑动切换通道与无缝动画；
 * 3. 精确命中测试：点击任意流可即时获得通道索引；
 * 4. 画面无缝全屏放大与恢复；
 * 5. 支持辅码流（流畅预览）与主码流（全屏高清）动态切换。
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
    val currentSlots = remember(state.layoutMode, state.streamCount, state.isFullscreen, state.fullscreenChannelIndex, state.selectedChannelIndex) {
        MultiViewLayoutManager.calculateSlots(
            mode = state.layoutMode,
            streamCount = state.streamCount,
            fullscreenChannelIndex = if (state.isFullscreen) state.fullscreenChannelIndex else -1,
            selectedChannelIndex = state.selectedChannelIndex
        )
    }

    // 布局模式、全屏状态或选中通道变化时通知底层 OpenGL 渲染器更新视口
    LaunchedEffect(state.layoutMode, state.streamCount, state.isFullscreen, state.fullscreenChannelIndex, state.selectedChannelIndex) {
        surfaceViewRef?.updateLayout(
            mode = state.layoutMode,
            count = state.streamCount,
            fullscreenIndex = if (state.isFullscreen) state.fullscreenChannelIndex else -1,
            selectedIndex = state.selectedChannelIndex
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

    // --- 单画面模式左右滑动切换手势与动画控制 ---
    val isSingleView = state.isFullscreen || state.layoutMode == LayoutMode.GRID_1
    val activeChannelIndex = if (state.isFullscreen && state.fullscreenChannelIndex >= 0) {
        state.fullscreenChannelIndex
    } else {
        state.selectedChannelIndex
    }
    val totalChannels = state.channels.size
    val prevChannelIndex = if (totalChannels > 1) {
        if (activeChannelIndex - 1 < 0) totalChannels - 1 else activeChannelIndex - 1
    } else 0
    val nextChannelIndex = if (totalChannels > 1) {
        (activeChannelIndex + 1) % totalChannels
    } else 0
    val prevChannel = state.channels.getOrNull(prevChannelIndex)
    val nextChannel = state.channels.getOrNull(nextChannelIndex)

    val coroutineScope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var isAnimating by remember { mutableStateOf(false) }

    // 切换通道后的悬浮 OSD HUD
    var switchHudChannel by remember { mutableStateOf<StreamChannel?>(null) }
    var hudJob by remember { mutableStateOf<Job?>(null) }

    fun triggerSwitchHud(channel: StreamChannel?) {
        hudJob?.cancel()
        switchHudChannel = channel
        hudJob = coroutineScope.launch {
            delay(2500)
            switchHudChannel = null
        }
    }

    val gestureModifier = if (isSingleView && totalChannels > 1) {
        Modifier.pointerInput(isSingleView, containerSize.width, totalChannels, activeChannelIndex) {
            val width = size.width.toFloat().coerceAtLeast(1080f)
            val threshold = (width * 0.15f).coerceIn(100f, 300f)

            detectHorizontalDragGestures(
                onDragStart = {
                    if (!isAnimating) {
                        isDragging = true
                    }
                },
                onHorizontalDrag = { change, dragAmount ->
                    if (!isAnimating) {
                        change.consume()
                        coroutineScope.launch {
                            offsetX.snapTo(offsetX.value + dragAmount)
                        }
                    }
                },
                onDragEnd = {
                    if (isAnimating) return@detectHorizontalDragGestures
                    isDragging = false
                    val currentVal = offsetX.value
                    coroutineScope.launch {
                        if (currentVal < -threshold) {
                            // 向左滑动 -> 切换到下一路
                            isAnimating = true
                            offsetX.animateTo(-width, tween(140, easing = FastOutLinearInEasing))
                            onAction(MultiViewAction.SwitchToAdjacentChannel(isNext = true))
                            triggerSwitchHud(nextChannel)
                            offsetX.snapTo(width)
                            offsetX.animateTo(0f, tween(220, easing = LinearOutSlowInEasing))
                            isAnimating = false
                        } else if (currentVal > threshold) {
                            // 向右滑动 -> 切换到上一路
                            isAnimating = true
                            offsetX.animateTo(width, tween(140, easing = FastOutLinearInEasing))
                            onAction(MultiViewAction.SwitchToAdjacentChannel(isNext = false))
                            triggerSwitchHud(prevChannel)
                            offsetX.snapTo(-width)
                            offsetX.animateTo(0f, tween(220, easing = LinearOutSlowInEasing))
                            isAnimating = false
                        } else {
                            // 未达到滑动切换阈值，回弹恢复
                            offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                        }
                    }
                },
                onDragCancel = {
                    isDragging = false
                    coroutineScope.launch {
                        offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                    }
                }
            )
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E14))
            .onSizeChanged { containerSize = it }
            .then(gestureModifier)
    ) {
        // A. 左右滑动手势露出的边缘切换提示卡片
        if (isSingleView && totalChannels > 1 && offsetX.value < -20f) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(Color(0xCC0D1B2A), Color(0xF216293D))
                        ),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .border(1.5.dp, Color(0xFF00E5FF).copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "松开切换至下一路",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = nextChannel?.name ?: "CAM ${nextChannelIndex + 1}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${nextChannel?.resolution ?: "1080P"} · ${nextChannel?.fps ?: 25}fps",
                        color = Color(0xB3FFFFFF),
                        fontSize = 11.sp
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(0x3300E5FF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Next",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        if (isSingleView && totalChannels > 1 && offsetX.value > 20f) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(Color(0xF216293D), Color(0xCC0D1B2A))
                        ),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .border(1.5.dp, Color(0xFF00E5FF).copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(0x3300E5FF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Previous",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = "松开切换至上一路",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = prevChannel?.name ?: "CAM ${prevChannelIndex + 1}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${prevChannel?.resolution ?: "1080P"} · ${prevChannel?.fps ?: 25}fps",
                        color = Color(0xB3FFFFFF),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // B. 视频渲染与槽位覆盖层（随滑动手势与切换动画平移）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = if (isSingleView) offsetX.value else 0f
                }
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
        }

        // C. 通道切换悬浮 OSD HUD 通知条
        AnimatedVisibility(
            visible = switchHudChannel != null,
            enter = fadeIn(animationSpec = tween(200)) + slideInVertically(initialOffsetY = { -it / 2 }),
            exit = fadeOut(animationSpec = tween(300)) + slideOutVertically(targetOffsetY = { -it / 2 }),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (state.isFullscreen) 58.dp else 16.dp)
        ) {
            Surface(
                color = Color(0xF00D1520),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.7f)),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color(0xFF00E676), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = switchHudChannel?.name ?: "",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "·",
                        color = Color(0x66FFFFFF),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${switchHudChannel?.resolution ?: "1080P"} · ${switchHudChannel?.fps ?: 25}fps",
                        color = Color(0xFF00E5FF),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = Color(0x3300E5FF),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (switchHudChannel?.isMainStream == true) "高清主码流" else "流畅子码流",
                            color = Color(0xFF00E5FF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // D. 顶层：控制工具栏（固定定位，不随视频手势平移）
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
