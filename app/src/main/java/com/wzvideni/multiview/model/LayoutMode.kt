package com.wzvideni.multiview.model

/**
 * 多画面分屏布局模式
 */
enum class LayoutMode(val title: String, val maxSlots: Int) {
    AUTO("自适应", 9),
    GRID_1("单画面", 1),
    GRID_2("2 分屏", 2),
    GRID_4("4 分屏", 4),
    GRID_6("6 分屏", 6),
    GRID_9("9 分屏", 9),
    GRID_16("16 分屏", 16),
    GRID_25("25 分屏", 25),
    GRID_32("32 分屏 (4x8)", 32),
    GRID_36("36 分屏 (6x6)", 36),
    ONE_PLUS_FIVE("1+5 模式", 6),
    ONE_PLUS_SEVEN("1+7 模式", 8),
    FULLSCREEN("全屏模式", 1);

    companion object {
        /**
         * 根据流数量自动匹配最合适的分屏模式
         * 1路/2路/3-4路/5-6路/7-9路/9+路分页
         */
        fun getOptimalMode(streamCount: Int): LayoutMode {
            return when {
                streamCount <= 1 -> GRID_1
                streamCount == 2 -> GRID_2
                streamCount <= 4 -> GRID_4
                streamCount <= 6 -> GRID_6
                else -> GRID_9
            }
        }
    }
}
