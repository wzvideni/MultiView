package com.wzvideni.multiview.ui

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wzvideni.multiview.layout.MultiViewLayoutManager
import com.wzvideni.multiview.layout.StreamSlotRect
import com.wzvideni.multiview.model.StreamChannel
import com.wzvideni.multiview.model.StreamStatus

/**
 * 叠加在 GL 视频渲染层之上的 Compose 交互与 OSD 层
 *
 * 核心职责：
 * 1. 拦截手势，通过 MultiViewLayoutManager 精确计算点击的是哪一路视频（命中测试）；
 * 2. 绘制当前高亮选中的通道边框（Cyan/Green 聚焦框）；
 * 3. 绘制 OSD 标题、状态徽章（码流类型、帧率、状态）、快捷全屏与静音操作。
 */
@Composable
fun MultiStreamOverlay(
    slots: List<StreamSlotRect>,
    channels: List<StreamChannel>,
    selectedChannelIndex: Int,
    isFullscreen: Boolean,
    onChannelClick: (channelIndex: Int, channel: StreamChannel?) -> Unit,
    onChannelDoubleClick: (channelIndex: Int, channel: StreamChannel?) -> Unit,
    containerSize: IntSize,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val viewW = containerSize.width.toFloat()
    val viewH = containerSize.height.toFloat()

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(slots, containerSize, isFullscreen) {
                detectTapGestures(
                    onTap = { offset ->
                        val clickedSlot = MultiViewLayoutManager.findSlotAt(
                            pxX = offset.x,
                            pxY = offset.y,
                            viewWidth = viewW,
                            viewHeight = viewH,
                            slots = slots
                        )
                        if (clickedSlot != null) {
                            val channel = channels.getOrNull(clickedSlot)
                            Log.d("MultiView", "Single Tap on channel: $clickedSlot (${channel?.name})")
                            onChannelClick(clickedSlot, channel)
                        }
                    },
                    onDoubleTap = { offset ->
                        val clickedSlot = MultiViewLayoutManager.findSlotAt(
                            pxX = offset.x,
                            pxY = offset.y,
                            viewWidth = viewW,
                            viewHeight = viewH,
                            slots = slots
                        )
                        if (clickedSlot != null) {
                            val channel = channels.getOrNull(clickedSlot)
                            Log.d("MultiView", "Double Tap (Toggle Fullscreen) on channel: $clickedSlot")
                            onChannelDoubleClick(clickedSlot, channel)
                        }
                    }
                )
            }
    ) {
        // 如果不是全屏模式，为每个槽位绘制 OSD 及选中聚焦框
        if (!isFullscreen && viewW > 0 && viewH > 0) {
            slots.forEach { slot ->
                val channelIndex = slot.slotIndex
                val channel = channels.getOrNull(channelIndex)
                val isSelected = (channelIndex == selectedChannelIndex)

                val leftPx = (slot.normalizedLeft * viewW).toInt()
                val topPx = (slot.normalizedTop * viewH).toInt()
                val widthPx = ((slot.normalizedRight - slot.normalizedLeft) * viewW).toInt()
                val heightPx = ((slot.normalizedBottom - slot.normalizedTop) * viewH).toInt()

                val widthDp = with(density) { widthPx.toDp() }
                val heightDp = with(density) { heightPx.toDp() }

                Box(
                    modifier = Modifier
                        .offset { IntOffset(leftPx, topPx) }
                        .size(widthDp, heightDp)
                        .then(
                            if (isSelected) {
                                Modifier.border(
                                    BorderStroke(2.dp, Color(0xFF00E5FF)),
                                    RoundedCornerShape(2.dp)
                                )
                            } else {
                                Modifier.border(
                                    BorderStroke(0.5.dp, Color(0x33FFFFFF)),
                                    RoundedCornerShape(0.dp)
                                )
                            }
                        )
                        .padding(4.dp)
                ) {
                    // OSD 顶部信息条
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .background(Color(0xB3000000), RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 状态指示灯（在线绿色，连接中黄色，离线红色）
                        val statusColor = when (channel?.status) {
                            StreamStatus.PLAYING -> Color(0xFF00E676)
                            StreamStatus.CONNECTING -> Color(0xFFFFB300)
                            StreamStatus.ERROR -> Color(0xFFFF5252)
                            else -> Color(0xFF9E9E9E)
                        }
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(statusColor, CircleShape)
                        )

                        val shortTitle = if (widthDp < 65.dp) "${channelIndex + 1}" else "CAM ${String.format("%02d", channelIndex + 1)}"
                        val channelTitle = if (widthDp < 110.dp) shortTitle else (channel?.name ?: shortTitle)
                        Text(
                            text = " $channelTitle",
                            color = Color.White,
                            fontSize = if (widthDp < 65.dp) 9.sp else 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (channel?.isMainStream == true && widthDp >= 130.dp) {
                            Text(
                                text = " [主码流]",
                                color = Color(0xFF00E5FF),
                                fontSize = 9.sp,
                                maxLines = 1
                            )
                        }
                    }

                    // 底部简要参数（帧率与码率）
                    if (widthDp > 80.dp && heightDp > 50.dp) {
                        Text(
                            text = "${channel?.resolution ?: "360P"} | ${channel?.fps ?: 15}fps",
                            color = Color(0xCCFFFFFF),
                            fontSize = 9.sp,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .background(Color(0x80000000), RoundedCornerShape(2.dp))
                                .padding(horizontal = 3.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}
