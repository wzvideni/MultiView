package com.wzvideni.multiview.layout

import com.wzvideni.multiview.model.LayoutMode

/**
 * 多画面布局计算引擎
 * 支持 1 ~ 32 路（及以上）视频流的几何网格分割与实时点击命中测试
 */
object MultiViewLayoutManager {

    /**
     * 获取指定模式下每页的槽位数
     */
    fun getSlotsPerPage(mode: LayoutMode, streamCount: Int): Int {
        val effectiveMode = if (mode == LayoutMode.AUTO) LayoutMode.getOptimalMode(streamCount) else mode
        return when (effectiveMode) {
            LayoutMode.GRID_1, LayoutMode.FULLSCREEN -> 1
            LayoutMode.GRID_2 -> 2
            LayoutMode.GRID_4 -> 4
            LayoutMode.GRID_6 -> 6
            LayoutMode.GRID_9 -> 9
            LayoutMode.GRID_16 -> 16
            LayoutMode.GRID_25 -> 25
            LayoutMode.GRID_32 -> 32
            LayoutMode.GRID_36 -> 36
            LayoutMode.ONE_PLUS_FIVE -> 6
            LayoutMode.ONE_PLUS_SEVEN -> 8
            LayoutMode.AUTO -> 9
        }
    }

    /**
     * 计算当前模式下的所有分屏槽位几何位置
     *
     * @param mode 布局模式
     * @param streamCount 当前加载的流总数（1..32）
     * @param pageIndex 当前分页索引 (0-based)
     * @param fullscreenChannelIndex 当处于全屏模式时指定的全屏通道索引
     * @param selectedChannelIndex 当前选中通道索引
     */
    fun calculateSlots(
        mode: LayoutMode,
        streamCount: Int,
        pageIndex: Int = 0,
        fullscreenChannelIndex: Int = -1,
        selectedChannelIndex: Int = 0
    ): List<StreamSlotRect> {
        val effectiveMode = if (mode == LayoutMode.AUTO) {
            LayoutMode.getOptimalMode(streamCount)
        } else {
            mode
        }

        // 全屏模式处理
        if (effectiveMode == LayoutMode.FULLSCREEN || fullscreenChannelIndex >= 0) {
            val targetIndex = if (fullscreenChannelIndex >= 0) fullscreenChannelIndex else selectedChannelIndex
            val validIndex = if (streamCount > 0) targetIndex.coerceIn(0, streamCount - 1) else -1
            return listOf(
                StreamSlotRect(
                    slotIndex = 0,
                    channelIndex = validIndex,
                    normalizedLeft = 0.0f,
                    normalizedTop = 0.0f,
                    normalizedRight = 1.0f,
                    normalizedBottom = 1.0f,
                    isEmptySlot = streamCount == 0
                )
            )
        }

        return when (effectiveMode) {
            LayoutMode.GRID_1 -> {
                val targetIndex = if (streamCount > 0) selectedChannelIndex.coerceIn(0, streamCount - 1) else -1
                listOf(
                    StreamSlotRect(
                        slotIndex = 0,
                        channelIndex = targetIndex,
                        normalizedLeft = 0.0f,
                        normalizedTop = 0.0f,
                        normalizedRight = 1.0f,
                        normalizedBottom = 1.0f,
                        isEmptySlot = streamCount == 0
                    )
                )
            }
            LayoutMode.GRID_2 -> {
                val startIndex = (pageIndex * 2).coerceAtMost((streamCount - 1).coerceAtLeast(0))
                generateGrid(rows = 1, cols = 2, streamCount = streamCount, startIndex = startIndex)
            }
            LayoutMode.GRID_4 -> {
                val startIndex = (pageIndex * 4).coerceAtMost((streamCount - 1).coerceAtLeast(0))
                generateGrid(rows = 2, cols = 2, streamCount = streamCount, startIndex = startIndex)
            }
            LayoutMode.GRID_6 -> {
                val startIndex = (pageIndex * 6).coerceAtMost((streamCount - 1).coerceAtLeast(0))
                generateGrid(rows = 2, cols = 3, streamCount = streamCount, startIndex = startIndex)
            }
            LayoutMode.GRID_9 -> {
                val startIndex = pageIndex * 9
                generateGrid(rows = 3, cols = 3, streamCount = streamCount, startIndex = startIndex)
            }
            LayoutMode.GRID_16 -> {
                val startIndex = pageIndex * 16
                generateGrid(rows = 4, cols = 4, streamCount = streamCount, startIndex = startIndex)
            }
            LayoutMode.GRID_25 -> {
                val startIndex = pageIndex * 25
                generateGrid(rows = 5, cols = 5, streamCount = streamCount, startIndex = startIndex)
            }
            LayoutMode.GRID_32 -> {
                val startIndex = pageIndex * 32
                generateGrid(rows = 4, cols = 8, streamCount = streamCount, startIndex = startIndex)
            }
            LayoutMode.GRID_36 -> {
                val startIndex = pageIndex * 36
                generateGrid(rows = 6, cols = 6, streamCount = streamCount, startIndex = startIndex)
            }
            LayoutMode.ONE_PLUS_FIVE -> {
                val startIndex = pageIndex * 6
                generateOnePlusFive(streamCount, startIndex = startIndex)
            }
            LayoutMode.ONE_PLUS_SEVEN -> {
                val startIndex = pageIndex * 8
                generateOnePlusSeven(streamCount, startIndex = startIndex)
            }
            else -> {
                val startIndex = pageIndex * 9
                generateGrid(rows = 3, cols = 3, streamCount = streamCount, startIndex = startIndex)
            }
        }
    }

