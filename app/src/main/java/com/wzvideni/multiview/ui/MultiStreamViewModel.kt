package com.wzvideni.multiview.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wzvideni.multiview.model.LayoutMode
import com.wzvideni.multiview.model.StreamChannel
import com.wzvideni.multiview.model.StreamStatus
import com.wzvideni.multiview.state.MultiViewAction
import com.wzvideni.multiview.state.MultiViewState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 多路监控流视图模型
 */
open class MultiStreamViewModel : ViewModel() {

    companion object {
        private const val TAG = "MultiStreamVM"
        const val DEFAULT_RTSP_HOST = "127.0.0.1:8554"
    }

    private val _uiState = MutableStateFlow(MultiViewState())
    val uiState: StateFlow<MultiViewState> = _uiState.asStateFlow()

    private var tooltipJob: Job? = null
    private var toastJob: Job? = null

    init {
        // 默认初始化 8 路模拟监控流（自适应 9 宫格，契合监控平板规范）
        loadDefaultChannels(count = 8)
    }

    /**
     * 加载/更新流列表（支持 1 ~ 32 路）
     */
    fun loadDefaultChannels(count: Int = 8) {
        val safeCount = count.coerceIn(0, 32)
        val list = (0 until safeCount).map { index ->
            val channelNum = String.format("%02d", index + 1)
            val subUrl = "rtsp://$DEFAULT_RTSP_HOST/live/sub$channelNum"
            val mainUrl = "rtsp://$DEFAULT_RTSP_HOST/live/main$channelNum"
            StreamChannel(
                id = "stream_$channelNum",
                channelIndex = index,
                name = "CAM $channelNum ${getChannelLocationName(index)}",
                rtspUrl = subUrl,
                subRtspUrl = subUrl,
                mainRtspUrl = mainUrl,
                isMainStream = false,
                status = StreamStatus.IDLE,
                resolution = "640x360",
                fps = 15,
                bitrateKbps = 512,
                isSelected = (index == 0)
            )
        }

        _uiState.update { current ->
            current.copy(
                channels = list,
                layoutMode = LayoutMode.AUTO,
                selectedChannelIndex = 0,
                currentPage = 0,
                showTooltip = false
            )
        }
    }

