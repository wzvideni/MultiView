package com.wzvideni.multiview.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.SystemClock
import com.wzvideni.multiview.gl.GLShaderHelper
import com.wzvideni.multiview.layout.MultiViewLayoutManager
import com.wzvideni.multiview.layout.StreamSlotRect
import com.wzvideni.multiview.model.LayoutMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 核心 OpenGL ES 多画面合成渲染器（支持 1 ~ 32 路画面）
 *
 * 采用单 SurfaceView + 动态 Viewport 视口复用技术：
 * 1. 杜绝创建 16~32 个 View/TextureView 的显存消耗与系统图层合成压力；
 * 2. 保证各通道独立渲染与超低时延；
 * 3. 完美支持单路全屏无缝切换与命中测试联动。
 */
class MultiStreamGLRenderer : GLSurfaceView.Renderer, IStreamFrameFeeder {

    companion object {
        const val MAX_CHANNELS = 32

        private val VERTICES = floatArrayOf(
            -1.0f,  1.0f, 0.0f, // 左上
            -1.0f, -1.0f, 0.0f, // 左下
             1.0f, -1.0f, 0.0f, // 右下
             1.0f,  1.0f, 0.0f  // 右上
        )

        private val TEX_COORDS = floatArrayOf(
            0.0f, 0.0f, // 对应左上
            0.0f, 1.0f, // 对应左下
            1.0f, 1.0f, // 对应右下
            1.0f, 0.0f  // 对应右上
        )

        private val INDICES = shortArrayOf(0, 1, 2, 0, 2, 3)

        private const val VERTEX_SHADER_CODE = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val TEXTURE_FRAGMENT_SHADER_CODE = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        private const val PROCEDURAL_FRAGMENT_SHADER_CODE = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform vec3 uBaseColor;
            uniform float uTime;
            uniform float uChannelIndex;
            void main() {
                // 工业级监控背景：深色微网格与动态扫描效果
                float scan = sin((vTexCoord.y * 30.0) + (uTime * 1.5)) * 0.03;
                float gridX = step(0.01, mod(vTexCoord.x * 10.0, 1.0));
                float gridY = step(0.01, mod(vTexCoord.y * 10.0, 1.0));
                float grid = (1.0 - gridX * gridY) * 0.04;
                
                vec3 col = uBaseColor * (0.8 + scan + grid);
                
                // 边缘边框
                if (vTexCoord.x < 0.015 || vTexCoord.x > 0.985 || vTexCoord.y < 0.015 || vTexCoord.y > 0.985) {
                    col = vec3(0.08, 0.12, 0.16);
                }
                gl_FragColor = vec4(col, 1.0);
            }
        """
    }

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(VERTICES)
            position(0)
        }

    private val texCoordBuffer: FloatBuffer = ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(TEX_COORDS)
            position(0)
        }

    private val indexBuffer: ShortBuffer = ByteBuffer.allocateDirect(INDICES.size * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
        .apply {
            put(INDICES)
            position(0)
        }

    // GL 状态
    private var textureProgram = 0
    private var proceduralProgram = 0
    private val textureIds = IntArray(MAX_CHANNELS)
    private val hasRealFrame = BooleanArray(MAX_CHANNELS) { false }

    @Volatile
    private var surfaceWidth = 1920

    @Volatile
    private var surfaceHeight = 1080

    @Volatile
    private var layoutMode: LayoutMode = LayoutMode.GRID_16

    @Volatile
    private var streamCount: Int = 16

    @Volatile
    private var fullscreenChannelIndex: Int = -1 // -1 代表非全屏（宫格模式）

    @Volatile
    private var selectedChannelIndex: Int = 0

    @Volatile
    private var isTestPatternEnabled: Boolean = true

    private var currentSlots: List<StreamSlotRect> = emptyList()
    private val frameQueue = ConcurrentHashMap<Int, PendingFrame>()

    private class PendingFrame(
        val width: Int,
        val height: Int,
        val buffer: ByteBuffer
    )

    // 颜色配置（为 32 路提供区分底色）
    private val channelBaseColors = Array(MAX_CHANNELS) { i ->
        floatArrayOf(
            0.10f + (i % 4) * 0.03f,
            0.12f + ((i / 4) % 4) * 0.03f,
            0.15f + ((i / 8) % 4) * 0.03f
        )
    }

    private val startTime = SystemClock.uptimeMillis()

    /**
     * 更新布局模式与流数量
     */
    fun updateLayout(mode: LayoutMode, count: Int, fullscreenIndex: Int = -1) {
        this.layoutMode = mode
        this.streamCount = count.coerceIn(1, MAX_CHANNELS)
        this.fullscreenChannelIndex = fullscreenIndex
        this.currentSlots = MultiViewLayoutManager.calculateSlots(mode, streamCount, fullscreenIndex)
    }

    /**
     * 设置选中通道
     */
    fun setSelectedChannel(index: Int) {
        this.selectedChannelIndex = index
    }

    /**
     * 获取当前计算好的几何槽位列表
     */
    fun getCurrentSlots(): List<StreamSlotRect> = currentSlots

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.04f, 0.05f, 0.07f, 1.0f)

        textureProgram = GLShaderHelper.createProgram(VERTEX_SHADER_CODE, TEXTURE_FRAGMENT_SHADER_CODE)
        proceduralProgram = GLShaderHelper.createProgram(VERTEX_SHADER_CODE, PROCEDURAL_FRAGMENT_SHADER_CODE)

        // 初始化 32 个纹理
        GLES20.glGenTextures(MAX_CHANNELS, textureIds, 0)
        for (i in 0 until MAX_CHANNELS) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[i])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // 为每个通道初始化一张默认文字占位图（显示通道序号与等待提示）
            val bitmap = createPlaceholderBitmap(i)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
        }

        updateLayout(layoutMode, streamCount, fullscreenChannelIndex)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.surfaceWidth = width
        this.surfaceHeight = height
        updateLayout(layoutMode, streamCount, fullscreenChannelIndex)
    }

    override fun onDrawFrame(gl: GL10?) {
        // 清屏（全局背景深蓝黑色）
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val currentTimeSec = (SystemClock.uptimeMillis() - startTime) / 1000f

        // 上传等待中的视频帧到 GL 纹理
        uploadPendingFrames()

        val slots = currentSlots
        if (slots.isEmpty()) return

        // 全屏模式处理
        if (fullscreenChannelIndex in 0 until MAX_CHANNELS) {
            drawChannel(
                channelIndex = fullscreenChannelIndex,
                glX = 0,
                glY = 0,
                glW = surfaceWidth,
                glH = surfaceHeight,
                timeSec = currentTimeSec
            )
            return
        }

        // 宫格模式：遍历每一个视口并绘制对应通道
        for (slot in slots) {
            val channelIdx = slot.slotIndex
            if (channelIdx >= MAX_CHANNELS) continue

            val vp = slot.toGLViewport(surfaceWidth, surfaceHeight)
            drawChannel(
                channelIndex = channelIdx,
                glX = vp.x,
                glY = vp.y,
                glW = vp.width,
                glH = vp.height,
                timeSec = currentTimeSec
            )
        }
    }

    private fun drawChannel(channelIndex: Int, glX: Int, glY: Int, glW: Int, glH: Int, timeSec: Float) {
        // 设置当前分屏的裁剪与视口
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(glX, glY, glW, glH)
        GLES20.glViewport(glX, glY, glW, glH)

        val hasFrame = hasRealFrame[channelIndex]

        if (hasFrame || !isTestPatternEnabled) {
            // 使用纹理渲染真实流画面（或默认卡片）
            GLES20.glUseProgram(textureProgram)

            val aPosition = GLES20.glGetAttribLocation(textureProgram, "aPosition")
            val aTexCoord = GLES20.glGetAttribLocation(textureProgram, "aTexCoord")
            val uTexture = GLES20.glGetUniformLocation(textureProgram, "uTexture")

            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[channelIndex])
            GLES20.glUniform1i(uTexture, 0)

            GLES20.glDrawElements(GLES20.GL_TRIANGLES, INDICES.size, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTexCoord)
        } else {
            // 使用动态扫描着色器渲染演示模拟流（超低开销，呈现科技感动态画面）
            GLES20.glUseProgram(proceduralProgram)

            val aPosition = GLES20.glGetAttribLocation(proceduralProgram, "aPosition")
            val aTexCoord = GLES20.glGetAttribLocation(proceduralProgram, "aTexCoord")
            val uBaseColor = GLES20.glGetUniformLocation(proceduralProgram, "uBaseColor")
            val uTime = GLES20.glGetUniformLocation(proceduralProgram, "uTime")
            val uChannelIndex = GLES20.glGetUniformLocation(proceduralProgram, "uChannelIndex")

            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

            val baseColor = channelBaseColors[channelIndex]
            GLES20.glUniform3f(uBaseColor, baseColor[0], baseColor[1], baseColor[2])
            GLES20.glUniform1f(uTime, timeSec)
            GLES20.glUniform1f(uChannelIndex, channelIndex.toFloat())

            GLES20.glDrawElements(GLES20.GL_TRIANGLES, INDICES.size, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTexCoord)
        }

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    private fun uploadPendingFrames() {
        for (i in 0 until MAX_CHANNELS) {
            val pending = frameQueue.remove(i) ?: continue
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[i])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                pending.width,
                pending.height,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                pending.buffer
            )
            hasRealFrame[i] = true
        }
    }

    private fun createPlaceholderBitmap(channelIndex: Int): Bitmap {
        val width = 320
        val height = 180
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 绘制深色工业背景
        canvas.drawColor(Color.rgb(18, 24, 32))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 50, 65)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawRect(Rect(0, 0, width, height), paint)

        // 绘制通道文字与图标示意
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 160, 200)
            textSize = 28f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val channelNum = String.format("%02d", channelIndex + 1)
        canvas.drawText("CAM $channelNum", width / 2f, height / 2f - 8f, textPaint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(80, 100, 120)
            textSize = 18f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("等待推流 (RTSP)", width / 2f, height / 2f + 24f, subPaint)

        return bitmap
    }

    // --- IStreamFrameFeeder 接口实现 ---

    override fun feedRgbaFrame(channelIndex: Int, width: Int, height: Int, rgbaBuffer: ByteBuffer) {
        if (channelIndex in 0 until MAX_CHANNELS) {
            frameQueue[channelIndex] = PendingFrame(width, height, rgbaBuffer)
        }
    }

    override fun feedYuvFrame(
        channelIndex: Int,
        width: Int,
        height: Int,
        yData: ByteBuffer,
        uData: ByteBuffer,
        vData: ByteBuffer
    ) {
        // 后续对接 Native YUV 解码时使用
    }

    override fun setTestPatternEnabled(enabled: Boolean) {
        this.isTestPatternEnabled = enabled
    }

    override fun clearChannel(channelIndex: Int) {
        if (channelIndex in 0 until MAX_CHANNELS) {
            hasRealFrame[channelIndex] = false
            frameQueue.remove(channelIndex)
        }
    }

    fun release() {
        if (textureProgram != 0) {
            GLES20.glDeleteProgram(textureProgram)
            textureProgram = 0
        }
        if (proceduralProgram != 0) {
            GLES20.glDeleteProgram(proceduralProgram)
            proceduralProgram = 0
        }
        GLES20.glDeleteTextures(MAX_CHANNELS, textureIds, 0)
    }
}
