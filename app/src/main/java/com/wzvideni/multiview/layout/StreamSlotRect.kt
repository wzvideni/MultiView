package com.wzvideni.multiview.layout

import android.graphics.RectF

/**
 * 单个流画面的几何视口定义（归一化坐标系 0.0 ~ 1.0）
 */
data class StreamSlotRect(
    val slotIndex: Int,
    val channelIndex: Int = slotIndex,
    val normalizedLeft: Float,
    val normalizedTop: Float,
    val normalizedRight: Float,
    val normalizedBottom: Float,
    val isEmptySlot: Boolean = false
) {
    val width: Float get() = normalizedRight - normalizedLeft
    val height: Float get() = normalizedBottom - normalizedTop
    val normalizedWidth: Float get() = width
    val normalizedHeight: Float get() = height

    /**
     * 判断归一化点是否在此槽位内
     */
    fun contains(x: Float, y: Float): Boolean {
        return x in normalizedLeft..normalizedRight && y in normalizedTop..normalizedBottom
    }

    /**
     * 转换为 Android View 像素矩形（用于 Compose UI 叠加层及命中测试）
     * Android 坐标系：原点在左上角，Y 向下
     */
    fun toPixelRect(viewWidth: Float, viewHeight: Float): RectF {
        return RectF(
            normalizedLeft * viewWidth,
            normalizedTop * viewHeight,
            normalizedRight * viewWidth,
            normalizedBottom * viewHeight
        )
    }

    /**
     * 转换为 OpenGL ES glViewport 矩形
     * OpenGL 坐标系：原点在左下角，Y 向上
     */
    fun toGLViewport(surfaceWidth: Int, surfaceHeight: Int): GLViewport {
        val x = (normalizedLeft * surfaceWidth).toInt()
        val w = (normalizedWidth * surfaceWidth).toInt().coerceAtLeast(1)
        val h = (normalizedHeight * surfaceHeight).toInt().coerceAtLeast(1)
        // OpenGL 的 Y 坐标计算：以左下角为基准
        val y = ((1.0f - normalizedBottom) * surfaceHeight).toInt().coerceAtLeast(0)
        return GLViewport(x = x, y = y, width = w, height = h)
    }
}

/**
 * OpenGL 视口参数
 */
data class GLViewport(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)
