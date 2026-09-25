package com.wzvideni.multiview.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wzvideni.multiview.R
import com.wzvideni.multiview.model.StreamChannel
import com.wzvideni.multiview.model.StreamStatus
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 单路全屏播放专属控制层
 *
 * 核心优化特性：
 * 1. 触屏唤醒 / 点击切换显隐，3秒静置自动淡出全屏控制栏；
 * 2. 双击画面任意位置快速退出全屏；
 * 3. 顶部提示条引导："双击窗口可退出全屏" 与快捷退出按钮；
 * 4. 底部监控状态指示灯（在线/连接中/异常）与状态文字提示；
 * 5. 全屏主辅码流（高清/流畅）无缝切换与云台 PTZ 控制面板；
 * 6. 滑动手势时自动收起控制栏防遮挡。
 */
@Composable
fun FullscreenControls(
    channel: StreamChannel?,
    channelIndex: Int,
    isPtzVisible: Boolean,
    dragOffsetX: Float = 0f,
    isSmallScreen: Boolean = false,
    onExitFullscreen: () -> Unit,
    onSwitchQuality: (useMainStream: Boolean) -> Unit,
    onToggleAudio: () -> Unit,
    onTogglePtz: () -> Unit,
    onSnapshot: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isControlsVisible by remember { mutableStateOf(true) }
    var resetTimerTrigger by remember { mutableStateOf(0) }

    // 触屏唤醒后显示3秒，然后自动淡出隐藏
    LaunchedEffect(isControlsVisible, resetTimerTrigger) {
        if (isControlsVisible) {
            delay(3000L)
            isControlsVisible = false
        }
    }

    // 通道切换后，重新显示3秒
    LaunchedEffect(channel?.id) {
        isControlsVisible = true
        resetTimerTrigger++
    }

    // 左右滑动手势进行中时，收起控制栏以防遮挡
    LaunchedEffect(dragOffsetX) {
        if (abs(dragOffsetX) > 20f && isControlsVisible) {
            isControlsVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        isControlsVisible = !isControlsVisible
                        if (isControlsVisible) {
                            resetTimerTrigger++
                        }
                    },
                    onDoubleTap = {
                        onExitFullscreen()
                    }
                )
            }
    ) {
        // --- 顶部控制条 ---
        AnimatedVisibility(
            visible = isControlsVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC050C19))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        isControlsVisible = true
                        resetTimerTrigger++
                    }
                    .padding(
                        horizontal = if (isSmallScreen) 12.dp else 16.dp,
                        vertical = if (isSmallScreen) 6.dp else 8.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_tip),
                    contentDescription = null,
                    modifier = Modifier.size(if (isSmallScreen) 16.dp else 20.dp)
                )
                Spacer(modifier = Modifier.width(if (isSmallScreen) 6.dp else 8.dp))
                Text(
                    text = "双击窗口可退出全屏",
                    color = Color(0xFF818FA0),
                    fontSize = if (isSmallScreen) 12.sp else 14.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.weight(1f))

                // 主辅码流切换按钮（全屏支持升为 1080P 主码流）
                val isMain = channel?.isMainStream == true
                Button(
                    onClick = { onSwitchQuality(!isMain) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isMain) Color(0xFF02D5FE) else Color(0xFF20242E)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier
                        .defaultMinSize(minWidth = 1.dp, minHeight = 28.dp)
                        .height(28.dp)
                ) {
                    Text(
                        text = if (isMain) "高清(主码流)" else "流畅(辅码流)",
                        color = if (isMain) Color.Black else Color.White,
                        fontSize = 11.sp,
                        fontWeight = if (isMain) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 云台控制显隐开关
                Button(
                    onClick = onTogglePtz,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPtzVisible) Color(0xFF2CE898) else Color(0xFF20242E)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier
                        .defaultMinSize(minWidth = 1.dp, minHeight = 28.dp)
                        .height(28.dp)
                ) {
                    Text(
                        text = "云台",
                        color = if (isPtzVisible) Color.Black else Color.White,
                        fontSize = 11.sp,
                        fontWeight = if (isPtzVisible) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 退出全屏按钮
                Box(
                    modifier = Modifier
                        .size(if (isSmallScreen) 30.dp else 34.dp)
                        .background(
                            Color(0xCC20242E),
                            if (isSmallScreen) CircleShape else RoundedCornerShape(6.dp)
                        )
                        .border(
                            1.dp,
                            Color(0x809DA7B2),
                            if (isSmallScreen) CircleShape else RoundedCornerShape(6.dp)
                        )
                        .clickable(onClick = onExitFullscreen),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_exit_fullscreen),
                        contentDescription = "退出全屏",
                        tint = Color.White,
                        modifier = Modifier.size(if (isSmallScreen) 15.dp else 18.dp)
                    )
                }
            }
        }

        // --- 云台 PTZ 控制面板（悬浮在右侧） ---
        if (isPtzVisible) {
            PtzControlPanel(
                onDirectionClick = { _ -> },
                onZoomClick = { _ -> },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
            )
        }

        // --- 底部浮动控制与状态条 ---
        AnimatedVisibility(
            visible = isControlsVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xB3050C19))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        isControlsVisible = true
                        resetTimerTrigger++
                    }
                    .padding(
                        horizontal = if (isSmallScreen) 12.dp else 20.dp,
                        vertical = if (isSmallScreen) 6.dp else 10.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusColor = when (channel?.status) {
                    StreamStatus.PLAYING -> Color(0xFF2CE898)
                    StreamStatus.CONNECTING -> Color(0xFFFFB300)
                    StreamStatus.ERROR -> Color(0xFFE83434)
                    else -> Color(0xFF818FA0)
                }
                Box(
                    modifier = Modifier
                        .size(if (isSmallScreen) 8.dp else 10.dp)
                        .background(statusColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(if (isSmallScreen) 6.dp else 8.dp))
                Text(
                    text = channel?.name ?: "通道 ${String.format("%02d", channelIndex + 1)}",
                    color = Color.White,
                    fontSize = if (isSmallScreen) 13.sp else 15.sp,
                    fontWeight = FontWeight.Medium
                )
                if (channel?.status == StreamStatus.CONNECTING) {
                    Spacer(modifier = Modifier.width(if (isSmallScreen) 6.dp else 8.dp))
                    Text(
                        text = "正在连接...",
                        color = Color(0xFFFFB300),
                        fontSize = if (isSmallScreen) 10.sp else 12.sp
                    )
                } else if (channel?.status == StreamStatus.ERROR) {
                    Spacer(modifier = Modifier.width(if (isSmallScreen) 6.dp else 8.dp))
                    Text(
                        text = "连接异常",
                        color = Color(0xFFE83434),
                        fontSize = if (isSmallScreen) 10.sp else 12.sp
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                ActionButton(
                    title = "截屏",
                    onClick = onSnapshot,
                    isSmallScreen = isSmallScreen
                )
                Spacer(modifier = Modifier.width(8.dp))
                ActionButton(
                    title = if (channel?.isMuted == false) "静音" else "伴音",
                    onClick = onToggleAudio,
                    isSmallScreen = isSmallScreen
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    title: String,
    onClick: () -> Unit,
    isSmallScreen: Boolean = false
) {
    Box(
        modifier = Modifier
            .background(Color(0x33FFFFFF), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (isSmallScreen) 10.dp else 14.dp,
                vertical = if (isSmallScreen) 4.dp else 6.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = if (isSmallScreen) 11.sp else 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * 4向云台控制摇杆及变倍
 */
@Composable
private fun PtzControlPanel(
    onDirectionClick: (String) -> Unit,
    onZoomClick: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xD91E2638), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("云台方向", color = Color.LightGray, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(8.dp))

        // 上
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Color(0xFF2C384E), CircleShape)
                .clickable { onDirectionClick("UP") },
            contentAlignment = Alignment.Center
        ) {
            Text("▲", color = Color.White, fontSize = 14.sp)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // 左
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Color(0xFF2C384E), CircleShape)
                    .clickable { onDirectionClick("LEFT") },
                contentAlignment = Alignment.Center
            ) {
                Text("◀", color = Color.White, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.width(4.dp))

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Color(0xFF02D5FE), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("PTZ", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 右
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Color(0xFF2C384E), CircleShape)
                    .clickable { onDirectionClick("RIGHT") },
                contentAlignment = Alignment.Center
            ) {
                Text("▶", color = Color.White, fontSize = 14.sp)
            }
        }

        // 下
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Color(0xFF2C384E), CircleShape)
                .clickable { onDirectionClick("DOWN") },
            contentAlignment = Alignment.Center
        ) {
            Text("▼", color = Color.White, fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 变焦放大/缩小
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { onZoomClick(true) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C384E)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier
                    .defaultMinSize(minWidth = 52.dp, minHeight = 28.dp)
                    .height(30.dp)
            ) {
                Text("放大+", color = Color.White, fontSize = 11.sp, maxLines = 1)
            }
            Button(
                onClick = { onZoomClick(false) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C384E)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier
                    .defaultMinSize(minWidth = 52.dp, minHeight = 28.dp)
                    .height(30.dp)
            ) {
                Text("缩小-", color = Color.White, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}
