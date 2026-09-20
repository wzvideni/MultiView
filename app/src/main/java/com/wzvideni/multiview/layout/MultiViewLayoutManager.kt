package com.wzvideni.multiview.layout

import com.wzvideni.multiview.model.LayoutMode

/**
 * 多画面布局计算引擎
 * 支持 1 ~ 32 路（及以上）视频流的几何网格分割与实时点击命中测试
 */
object MultiViewLayoutManager {

    /**
     * 计算当前模式下的所有分屏槽位几何位置
     *
     * @param mode 布局模式
     * @param streamCount 当前加载的流总数（1..32）
     * @param fullscreenChannelIndex 当处于全屏模式时指定的全屏通道索引
     */
    fun calculateSlots(
        mode: LayoutMode,
        streamCount: Int,
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
            val validIndex = if (streamCount > 0) targetIndex.coerceIn(0, streamCount - 1) else 0
            return listOf(
                StreamSlotRect(
                    slotIndex = validIndex,
                    normalizedLeft = 0.0f,
                    normalizedTop = 0.0f,
                    normalizedRight = 1.0f,
                    normalizedBottom = 1.0f
                )
            )
        }

        return when (effectiveMode) {
            LayoutMode.GRID_1 -> {
                val validIndex = if (streamCount > 0) selectedChannelIndex.coerceIn(0, streamCount - 1) else 0
                listOf(
                    StreamSlotRect(
                        slotIndex = validIndex,
                        normalizedLeft = 0.0f,
                        normalizedTop = 0.0f,
                        normalizedRight = 1.0f,
                        normalizedBottom = 1.0f
                    )
                )
            }
            LayoutMode.GRID_4 -> generateUniformGrid(rows = 2, cols = 2)
            LayoutMode.GRID_9 -> generateUniformGrid(rows = 3, cols = 3)
            LayoutMode.GRID_16 -> generateUniformGrid(rows = 4, cols = 4)
            LayoutMode.GRID_25 -> generateUniformGrid(rows = 5, cols = 5)
            LayoutMode.GRID_32 -> generateUniformGrid(rows = 4, cols = 8) // 4行8列 = 32画面，适应宽屏
            LayoutMode.GRID_36 -> generateUniformGrid(rows = 6, cols = 6)
            LayoutMode.ONE_PLUS_FIVE -> generateOnePlusFive()
            LayoutMode.ONE_PLUS_SEVEN -> generateOnePlusSeven()
            else -> generateUniformGrid(rows = 4, cols = 4)
        }
    }

    /**
     * 生成等分均匀网格
     */
    private fun generateUniformGrid(rows: Int, cols: Int): List<StreamSlotRect> {
        val totalSlots = rows * cols
        val cellWidth = 1.0f / cols
        val cellHeight = 1.0f / rows
        val result = ArrayList<StreamSlotRect>(totalSlots)

        var index = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = c * cellWidth
                val top = r * cellHeight
                val right = left + cellWidth
                val bottom = top + cellHeight
                result.add(
                    StreamSlotRect(
                        slotIndex = index++,
                        normalizedLeft = left,
                        normalizedTop = top,
                        normalizedRight = right,
                        normalizedBottom = bottom
                    )
                )
            }
        }
        return result
    }

    /**
     * 生成 1大+5小 布局
     */
    private fun generateOnePlusFive(): List<StreamSlotRect> {
        val list = mutableListOf<StreamSlotRect>()
        // 0: 左上大画面 2/3 宽高
        list.add(StreamSlotRect(0, 0f, 0f, 2f / 3f, 2f / 3f))
        // 1: 右上一
        list.add(StreamSlotRect(1, 2f / 3f, 0f, 1f, 1f / 3f))
        // 2: 右上二
        list.add(StreamSlotRect(2, 2f / 3f, 1f / 3f, 1f, 2f / 3f))
        // 3: 左下
        list.add(StreamSlotRect(3, 0f, 2f / 3f, 1f / 3f, 1f))
        // 4: 中下
        list.add(StreamSlotRect(4, 1f / 3f, 2f / 3f, 2f / 3f, 1f))
        // 5: 右下
        list.add(StreamSlotRect(5, 2f / 3f, 2f / 3f, 1f, 1f))
        return list
    }

    /**
     * 生成 1大+7小 布局
     */
    private fun generateOnePlusSeven(): List<StreamSlotRect> {
        val list = mutableListOf<StreamSlotRect>()
        // 0: 左上大画面 3/4 宽高
        list.add(StreamSlotRect(0, 0f, 0f, 0.75f, 0.75f))
        // 1..3: 右侧一列 3 个小画面
        list.add(StreamSlotRect(1, 0.75f, 0f, 1f, 0.25f))
        list.add(StreamSlotRect(2, 0.75f, 0.25f, 1f, 0.50f))
        list.add(StreamSlotRect(3, 0.75f, 0.50f, 1f, 0.75f))
        // 4..7: 底部一行 4 个小画面
        list.add(StreamSlotRect(4, 0.0f, 0.75f, 0.25f, 1f))
        list.add(StreamSlotRect(5, 0.25f, 0.75f, 0.50f, 1f))
        list.add(StreamSlotRect(6, 0.50f, 0.75f, 0.75f, 1f))
        list.add(StreamSlotRect(7, 0.75f, 0.75f, 1.0f, 1f))
        return list
    }

    /**
     * 命中测试（Hit-Testing）
     * 输入点击的屏幕像素坐标 (pxX, pxY) 与视口总大小，返回具体命中的通道槽位索引
     *
     * @param pxX 点击的 X 像素坐标
     * @param pxY 点击的 Y 像素坐标
     * @param viewWidth 容器总宽度
     * @param viewHeight 容器总高度
     * @param slots 当前布局的槽位列表
     * @return 命中的 slotIndex，如果未命中则返回 null
     */
    fun findSlotAt(
        pxX: Float,
        pxY: Float,
        viewWidth: Float,
        viewHeight: Float,
        slots: List<StreamSlotRect>
    ): Int? {
        if (viewWidth <= 0f || viewHeight <= 0f) return null
        val normalizedX = (pxX / viewWidth).coerceIn(0.0f, 1.0f)
        val normalizedY = (pxY / viewHeight).coerceIn(0.0f, 1.0f)

        return slots.firstOrNull { it.contains(normalizedX, normalizedY) }?.slotIndex
    }
}