    /**
     * 生成等分均匀网格
     */
    private fun generateGrid(
        rows: Int,
        cols: Int,
        streamCount: Int,
        startIndex: Int
    ): List<StreamSlotRect> {
        val totalSlots = rows * cols
        val cellWidth = 1.0f / cols
        val cellHeight = 1.0f / rows
        val result = ArrayList<StreamSlotRect>(totalSlots)

        var slotIdx = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val channelIdx = startIndex + slotIdx
                val hasChannel = channelIdx in 0 until streamCount

                val left = c * cellWidth
                val top = r * cellHeight
                val right = left + cellWidth
                val bottom = top + cellHeight

                result.add(
                    StreamSlotRect(
                        slotIndex = slotIdx,
                        channelIndex = if (hasChannel) channelIdx else -1,
                        normalizedLeft = left,
                        normalizedTop = top,
                        normalizedRight = right,
                        normalizedBottom = bottom,
                        isEmptySlot = !hasChannel
                    )
                )
                slotIdx++
            }
        }
        return result
    }

    /**
     * 生成 1大+5小 布局
     */
    private fun generateOnePlusFive(streamCount: Int, startIndex: Int): List<StreamSlotRect> {
        val list = mutableListOf<StreamSlotRect>()
        val geometries = listOf(
            floatArrayOf(0f, 0f, 2f / 3f, 2f / 3f),
            floatArrayOf(2f / 3f, 0f, 1f, 1f / 3f),
            floatArrayOf(2f / 3f, 1f / 3f, 1f, 2f / 3f),
            floatArrayOf(0f, 2f / 3f, 1f / 3f, 1f),
            floatArrayOf(1f / 3f, 2f / 3f, 2f / 3f, 1f),
            floatArrayOf(2f / 3f, 2f / 3f, 1f, 1f)
        )
        geometries.forEachIndexed { slotIdx, geo ->
            val channelIdx = startIndex + slotIdx
            val hasChannel = channelIdx in 0 until streamCount
            list.add(
                StreamSlotRect(
                    slotIndex = slotIdx,
                    channelIndex = if (hasChannel) channelIdx else -1,
                    normalizedLeft = geo[0],
                    normalizedTop = geo[1],
                    normalizedRight = geo[2],
                    normalizedBottom = geo[3],
                    isEmptySlot = !hasChannel
                )
            )
        }
        return list
    }

    /**
     * 生成 1大+7小 布局
     */
    private fun generateOnePlusSeven(streamCount: Int, startIndex: Int): List<StreamSlotRect> {
        val list = mutableListOf<StreamSlotRect>()
        val geometries = listOf(
            floatArrayOf(0f, 0f, 0.75f, 0.75f),
            floatArrayOf(0.75f, 0f, 1f, 0.25f),
            floatArrayOf(0.75f, 0.25f, 1f, 0.50f),
            floatArrayOf(0.75f, 0.50f, 1f, 0.75f),
            floatArrayOf(0.0f, 0.75f, 0.25f, 1f),
            floatArrayOf(0.25f, 0.75f, 0.50f, 1f),
            floatArrayOf(0.50f, 0.75f, 0.75f, 1f),
            floatArrayOf(0.75f, 0.75f, 1.0f, 1f)
        )
        geometries.forEachIndexed { slotIdx, geo ->
            val channelIdx = startIndex + slotIdx
            val hasChannel = channelIdx in 0 until streamCount
            list.add(
                StreamSlotRect(
                    slotIndex = slotIdx,
                    channelIndex = if (hasChannel) channelIdx else -1,
                    normalizedLeft = geo[0],
                    normalizedTop = geo[1],
                    normalizedRight = geo[2],
                    normalizedBottom = geo[3],
                    isEmptySlot = !hasChannel
                )
            )
        }
        return list
    }

    /**
     * 命中测试（Hit-Testing）
     * 输入点击的屏幕像素坐标 (pxX, pxY) 与视口总大小，返回命中的 StreamSlotRect
     *
     * @param pxX 点击的 X 像素坐标
     * @param pxY 点击的 Y 像素坐标
     * @param viewWidth 容器总宽度
     * @param viewHeight 容器总高度
     * @param slots 当前布局的槽位列表
     * @return 命中的 StreamSlotRect，如果未命中则返回 null
     */
    fun findSlotAt(
        pxX: Float,
        pxY: Float,
        viewWidth: Float,
        viewHeight: Float,
        slots: List<StreamSlotRect>
    ): StreamSlotRect? {
        if (viewWidth <= 0f || viewHeight <= 0f) return null
        val normalizedX = (pxX / viewWidth).coerceIn(0.0f, 1.0f)
        val normalizedY = (pxY / viewHeight).coerceIn(0.0f, 1.0f)

        return slots.firstOrNull { it.contains(normalizedX, normalizedY) }
    }
}
