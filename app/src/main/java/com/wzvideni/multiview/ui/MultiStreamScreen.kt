package com.wzvideni.multiview.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wzvideni.multiview.state.MultiViewAction

/**
 * 完整的多路流监控页面（支持 1 ~ 32 路 RTSP 并发展示、自适应分屏、长按拖动调换、全屏手势滑动）
 */
@Composable
fun MultiStreamScreen(
    modifier: Modifier = Modifier,
    viewModel: MultiStreamViewModel = viewModel(),
    onBackPressed: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 全屏模式下沉浸式隐藏系统栏 (状态栏与导航栏)
    val activity = remember(context) {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return@remember ctx
            ctx = ctx.baseContext
        }
        null
    }

    DisposableEffect(activity, state.isFullscreen) {
        val window = activity?.window
        if (window != null && state.isFullscreen) {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF141A26))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // 第一行：返回键、调度台标题与当前选中通道状态
        Row(
            modifier = Modifier.fillMaxWidth(),
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

            Text(
                text = "多路视频监控调度台",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.weight(1f))

            val displayName = selectedChannelName.ifEmpty { "CAM ${String.format("%02d", selectedChannelIndex + 1)}" }
            Box(
                modifier = Modifier
                    .background(Color(0x2600E5FF), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = displayName,
                    color = Color(0xFF00E5FF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 第二行：流数快速切换标签与各路数按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "预设流数:",
                color = Color(0xFFB0BEC5),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.width(8.dp))

            val countOptions = listOf(1, 2, 4, 6, 8, 9, 16, 25, 32)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                countOptions.forEach { count ->
                    val isSelected = (currentCount == count)
                    Button(
                        onClick = { onSelectCount(count) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) Color(0xFF00B0FF) else Color(0xFF222C3E)
                        ),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 1.dp, minHeight = 28.dp)
                            .height(28.dp)
                    ) {
                        Text(
                            text = "${count}路",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
