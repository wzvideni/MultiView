package com.wzvideni.multiview.state

import com.wzvideni.multiview.layout.MultiViewLayoutManager
import com.wzvideni.multiview.model.LayoutMode
import com.wzvideni.multiview.model.StreamChannel
import com.wzvideni.multiview.model.StreamStatus

/**
 * 多画面播放器的整体 UI 状态
 */
data class MultiViewState(
    val channels: List<StreamChannel> = emptyList(),
    val selectedChannelIndex: Int = 0,
    val layoutMode: LayoutMode = LayoutMode.AUTO,
    val isFullscreen: Boolean = false,
    val fullscreenChannelIndex: Int = -1,
    val currentPage: Int = 0,
    val showTooltip: Boolean = false,
    val toastMessage: String? = null,
    val isTestPatternEnabled: Boolean = true,
    val isPtzControlVisible: Boolean = false,
    val isInfoBarVisible: Boolean = true
) {
    val streamCount: Int get() = channels.size

    val slotsPerPage: Int
        get() = MultiViewLayoutManager.getSlotsPerPage(layoutMode, streamCount)

    val totalPages: Int
        get() {
            val perPage = slotsPerPage
            return if (streamCount <= perPage) 1 else ((streamCount + perPage - 1) / perPage)
        }

    val selectedChannel: StreamChannel?
        get() = channels.getOrNull(selectedChannelIndex)

    val currentFullscreenChannel: StreamChannel?
        get() = if (isFullscreen && fullscreenChannelIndex in channels.indices) {
            channels[fullscreenChannelIndex]
        } else null
}

/**
 * 多画面交互事件与动作
 */
sealed interface MultiViewAction {
    /** 单击选中某个通道画面 */
    data class SelectChannel(val channelIndex: Int) : MultiViewAction

    /** 双击画面或点击全屏按钮切换放大全屏 */
    data class ToggleFullscreen(val channelIndex: Int) : MultiViewAction

    /** 退出全屏，恢复分屏宫格 */
    data object ExitFullscreen : MultiViewAction

    /** 切换宫格布局模式 */
    data class ChangeLayoutMode(val mode: LayoutMode) : MultiViewAction

    /** 切换分页 (9路及以上分页滑动) */
    data class SwitchPage(val pageIndex: Int) : MultiViewAction

    /** 左右滑动切换上一路/下一路单画面播放 (isNext=true 表示向左滑动切下一路，isNext=false 表示向右滑动切上一路) */
    data class SwitchToAdjacentChannel(val isNext: Boolean) : MultiViewAction

    /** 长按拖拽调换通道窗口顺序 */
    data class SwapChannels(val fromIndex: Int, val toIndex: Int) : MultiViewAction

    /** 关闭单个通道展示 */
    data class CloseChannel(val channelIndex: Int) : MultiViewAction

    /** 切换主码流 / 辅码流 (清晰度切换) */
    data class SwitchStreamQuality(val channelIndex: Int, val useMainStream: Boolean) : MultiViewAction

    /** 静音 / 取消静音 */
    data class ToggleAudio(val channelIndex: Int) : MultiViewAction

    /** 切换云台控制显隐 */
    data class TogglePtzControl(val visible: Boolean) : MultiViewAction

    /** 控制双击提示条显隐 */
    data class SetTooltipVisible(val visible: Boolean) : MultiViewAction

    /** 播放器状态变动反馈 */
    data class UpdateChannelStatus(val channelIndex: Int, val status: StreamStatus) : MultiViewAction

    /** 清除 Toast 提示 */
    data object ClearToast : MultiViewAction
}
