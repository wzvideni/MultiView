package com.wzvideni.multiview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wzvideni.multiview.model.LayoutMode

/**
 * 分屏模式底部的控制栏（支持切换 1/4/9/16/25/32 分屏及特色 1+5 布局）
 */
@Composable
fun MultiStreamControls(
    currentMode: LayoutMode,
    totalStreams: Int,
    selectedChannelIndex: Int,
    onModeSelected: (LayoutMode) -> Unit,
    onEnterFullscreen: (channelIndex: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = listOf(
        LayoutMode.GRID_1,
        LayoutMode.GRID_4,
        LayoutMode.GRID_9,
        LayoutMode.GRID_16,
        LayoutMode.GRID_25,
        LayoutMode.GRID_32,
        LayoutMode.ONE_PLUS_FIVE,
        LayoutMode.ONE_PLUS_SEVEN
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xEE161D2B))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 状态信息统计
        Text(
            text = "流数: $totalStreams 路",
            color = Color(0xFF00E5FF),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "选中: CAM ${String.format("%02d", selectedChannelIndex + 1)}",
            color = Color.LightGray,
            fontSize = 11.sp
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 当前选中通道快捷全屏按钮
        Button(
            onClick = { onEnterFullscreen(selectedChannelIndex) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.height(28.dp)
        ) {
            Text("放大全屏", color = Color.White, fontSize = 10.sp)
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 可横向滑动的分屏切换按钮组
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            modes.forEach { mode ->
                val isSelected = (mode == currentMode)
                Button(
                    onClick = { onModeSelected(mode) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) Color(0xFF00E676) else Color(0xFF263248)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        text = mode.title,
                        color = if (isSelected) Color.Black else Color.White,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
