package com.wzvideni.multiview.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wzvideni.multiview.state.MultiViewAction

/**
 * 完整的多路流监控页面（最多支持 32 路 RTSP 并发展示、单路点击感知与全屏放大）
 */
@Composable
fun MultiStreamScreen(
    modifier: Modifier = Modifier,
    viewModel: MultiStreamViewModel = viewModel(),
    onBackPressed: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()

    // 全屏模式下按返回键优先退出全屏
    BackHandler(enabled = state.isFullscreen) {
        viewModel.onAction(MultiViewAction.ExitFullscreen)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D111A))
    ) {
        // 非全屏状态下展示顶部导航与路数快速切换条
        if (!state.isFullscreen) {
            TopStreamNavBar(
                currentCount = state.streamCount,
                selectedChannelIndex = state.selectedChannelIndex,
                selectedChannelName = state.selectedChannel?.name ?: "",
                onBackPressed = onBackPressed,
                onSelectCount = { count -> viewModel.loadDefaultChannels(count) }
            )
        }

        // 核心多画面播放器区域
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            MultiStreamPlayerView(
                state = state,
                onAction = viewModel::onAction,
                modifier = Modifier.fillMaxSize(),
                showControls = true
            )
        }
    }
}

/**
 * 顶部监控标题与路数切换
 */
@Composable
private fun TopStreamNavBar(
    currentCount: Int,
    selectedChannelIndex: Int,
    selectedChannelName: String,
    onBackPressed: () -> Unit,
    onSelectCount: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF141A26))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBackPressed,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        Column {
            Text(
                text = "多路视频监控调度台",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "当前点击: CAM ${String.format("%02d", selectedChannelIndex + 1)} ($selectedChannelName)",
                color = Color(0xFF00E5FF),
                fontSize = 10.sp
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(text = "流数:", color = Color.Gray, fontSize = 11.sp)
        Spacer(modifier = Modifier.width(4.dp))

        // 快速切换路数 (4 / 9 / 16 / 25 / 32)
        val countOptions = listOf(4, 9, 16, 25, 32)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            countOptions.forEach { count ->
                val isSelected = (currentCount == count)
                Button(
                    onClick = { onSelectCount(count) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) Color(0xFF00B0FF) else Color(0xFF222C3E)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(width = 50.dp, height = 28.dp)
                ) {
                    Text(
                        text = "${count}路",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
