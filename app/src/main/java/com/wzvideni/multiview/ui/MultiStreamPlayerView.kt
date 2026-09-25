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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
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
 * 多路视频流聚合播放器核心组件
 *
 * 核心特性：
 * 1. 单 SurfaceView + OpenGL ES 视口复用，支持 1 ~ 32 路画面极低开销并发硬件渲染；
 * 2. 完美支持自适应布局（1/2/4/6/9 分屏及 9+ 分页），支持空槽位平滑渲染；
 * 3. 增强播放重试机制：错峰启动、超时保护、指数退避自动重连；
 * 4. 长按拖动调换窗口顺序：触点光圈、浮动预览卡片、目标窗口悬停高亮提示；
 * 5. 全屏/单画面下流畅左右滑动手势切换上一路/下一路并带边缘卡片提示；
 * 6. 9+ 画面宫格模式支持左右滑动分页与底部分页指示器。
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
    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val isSmallScreen = configuration.screenWidthDp < 500

    val playerManager = remember { RtspStreamPlayerManager(context) }
    var surfaceViewRef by remember { mutableStateOf<MultiStreamGLSurfaceView?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // 监听播放器状态变动并通知 ViewModel
    DisposableEffect(playerManager) {
        playerManager.onChannelStatusChanged = { channelIndex, status ->
            onAction(MultiViewAction.UpdateChannelStatus(channelIndex, status))
        }
        onDispose {
            playerManager.onChannelStatusChanged = null
        }
    }

    // 计算当前布局的所有槽位几何位置
    val currentSlots = remember(
        state.layoutMode,
        state.streamCount,
        state.currentPage,
        state.isFullscreen,
        state.fullscreenChannelIndex,
        state.selectedChannelIndex,
        isPortrait,
        isSmallScreen
    ) {
        MultiViewLayoutManager.calculateSlots(
            mode = state.layoutMode,
            streamCount = state.streamCount,
            pageIndex = state.currentPage,
            fullscreenChannelIndex = if (state.isFullscreen) state.fullscreenChannelIndex else -1,
            selectedChannelIndex = state.selectedChannelIndex,
            isPortrait = isPortrait,
            isSmallScreen = isSmallScreen
        )
    }

    // 通知底层 OpenGL 渲染器更新视口与布局
    LaunchedEffect(
        state.layoutMode,
        state.streamCount,
        state.currentPage,
        state.isFullscreen,
        state.fullscreenChannelIndex,
        state.selectedChannelIndex,
        isPortrait,
        isSmallScreen
    ) {
        surfaceViewRef?.updateLayout(
            mode = state.layoutMode,
            count = state.streamCount,
            pageIndex = state.currentPage,
            fullscreenIndex = if (state.isFullscreen) state.fullscreenChannelIndex else -1,
            selectedIndex = state.selectedChannelIndex,
            isPortrait = isPortrait,
            isSmallScreen = isSmallScreen
        )
    }

    // 选中通道高亮同步
    LaunchedEffect(state.selectedChannelIndex) {
        surfaceViewRef?.setSelectedChannel(state.selectedChannelIndex)
    }

    // 提取流配置特征指纹，阻断仅因 channel.status 状态变动导致的 LaunchedEffect 频繁重组风暴
    val streamConfigSignature = remember(state.channels) {
        state.channels.map { ch ->
            "${ch.id}_${ch.isMuted}_${ch.isMainStream}_${ch.rtspUrl}_${ch.subRtspUrl}_${ch.mainRtspUrl}"
        }
    }

    // 动态智能调度 RTSP 播放器拉流 (包含错峰启动与重试)
    LaunchedEffect(streamConfigSignature, currentSlots, state.isFullscreen, state.fullscreenChannelIndex) {
        val visibleIndices = currentSlots.filter { !it.isEmptySlot }.map { it.channelIndex }
        playerManager.updateStreams(
            channels = state.channels,
            visibleIndices = visibleIndices,
            isFullscreen = state.isFullscreen,
            fullscreenChannelIndex = state.fullscreenChannelIndex
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            playerManager.releaseAll()
            surfaceViewRef?.onDestroy()
            surfaceViewRef = null
        }
    }

    // --- 单画面模式左右滑动切换手势与动画控制 ---
    val isSingleView = state.isFullscreen || (state.layoutMode == LayoutMode.GRID_1 && state.streamCount == 1)
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

    val offsetX = remember { Animatable(0f) }
    var isDraggingFullscreen by remember { mutableStateOf(false) }
    var isAnimatingFullscreen by remember { mutableStateOf(false) }

    // 切换通道后的悬浮 OSD HUD
    var switchHudChannel by remember { mutableStateOf<StreamChannel?>(null) }
    var hudJob by remember { mutableStateOf<Job?>(null) }

    fun triggerSwitchHud(channel: StreamChannel?) {
        hudJob?.cancel()
        switchHudChannel = channel
        hudJob = coroutineScope.launch {
            delay(2200)
            switchHudChannel = null
        }
    }

    // 手势拦截 Modifier 构造
    val gestureModifier = if (isSingleView && totalChannels > 1) {
        // 全屏/单画面模式：左右滑动切换上下路通道
        Modifier.pointerInput(isSingleView, containerSize.width, totalChannels, activeChannelIndex) {
            val width = size.width.toFloat().coerceAtLeast(800f)
            val threshold = (width * 0.15f).coerceIn(80f, 250f)

            detectHorizontalDragGestures(
                onDragStart = {
                    if (!isAnimatingFullscreen) isDraggingFullscreen = true
                },
                onHorizontalDrag = { change, dragAmount ->
                    if (!isAnimatingFullscreen) {
                        change.consume()
                        coroutineScope.launch {
                            offsetX.snapTo(offsetX.value + dragAmount)
                        }
                    }
                },
                onDragEnd = {
                    if (isAnimatingFullscreen) return@detectHorizontalDragGestures
                    isDraggingFullscreen = false
                    val currentVal = offsetX.value
                    coroutineScope.launch {
                        if (currentVal < -threshold) {
                            // 向左滑动 -> 切换到下一路
                            isAnimatingFullscreen = true
                            offsetX.animateTo(-width, tween(120, easing = FastOutLinearInEasing))
                            onAction(MultiViewAction.SwitchToAdjacentChannel(isNext = true))
                            triggerSwitchHud(nextChannel)
                            offsetX.snapTo(width)
                            offsetX.animateTo(0f, tween(200, easing = LinearOutSlowInEasing))
                            isAnimatingFullscreen = false
                        } else if (currentVal > threshold) {
                            // 向右滑动 -> 切换到上一路
                            isAnimatingFullscreen = true
                            offsetX.animateTo(width, tween(120, easing = FastOutLinearInEasing))
                            onAction(MultiViewAction.SwitchToAdjacentChannel(isNext = false))
                            triggerSwitchHud(prevChannel)
                            offsetX.snapTo(-width)
                            offsetX.animateTo(0f, tween(200, easing = LinearOutSlowInEasing))
                            isAnimatingFullscreen = false
                        } else {
                            // 未达切换阈值，弹性回弹
                            offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                        }
                    }
                },
                onDragCancel = {
                    isDraggingFullscreen = false
                    coroutineScope.launch {
                        offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                    }
                }
            )
        }
    } else if (!state.isFullscreen && state.totalPages > 1) {
        // 多画面分页模式：左右横向滑动切换页面
        Modifier.pointerInput(state.currentPage, state.totalPages) {
            var totalDrag = 0f
            detectHorizontalDragGestures(
                onDragStart = { totalDrag = 0f },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    totalDrag += dragAmount
                },
                onDragEnd = {
                    if (totalDrag < -100f && state.currentPage < state.totalPages - 1) {
                        onAction(MultiViewAction.SwitchPage(state.currentPage + 1))
                    } else if (totalDrag > 100f && state.currentPage > 0) {
                        onAction(MultiViewAction.SwitchPage(state.currentPage - 1))
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
            .background(Color(0xFF0B1114))
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
                            listOf(Color(0xCC050C19), Color(0xF21F3952))
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .border(1.5.dp, Color(0xFF00E5FF), RoundedCornerShape(12.dp))
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
                        color = Color(0xCCFFFFFF),
                        fontSize = 11.sp
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color(0x3300E5FF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Next",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(16.dp)
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
                            listOf(Color(0xF21F3952), Color(0xCC050C19))
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .border(1.5.dp, Color(0xFF00E5FF), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color(0x3300E5FF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Previous",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
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
                        color = Color(0xCCFFFFFF),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // B. 视频渲染与槽位覆盖层（全屏手势时跟随平移）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = if (isSingleView) offsetX.value else 0f
                }
        ) {
            // 1. 底层：OpenGL ES 单 SurfaceView 视口复用合成
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

            // 2. 中层：Compose 槽位交互与 OSD 覆盖层（长按拖动换位、高亮边框、状态徽标）
            MultiStreamOverlay(
                slots = currentSlots,
                channels = state.channels,
                selectedChannelIndex = state.selectedChannelIndex,
                isFullscreen = state.isFullscreen,
                showTooltip = state.showTooltip,
                containerSize = containerSize,
                isSmallScreen = isSmallScreen,
                onChannelClick = { channelIndex ->
                    onAction(MultiViewAction.SelectChannel(channelIndex))
                },
                onChannelDoubleClick = { channelIndex ->
                    onAction(MultiViewAction.ToggleFullscreen(channelIndex))
                },
                onEmptySlotClick = {
                    onAction(MultiViewAction.SelectChannel(0))
                },
                onCloseChannel = { channelIndex ->
                    onAction(MultiViewAction.CloseChannel(channelIndex))
                },
                onSwapChannels = { from, to ->
                    onAction(MultiViewAction.SwapChannels(from, to))
                }
            )
        }

        // C. 通道切换悬浮 OSD HUD 通知胶囊
        AnimatedVisibility(
            visible = switchHudChannel != null,
            enter = fadeIn(animationSpec = tween(200)) + slideInVertically(initialOffsetY = { -it / 2 }),
            exit = fadeOut(animationSpec = tween(300)) + slideOutVertically(targetOffsetY = { -it / 2 }),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (state.isFullscreen) 58.dp else 16.dp)
        ) {
            Surface(
                color = Color(0xF0050C19),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFF00E5FF)),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color(0xFF2CE898), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = switchHudChannel?.name ?: "",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${switchHudChannel?.resolution ?: "1080P"} · ${switchHudChannel?.fps ?: 25}fps",
                        color = Color(0xFF00E5FF),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // D. 9+ 画面底部分页圆点指示器
        if (!state.isFullscreen && state.totalPages > 1) {
            MultiStreamPageIndicator(
                totalPages = state.totalPages,
                currentPage = state.currentPage,
                onPageClick = { page -> onAction(MultiViewAction.SwitchPage(page)) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (showControls) 80.dp else 16.dp)
            )
        }

        // E. 全屏模式专属控制器
        if (state.isFullscreen) {
            FullscreenControls(
                channel = state.currentFullscreenChannel,
                channelIndex = state.fullscreenChannelIndex,
                isPtzVisible = state.isPtzControlVisible,
                dragOffsetX = offsetX.value,
                isSmallScreen = isSmallScreen,
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

        // F. 浮动 Toast 提示胶囊
        AnimatedVisibility(
            visible = state.toastMessage != null,
            enter = fadeIn(tween(150)) + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut(tween(200)) + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (showControls && !state.isFullscreen) 90.dp else 40.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xE6050C19), RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0xFF00E5FF), RoundedCornerShape(20.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    text = state.toastMessage ?: "",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * 9+ 画面分页指示器
 */
@Composable
fun MultiStreamPageIndicator(
    totalPages: Int,
    currentPage: Int,
    onPageClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (page in 0 until totalPages) {
            val isCurrent = (page == currentPage)
            Box(
                modifier = Modifier
                    .size(if (isCurrent) 10.dp else 8.dp)
                    .background(
                        if (isCurrent) Color(0xFF00E5FF) else Color(0x40FFFFFF),
                        CircleShape
                    )
                    .clickable { onPageClick(page) }
            )
        }
    }
}