    fun onAction(action: MultiViewAction) {
        when (action) {
            is MultiViewAction.SelectChannel -> {
                Log.d(TAG, "Channel selected: ${action.channelIndex}")
                _uiState.update { current ->
                    current.copy(
                        selectedChannelIndex = action.channelIndex,
                        showTooltip = true
                    )
                }
                // 3秒后自动淡出双击提示条
                tooltipJob?.cancel()
                tooltipJob = viewModelScope.launch {
                    delay(3000)
                    _uiState.update { it.copy(showTooltip = false) }
                }
            }

            is MultiViewAction.ToggleFullscreen -> {
                val targetIndex = action.channelIndex
                _uiState.update { current ->
                    val willBeFullscreen = !current.isFullscreen || current.fullscreenChannelIndex != targetIndex
                    Log.d(TAG, "Toggle Fullscreen for channel $targetIndex -> $willBeFullscreen")
                    current.copy(
                        isFullscreen = willBeFullscreen,
                        fullscreenChannelIndex = if (willBeFullscreen) targetIndex else -1,
                        selectedChannelIndex = targetIndex,
                        showTooltip = false
                    )
                }
            }

            is MultiViewAction.ExitFullscreen -> {
                Log.d(TAG, "Exit Fullscreen")
                _uiState.update { current ->
                    current.copy(
                        isFullscreen = false,
                        fullscreenChannelIndex = -1
                    )
                }
            }

            is MultiViewAction.ChangeLayoutMode -> {
                Log.d(TAG, "Change Layout Mode: ${action.mode.title}")
                _uiState.update { current ->
                    current.copy(
                        layoutMode = action.mode,
                        currentPage = 0
                    )
                }
            }

            is MultiViewAction.SwitchPage -> {
                _uiState.update { current ->
                    val safePage = action.pageIndex.coerceIn(0, (current.totalPages - 1).coerceAtLeast(0))
                    current.copy(currentPage = safePage)
                }
            }

            is MultiViewAction.SwapChannels -> {
                val from = action.fromIndex
                val to = action.toIndex
                val currentChannels = _uiState.value.channels
                if (from in currentChannels.indices && to in currentChannels.indices && from != to) {
                    Log.i(TAG, "SwapChannels: $from <-> $to")
                    _uiState.update { current ->
                        val updated = current.channels.toMutableList()
                        val temp = updated[from]
                        updated[from] = updated[to].copy(channelIndex = from)
                        updated[to] = temp.copy(channelIndex = to)

                        val newSelected = when (current.selectedChannelIndex) {
                            from -> to
                            to -> from
                            else -> current.selectedChannelIndex
                        }
                        current.copy(
                            channels = updated,
                            selectedChannelIndex = newSelected
                        )
                    }
                    showToast("窗口顺序已调换")
                }
            }

            is MultiViewAction.CloseChannel -> {
                tooltipJob?.cancel()
                _uiState.update { current ->
                    val updated = current.channels.filterIndexed { idx, _ -> idx != action.channelIndex }
                        .mapIndexed { idx, ch -> ch.copy(channelIndex = idx) }
                    val safeSelected = if (current.selectedChannelIndex >= updated.size) {
                        (updated.size - 1).coerceAtLeast(0)
                    } else {
                        current.selectedChannelIndex
                    }
                    current.copy(
                        channels = updated,
                        selectedChannelIndex = safeSelected,
                        showTooltip = false
                    )
                }
                showToast("通道已关闭")
            }

            is MultiViewAction.SwitchStreamQuality -> {
                val idx = action.channelIndex
                _uiState.update { current ->
                    val updatedChannels = current.channels.mapIndexed { i, ch ->
                        if (i == idx) {
                            ch.copy(
                                isMainStream = action.useMainStream,
                                rtspUrl = if (action.useMainStream) ch.mainRtspUrl else ch.subRtspUrl,
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

            is MultiViewAction.SetTooltipVisible -> {
                _uiState.update { it.copy(showTooltip = action.visible) }
            }

            is MultiViewAction.UpdateChannelStatus -> {
                _uiState.update { current ->
                    if (action.channelIndex in current.channels.indices) {
                        val updated = current.channels.toMutableList()
                        val ch = updated[action.channelIndex]
                        if (ch.status != action.status) {
                            updated[action.channelIndex] = ch.copy(status = action.status)
                            current.copy(channels = updated)
                        } else {
                            current
                        }
                    } else {
                        current
                    }
                }
            }

            is MultiViewAction.SwitchToAdjacentChannel -> {
                _uiState.update { current ->
                    val total = current.channels.size
                    if (total <= 1) return@update current

                    val currentActiveIndex = if (current.isFullscreen && current.fullscreenChannelIndex >= 0) {
                        current.fullscreenChannelIndex
                    } else {
                        current.selectedChannelIndex
                    }

                    val newIndex = if (action.isNext) {
                        (currentActiveIndex + 1) % total
                    } else {
                        if (currentActiveIndex - 1 < 0) total - 1 else currentActiveIndex - 1
                    }

                    Log.d(TAG, "SwitchToAdjacentChannel: isNext=${action.isNext}, from $currentActiveIndex to $newIndex")
                    current.copy(
                        selectedChannelIndex = newIndex,
                        fullscreenChannelIndex = if (current.isFullscreen) newIndex else current.fullscreenChannelIndex
                    )
                }
            }

            is MultiViewAction.ClearToast -> {
                _uiState.update { it.copy(toastMessage = null) }
            }
        }
    }

    private fun showToast(msg: String) {
        toastJob?.cancel()
        _uiState.update { it.copy(toastMessage = msg) }
        toastJob = viewModelScope.launch {
            delay(2500)
            _uiState.update { it.copy(toastMessage = null) }
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
