package com.wzvideni.multiview.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import com.wzvideni.multiview.model.LayoutMode
import com.wzvideni.multiview.model.StreamChannel
import com.wzvideni.multiview.model.StreamStatus
import com.wzvideni.multiview.state.MultiViewAction
import com.wzvideni.multiview.state.MultiViewState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 多路监控流视图模型
 */
open class MultiStreamViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MultiViewState())
    val uiState: StateFlow<MultiViewState> = _uiState.asStateFlow()

    init {
        // 默认初始化 32 路模拟监控流
        loadDefaultChannels(count = 32)
    }

    /**
     * 加载/更新流列表（支持 1 ~ 32 路）
     */
    fun loadDefaultChannels(count: Int = 32) {
        val safeCount = count.coerceIn(1, 32)
        val list = (0 until safeCount).map { index ->
            val channelNum = String.format("%02d", index + 1)
            StreamChannel(
                id = "stream_$channelNum",
                channelIndex = index,
                name = "CAM $channelNum ${getChannelLocationName(index)}",
                rtspUrl = "rtsp://192.168.1.100:554/live/sub$channelNum",
                subRtspUrl = "rtsp://192.168.1.100:554/live/sub$channelNum",
                mainRtspUrl = "rtsp://192.168.1.100:554/live/main$channelNum",
                isMainStream = false,
                status = StreamStatus.PLAYING,
                resolution = "640x360",
                fps = 15,
                bitrateKbps = 512,
                isSelected = (index == 0)
            )
        }

        val targetMode = when {
            safeCount <= 1 -> LayoutMode.GRID_1
            safeCount <= 4 -> LayoutMode.GRID_4
            safeCount <= 9 -> LayoutMode.GRID_9
            safeCount <= 16 -> LayoutMode.GRID_16
            safeCount <= 25 -> LayoutMode.GRID_25
            else -> LayoutMode.GRID_32
        }

        _uiState.update { current ->
            current.copy(
                channels = list,
                layoutMode = targetMode,
                selectedChannelIndex = 0
            )
        }
    }

    fun onAction(action: MultiViewAction) {
        when (action) {
            is MultiViewAction.SelectChannel -> {
                Log.d("MultiView", "Channel selected: ${action.channelIndex}")
                _uiState.update { current ->
                    current.copy(selectedChannelIndex = action.channelIndex)
                }
            }

            is MultiViewAction.ToggleFullscreen -> {
                val targetIndex = action.channelIndex
                _uiState.update { current ->
                    val willBeFullscreen = !current.isFullscreen || current.fullscreenChannelIndex != targetIndex
                    Log.d("MultiView", "Toggle Fullscreen for channel $targetIndex -> $willBeFullscreen")
                    current.copy(
                        isFullscreen = willBeFullscreen,
                        fullscreenChannelIndex = if (willBeFullscreen) targetIndex else -1,
                        selectedChannelIndex = targetIndex
                    )
                }
            }

            is MultiViewAction.ExitFullscreen -> {
                Log.d("MultiView", "Exit Fullscreen")
                _uiState.update { current ->
                    current.copy(
                        isFullscreen = false,
                        fullscreenChannelIndex = -1
                    )
                }
            }

            is MultiViewAction.ChangeLayoutMode -> {
                Log.d("MultiView", "Change Layout Mode: ${action.mode.title}")
                _uiState.update { current ->
                    current.copy(layoutMode = action.mode)
                }
            }

            is MultiViewAction.SwitchStreamQuality -> {
                val idx = action.channelIndex
                _uiState.update { current ->
                    val updatedChannels = current.channels.mapIndexed { i, ch ->
                        if (i == idx) {
                            ch.copy(
                                isMainStream = action.useMainStream,
                                resolution = if (action.useMainStream) "1920x1080" else "640x360",
                                bitrateKbps = if (action.useMainStream) 2048 else 512,
                                fps = if (action.useMainStream) 25 else 15
                            )
                        } else ch
                    }
                    current.copy(channels = updatedChannels)
                }
            }

            is MultiViewAction.ToggleAudio -> {
                val idx = action.channelIndex
                _uiState.update { current ->
                    val updated = current.channels.mapIndexed { i, ch ->
                        if (i == idx) ch.copy(isMuted = !ch.isMuted) else ch
                    }
                    current.copy(channels = updated)
                }
            }

            is MultiViewAction.TogglePtzControl -> {
                _uiState.update { current ->
                    current.copy(isPtzControlVisible = action.visible)
                }
            }
        }
    }

    private fun getChannelLocationName(index: Int): String {
        val locations = arrayOf(
            "东大门", "西大门", "南大门", "北大门",
            "周界01", "周界02", "周界03", "周界04",
            "大堂前台", "1号电梯厅", "2号电梯厅", "消防通道",
            "地下车库A", "地下车库B", "物资仓库", "配电房",
            "办公区A", "办公区B", "走廊01", "走廊02",
            "天台出口", "机房外侧", "食堂大厅", "装卸货区",
            "围墙北段", "围墙东段", "围墙南段", "围墙西段",
            "高点全景01", "高点全景02", "应急指挥点", "总控机房"
        )
        return locations.getOrElse(index) { "监控位" }
    }
}
