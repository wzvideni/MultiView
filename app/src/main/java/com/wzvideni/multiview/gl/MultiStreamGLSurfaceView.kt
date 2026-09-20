package com.wzvideni.multiview.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.wzvideni.multiview.layout.MultiViewLayoutManager
import com.wzvideni.multiview.model.LayoutMode

/**
 * 承载多路画面的 GLSurfaceView 容器
 */
class MultiStreamGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    val renderer = MultiStreamGLRenderer()

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /**
     * 根据像素坐标查找点击的通道槽位
     */
    fun findSlotAt(pxX: Float, pxY: Float): Int? {
        val slots = renderer.getCurrentSlots()
        return MultiViewLayoutManager.findSlotAt(
            pxX = pxX,
            pxY = pxY,
            viewWidth = width.toFloat(),
            viewHeight = height.toFloat(),
            slots = slots
        )
    }

    /**
     * 切换分屏布局
     */
    fun updateLayout(mode: LayoutMode, count: Int, fullscreenIndex: Int = -1, selectedIndex: Int = 0) {
        queueEvent {
            renderer.updateLayout(mode, count, fullscreenIndex, selectedIndex)
        }
    }

    /**
     * 设置高亮选中的通道
     */
    fun setSelectedChannel(index: Int) {
        queueEvent {
            renderer.setSelectedChannel(index)
        }
    }

    fun onDestroy() {
        queueEvent {
            renderer.release()
        }
    }
}
