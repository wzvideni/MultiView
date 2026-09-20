package com.wzvideni.multiview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wzvideni.multiview.model.StreamChannel

/**
 * 单路全屏播放时的专属操作控制栏与 OSD（包括清晰度切换、云台 PTZ 控制、截屏等）
 */
@Composable
fun FullscreenControls(
    channel: StreamChannel?,
    channelIndex: Int,
    isPtzVisible: Boolean,
    onExitFullscreen: () -> Unit,
    onSwitchQuality: (useMainStream: Boolean) -> Unit,
    onToggleAudio: () -> Unit,
    onTogglePtz: () -> Unit,
    onSnapshot: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // --- 顶部控制条 ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color(0xCC111622))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回按钮
            IconButton(
                onClick = onExitFullscreen,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回分屏",
                    tint = Color.White
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 通道标题
            val title = channel?.name ?: "通道 ${String.format("%02d", channelIndex + 1)}"
            Column {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${if (channel?.isMainStream == true) "主码流" else "辅码流"} | ${channel?.resolution ?: "1080P"} | ${channel?.bitrateKbps ?: 1024} Kbps",
                    color = Color(0xFF00E5FF),
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // 主辅码流切换按钮（全屏支持升为 1080P 主码流）
            val isMain = channel?.isMainStream == true
            Button(
                onClick = { onSwitchQuality(!isMain) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMain) Color(0xFF00B0FF) else Color(0xFF37474F)
                ),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier
                    .defaultMinSize(minWidth = 1.dp, minHeight = 32.dp)
                    .height(32.dp)
            ) {
                Text(
                    text = if (isMain) "高清 (主码流)" else "流畅 (辅码流)",
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 云台控制显隐开关
            Button(
                onClick = onTogglePtz,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPtzVisible) Color(0xFF00E676) else Color(0xFF37474F)
                ),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                modifier = Modifier
                    .defaultMinSize(minWidth = 1.dp, minHeight = 32.dp)
                    .height(32.dp)
            ) {
                Text(
                    text = "云台",
                    color = if (isPtzVisible) Color.Black else Color.White,
                    fontSize = 12.sp,
                    fontWeight = if (isPtzVisible) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1
                )
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

        // --- 底部浮动控制条 ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xCC111622))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            ActionButton(title = "截屏", onClick = onSnapshot)
            ActionButton(
                title = if (channel?.isMuted == false) "静音" else "伴音",
                onClick = onToggleAudio
            )
            ActionButton(title = "退出全屏", onClick = onExitFullscreen)
        }
    }
}

@Composable
private fun ActionButton(title: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(0x33FFFFFF), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 13.sp,
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
                    .background(Color(0xFF00E5FF), CircleShape),
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
