package com.wzvideni.multiview.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wzvideni.multiview.R
import com.wzvideni.multiview.layout.MultiViewLayoutManager
import com.wzvideni.multiview.layout.StreamSlotRect
import com.wzvideni.multiview.model.StreamChannel
import com.wzvideni.multiview.model.StreamStatus

/**
 * 监控视口 Compose 交互与 OSD 覆盖层
 *
 * 核心特性：
 * 1. 选中窗口外边框聚焦高亮 (#00E5FF / #02D5FE)
 * 2. 底部视频信息栏：绿点在线状态灯 + 设备名称 (#FFFFFF)
 * 3. 顶部提示条："双击全屏 · 长按可拖动调换顺序" + 快捷关闭按钮
 * 4. 空视口占位卡片："暂无监控画面" + "点击可选择设备发起"
 * 5. 长按拖动调换窗口顺序：长按触发高亮与浮动预览卡片，悬浮目标窗口高亮提示，释放调换
 */
@Composable
fun MultiStreamOverlay(
    slots: List<StreamSlotRect>,
    channels: List<StreamChannel>,
    selectedChannelIndex: Int,
    isFullscreen: Boolean,
    showTooltip: Boolean,
    containerSize: IntSize,
    onChannelClick: (channelIndex: Int) -> Unit,
    onChannelDoubleClick: (channelIndex: Int) -> Unit,
    onEmptySlotClick: () -> Unit = {},
    onCloseChannel: (channelIndex: Int) -> Unit = {},
    onSwapChannels: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val viewW = containerSize.width.toFloat()
    val viewH = containerSize.height.toFloat()

    // 拖拽手势状态
    var isDragging by remember { mutableStateOf(false) }
    var dragSourceSlot by remember { mutableStateOf<StreamSlotRect?>(null) }
    var dragSourceChannel by remember { mutableStateOf<StreamChannel?>(null) }
    var dragCurrentOffset by remember { mutableStateOf(Offset.Zero) }
    var hoverTargetSlot by remember { mutableStateOf<StreamSlotRect?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 1. 长按拖拽调换窗口顺序手势
            .pointerInput(slots, containerSize, isFullscreen, channels.size) {
                if (isFullscreen || channels.size <= 1) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val slot = MultiViewLayoutManager.findSlotAt(
                            pxX = offset.x,
                            pxY = offset.y,
                            viewWidth = viewW,
                            viewHeight = viewH,
                            slots = slots
                        )
                        if (slot != null && !slot.isEmptySlot && slot.channelIndex in channels.indices) {
                            isDragging = true
                            dragSourceSlot = slot
                            dragSourceChannel = channels[slot.channelIndex]
                            dragCurrentOffset = offset
                            hoverTargetSlot = slot
                            onChannelClick(slot.channelIndex)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragCurrentOffset += dragAmount
                        hoverTargetSlot = MultiViewLayoutManager.findSlotAt(
                            pxX = dragCurrentOffset.x,
                            pxY = dragCurrentOffset.y,
                            viewWidth = viewW,
                            viewHeight = viewH,
                            slots = slots
                        )
                    },
                    onDragEnd = {
                        val source = dragSourceSlot
                        val target = hoverTargetSlot
                        if (source != null && target != null && source.slotIndex != target.slotIndex) {
                            val targetIndex = if (target.isEmptySlot) {
                                (channels.size - 1).coerceAtLeast(0)
                            } else {
                                target.channelIndex
                            }
                            if (source.channelIndex != targetIndex &&
                                source.channelIndex in channels.indices &&
                                targetIndex in channels.indices
                            ) {
                                onSwapChannels(source.channelIndex, targetIndex)
                            }
                        }
                        isDragging = false
                        dragSourceSlot = null
                        dragSourceChannel = null
                        hoverTargetSlot = null
                    },
                    onDragCancel = {
                        isDragging = false
                        dragSourceSlot = null
                        dragSourceChannel = null
                        hoverTargetSlot = null
                    }
                )
            }
            // 2. 单击选中 / 双击全屏
            .pointerInput(slots, containerSize, isFullscreen) {
                detectTapGestures(
                    onTap = { offset ->
                        if (isDragging) return@detectTapGestures
                        val slot = MultiViewLayoutManager.findSlotAt(
                            pxX = offset.x,
                            pxY = offset.y,
                            viewWidth = viewW,
                            viewHeight = viewH,
                            slots = slots
                        )
                        if (slot != null) {
                            if (slot.isEmptySlot || slot.channelIndex < 0) {
                                onEmptySlotClick()
                            } else {
                                onChannelClick(slot.channelIndex)
                            }
                        }
                    },
                    onDoubleTap = { offset ->
                        if (isDragging) return@detectTapGestures
                        val slot = MultiViewLayoutManager.findSlotAt(
                            pxX = offset.x,
                            pxY = offset.y,
                            viewWidth = viewW,
                            viewHeight = viewH,
                            slots = slots
                        )
                        if (slot != null && !slot.isEmptySlot && slot.channelIndex >= 0) {
                            onChannelDoubleClick(slot.channelIndex)
                        }
                    }
                )
            }
    ) {
        if (!isFullscreen && viewW > 0 && viewH > 0) {
            slots.forEach { slot ->
                val leftPx = (slot.normalizedLeft * viewW).toInt()
                val topPx = (slot.normalizedTop * viewH).toInt()
                val widthPx = ((slot.normalizedRight - slot.normalizedLeft) * viewW).toInt()
                val heightPx = ((slot.normalizedBottom - slot.normalizedTop) * viewH).toInt()

                val widthDp = with(density) { widthPx.toDp() }
                val heightDp = with(density) { heightPx.toDp() }

                val isDragSource = isDragging && slot.slotIndex == dragSourceSlot?.slotIndex
                val isHoverTarget = isDragging && !isDragSource && slot.slotIndex == hoverTargetSlot?.slotIndex
                val isSelected = (!slot.isEmptySlot && slot.channelIndex == selectedChannelIndex)
                val channel = channels.getOrNull(slot.channelIndex)

                val borderColor = when {
                    isHoverTarget -> Color(0xFF00E5FF)
                    isDragSource -> Color(0x8000E5FF)
                    isSelected -> Color(0xFF00E5FF)
                    else -> Color(0xFF305272)
                }
                val borderWidth = when {
                    isHoverTarget -> 3.dp
                    isDragSource -> 2.dp
                    isSelected -> 2.dp
                    else -> 1.dp
                }

                Box(
                    modifier = Modifier
                        .offset { IntOffset(leftPx, topPx) }
                        .size(widthDp, heightDp)
                        .border(BorderStroke(borderWidth, borderColor))
                ) {
                    if (slot.isEmptySlot || channel == null) {
                        // 空槽位占位
                        EmptySlotContent(
                            widthDp = widthDp,
                            onClick = onEmptySlotClick
                        )
                        // 若正被拖拽悬停至空槽位
                        if (isHoverTarget) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0x3300E5FF)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "释放调换至末尾",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .background(Color(0xCC05ABC1), RoundedCornerShape(16.dp))
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    } else {
                        // 视频通道内容层
                        // A. 顶部提示条 (单击选中时显示 "双击全屏 · 长按可拖动调换顺序")
                        AnimatedVisibility(
                            visible = !isDragging && isSelected && showTooltip,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xCC050C19))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_tip),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                val tipText = if (channels.size > 1) "双击全屏 · 长按可拖动调换顺序" else "双击窗口可进行全屏"
                                Text(
                                    text = tipText,
                                    color = Color(0xFF818FA0),
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(Color(0x990B1114), RoundedCornerShape(4.dp))
                                        .border(1.dp, Color(0x809DA7B2), RoundedCornerShape(4.dp))
                                        .clickable { onCloseChannel(channel.channelIndex) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_close_channel),
                                        contentDescription = "关闭显示",
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }

                        // B. 底部通道状态条
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .background(Color(0xB3050C19))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 状态圆点 (在线绿色，连接中黄色，异常红色，空闲灰色)
                            val statusColor = when (channel.status) {
                                StreamStatus.PLAYING -> Color(0xFF2CE898)
                                StreamStatus.CONNECTING -> Color(0xFFFFB300)
                                StreamStatus.ERROR -> Color(0xFFE83434)
                                StreamStatus.IDLE, StreamStatus.NO_SIGNAL -> Color(0xFF818FA0)
                            }
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(statusColor, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))

                            Text(
                                text = channel.name,
                                color = Color.White,
                                fontSize = if (widthDp < 240.dp) 12.sp else 14.sp,
                                fontWeight = FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (channel.status == StreamStatus.CONNECTING) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "连接中...",
                                    color = Color(0xFFFFB300),
                                    fontSize = 10.sp,
                                    modifier = Modifier
                                        .background(Color(0x33FFB300), RoundedCornerShape(2.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            } else if (channel.status == StreamStatus.ERROR) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "已断开",
                                    color = Color(0xFFE83434),
                                    fontSize = 10.sp,
                                    modifier = Modifier
                                        .background(Color(0x33E83434), RoundedCornerShape(2.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }

                            if (channel.isMainStream && widthDp >= 200.dp) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "主码流",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 10.sp,
                                    modifier = Modifier
                                        .background(Color(0x3300E5FF), RoundedCornerShape(2.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }

                        // C. 拖拽源槽位遮罩 (长按提起的窗口半透明压暗)
                        if (isDragSource) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0x99050C19)),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .background(Color(0xB3000000), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.ic_tip),
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "正在调换该窗口...",
                                        color = Color(0xFF00E5FF),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        // D. 拖拽悬浮目标槽位高亮遮罩 (释放位置高亮提示)
                        if (isHoverTarget) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0x3300E5FF)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "释放以调换顺序",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .background(Color(0xEE05ABC1), RoundedCornerShape(20.dp))
                                        .padding(horizontal = 14.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. 拖拽中跟随手指的触点光圈与浮动预览卡片
        if (isDragging && dragSourceChannel != null && viewW > 0 && viewH > 0) {
            // A. 手指触点光圈
            val touchSizeDp = 36.dp
            val touchHalfPx = with(density) { (touchSizeDp / 2).toPx() }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (dragCurrentOffset.x - touchHalfPx).toInt(),
                            (dragCurrentOffset.y - touchHalfPx).toInt()
                        )
                    }
                    .size(touchSizeDp)
                    .background(Color(0x4000E5FF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(Color(0xFF00E5FF), CircleShape)
                        .border(2.dp, Color.White, CircleShape)
                )
            }

            // B. 浮动预览卡片 (半透明卡片跟随手指移动)
            val ghostW = 200.dp
            val ghostH = 112.dp
            val ghostWPx = with(density) { ghostW.toPx() }
            val ghostHPx = with(density) { ghostH.toPx() }
            val ghostX = (dragCurrentOffset.x - ghostWPx / 2f).coerceIn(12f, (viewW - ghostWPx - 12f).coerceAtLeast(12f))
            val ghostY = (dragCurrentOffset.y - ghostHPx - 24f).coerceIn(12f, (viewH - ghostHPx - 12f).coerceAtLeast(12f))

            Box(
                modifier = Modifier
                    .offset { IntOffset(ghostX.toInt(), ghostY.toInt()) }
                    .size(ghostW, ghostH)
                    .shadow(16.dp, RoundedCornerShape(8.dp))
                    .background(Color(0xEB07111C), RoundedCornerShape(8.dp))
                    .border(BorderStroke(2.dp, Color(0xFF00E5FF)), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFF2CE898), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = dragSourceChannel?.name ?: "",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "调换中",
                            color = Color(0xFF00E5FF),
                            fontSize = 10.sp,
                            modifier = Modifier
                                .background(Color(0x3300E5FF), RoundedCornerShape(3.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Image(
                        painter = painterResource(id = R.drawable.ic_camera_item),
                        contentDescription = null,
                        modifier = Modifier
                            .size(32.dp)
                            .align(Alignment.CenterHorizontally)
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    val targetText = if (hoverTargetSlot != null && hoverTargetSlot?.slotIndex != dragSourceSlot?.slotIndex) {
                        if (hoverTargetSlot!!.isEmptySlot) {
                            "释放调换至 末尾"
                        } else {
                            "释放调换至 窗口 ${hoverTargetSlot!!.slotIndex + 1}"
                        }
                    } else {
                        "拖动至目标窗口调换"
                    }
                    Text(
                        text = targetText,
                        color = if (hoverTargetSlot != null && hoverTargetSlot?.slotIndex != dragSourceSlot?.slotIndex) Color(0xFF00E5FF) else Color(0xFF818FA0),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * 空视口占位组件
 */
@Composable
private fun EmptySlotContent(
    widthDp: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1114))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val iconSize = if (widthDp < 240.dp) 48.dp else 72.dp
        Image(
            painter = painterResource(id = R.drawable.ic_empty_screen),
            contentDescription = "暂无监控画面",
            modifier = Modifier.size(iconSize)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "暂无监控画面",
            color = Color.White,
            fontSize = if (widthDp < 240.dp) 13.sp else 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        if (widthDp >= 200.dp) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "点击可选择设备发起",
                color = Color(0xFF818FA0),
                fontSize = if (widthDp < 240.dp) 10.sp else 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
