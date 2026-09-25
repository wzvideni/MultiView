package com.wzvideni.multiview.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.wzvideni.multiview.layout.MultiViewLayoutManager
import com.wzvideni.multiview.layout.StreamSlotRect
import com.wzvideni.multiview.model.LayoutMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 核心 OpenGL ES 多画面合成渲染器（支持 1 ~ 32 路画面）
 *
 * 采用单 SurfaceView + 动态 Viewport 视口复用 + SurfaceTexture 零拷贝硬件加速技术：
 * 1. 杜绝创建 16~32 个 View/TextureView 的显存消耗与系统图层合成压力；
 * 2. 支持 ExoPlayer / MediaCodec 硬件解码直接输出到 SurfaceTexture (GL_TEXTURE_EXTERNAL_OES)；
 * 3. 保证各通道独立渲染与超低时延，同时支持未接入真实流通道的科技感模拟画面；
 * 4. 完美支持单路全屏无缝切换、空槽位剔除、多页分屏与命中测试联动。
 */
class MultiStreamGLRenderer : GLSurfaceView.Renderer, IStreamFrameFeeder {

    companion object {
        private const val TAG = "MultiStreamGLRenderer"
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

        private val OES_TEX_COORDS = floatArrayOf(
            0.0f, 1.0f, // 对应左上 (符合 SurfaceTexture.getTransformMatrix 标准)
            0.0f, 0.0f, // 对应左下
            1.0f, 0.0f, // 对应右下
            1.0f, 1.0f  // 对应右上
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

        private const val OES_VERTEX_SHADER_CODE = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val OES_FRAGMENT_SHADER_CODE = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
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

    private val oesTexCoordBuffer: FloatBuffer = ByteBuffer.allocateDirect(OES_TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(OES_TEX_COORDS)
            position(0)
        }

    private val indexBuffer: ShortBuffer = ByteBuffer.allocateDirect(INDICES.size * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
        .apply {
            put(INDICES)
            position(0)
        }

    // GL 着色器程序
    private var textureProgram = 0
    private var proceduralProgram = 0
    private var oesProgram = 0

    // 缓存 Shader 变量句柄，消除 drawChannel 每帧频繁字符串查询带来的性能开销
    private var oesAPosition = -1
    private var oesATexCoord = -1
    private var oesUTexMatrix = -1
    private var oesUTexture = -1

    private var texAPosition = -1
    private var texATexCoord = -1
    private var texUTexture = -1

    private var procAPosition = -1
    private var procATexCoord = -1
    private var procUBaseColor = -1
    private var procUTime = -1
    private var procUChannelIndex = -1

    // 传统 2D 纹理（用于 feedRgbaFrame 离线帧）
    private val textureIds = IntArray(MAX_CHANNELS)
    private val hasRealFrame = BooleanArray(MAX_CHANNELS) { false }

    // OES 外部纹理与 SurfaceTexture（用于硬件解码直接输出）
    private val oesTextureIds = IntArray(MAX_CHANNELS)
    private val surfaceTextures = arrayOfNulls<SurfaceTexture>(MAX_CHANNELS)
    private val surfaces = arrayOfNulls<Surface>(MAX_CHANNELS)
    private val hasOesFrame = BooleanArray(MAX_CHANNELS) { false }
    private val frameAvailableFlags = Array(MAX_CHANNELS) { AtomicBoolean(false) }
    private val texMatrices = Array(MAX_CHANNELS) { FloatArray(16) }
    private var onSurfaceAvailableListener: ((channelIndex: Int, surface: Surface) -> Unit)? = null
    private var onFrameRenderedListener: ((channelIndex: Int) -> Unit)? = null

    @Volatile
    private var surfaceWidth = 1920

    @Volatile
    private var surfaceHeight = 1080

    @Volatile
    private var layoutMode: LayoutMode = LayoutMode.AUTO

    @Volatile
    private var streamCount: Int = 16

    @Volatile
    private var pageIndex: Int = 0

    @Volatile
    private var fullscreenChannelIndex: Int = -1 // -1 代表非全屏（宫格模式）

    @Volatile
    private var selectedChannelIndex: Int = 0

    @Volatile
    private var isTestPatternEnabled: Boolean = true

    private var currentSlots: List<StreamSlotRect> = emptyList()
    private val frameQueue = ConcurrentHashMap<Int, PendingFrame>()
    private val videoAspects = ConcurrentHashMap<Int, Float>()

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

    @Volatile
    private var isPortrait: Boolean = false

    @Volatile
    private var isSmallScreen: Boolean = false

    /**
     * 更新布局模式与流数量
     */
    fun updateLayout(
        mode: LayoutMode,
        count: Int,
        pageIndex: Int = 0,
        fullscreenIndex: Int = -1,
        selectedIndex: Int = 0,
        isPortrait: Boolean = false,
        isSmallScreen: Boolean = false
    ) {
        this.layoutMode = mode
        this.streamCount = count.coerceIn(0, MAX_CHANNELS)
        this.pageIndex = pageIndex
        this.fullscreenChannelIndex = fullscreenIndex
        this.selectedChannelIndex = selectedIndex
        this.isPortrait = isPortrait
        this.isSmallScreen = isSmallScreen
        this.currentSlots = MultiViewLayoutManager.calculateSlots(
            mode = mode,
            streamCount = streamCount,
            pageIndex = pageIndex,
            fullscreenChannelIndex = fullscreenIndex,
            selectedChannelIndex = selectedIndex,
            isPortrait = isPortrait,
            isSmallScreen = isSmallScreen
        )
    }

    /**
     * 设置选中通道
     */
    fun setSelectedChannel(index: Int) {
        this.selectedChannelIndex = index
        if (layoutMode == LayoutMode.GRID_1 || fullscreenChannelIndex >= 0) {
            this.currentSlots = MultiViewLayoutManager.calculateSlots(
                mode = layoutMode,
                streamCount = streamCount,
                pageIndex = pageIndex,
                fullscreenChannelIndex = fullscreenChannelIndex,
                selectedChannelIndex = index,
                isPortrait = isPortrait,
                isSmallScreen = isSmallScreen
            )
        }
    }

    /**
     * 获取当前计算好的几何槽位列表
     */
    fun getCurrentSlots(): List<StreamSlotRect> = currentSlots

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.043f, 0.067f, 0.078f, 1.0f) // #0B1114

        textureProgram = GLShaderHelper.createProgram(VERTEX_SHADER_CODE, TEXTURE_FRAGMENT_SHADER_CODE)
        texAPosition = GLES20.glGetAttribLocation(textureProgram, "aPosition")
        texATexCoord = GLES20.glGetAttribLocation(textureProgram, "aTexCoord")
        texUTexture = GLES20.glGetUniformLocation(textureProgram, "uTexture")

        proceduralProgram = GLShaderHelper.createProgram(VERTEX_SHADER_CODE, PROCEDURAL_FRAGMENT_SHADER_CODE)
        procAPosition = GLES20.glGetAttribLocation(proceduralProgram, "aPosition")
        procATexCoord = GLES20.glGetAttribLocation(proceduralProgram, "aTexCoord")
        procUBaseColor = GLES20.glGetUniformLocation(proceduralProgram, "uBaseColor")
        procUTime = GLES20.glGetUniformLocation(proceduralProgram, "uTime")
        procUChannelIndex = GLES20.glGetUniformLocation(proceduralProgram, "uChannelIndex")

        oesProgram = GLShaderHelper.createProgram(OES_VERTEX_SHADER_CODE, OES_FRAGMENT_SHADER_CODE)
        oesAPosition = GLES20.glGetAttribLocation(oesProgram, "aPosition")
        oesATexCoord = GLES20.glGetAttribLocation(oesProgram, "aTexCoord")
        oesUTexMatrix = GLES20.glGetUniformLocation(oesProgram, "uTexMatrix")
        oesUTexture = GLES20.glGetUniformLocation(oesProgram, "uTexture")

        // 1. 初始化 32 个传统 2D 纹理
        GLES20.glGenTextures(MAX_CHANNELS, textureIds, 0)
        for (i in 0 until MAX_CHANNELS) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[i])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val placeholder = createPlaceholderBitmap(i)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, placeholder, 0)
            placeholder.recycle()
        }

        // 2. 初始化 32 个硬件加速 OES 外部纹理及关联的 SurfaceTexture / Surface
        GLES20.glGenTextures(MAX_CHANNELS, oesTextureIds, 0)
        for (i in 0 until MAX_CHANNELS) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureIds[i])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            Matrix.setIdentityM(texMatrices[i], 0)

            val channelIndex = i
            val st = SurfaceTexture(oesTextureIds[i])
            st.setOnFrameAvailableListener {
                frameAvailableFlags[channelIndex].set(true)
                onFrameRenderedListener?.invoke(channelIndex)
            }
            surfaceTextures[i] = st
            val surf = Surface(st)
            surfaces[i] = surf

            // 通知外部监听者（如 ExoPlayer 管理器）该通道 Surface 已可用
            onSurfaceAvailableListener?.invoke(channelIndex, surf)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.surfaceWidth = width
        this.surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
        this.currentSlots = MultiViewLayoutManager.calculateSlots(
            mode = layoutMode,
            streamCount = streamCount,
            pageIndex = pageIndex,
            fullscreenChannelIndex = fullscreenChannelIndex,
            selectedChannelIndex = selectedChannelIndex,
            isPortrait = isPortrait,
            isSmallScreen = isSmallScreen
        )
    }

    override fun onDrawFrame(gl: GL10?) {
        // 清理背景
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 上传所有待渲染的像素帧（RGB 内存缓冲方式）
        uploadPendingFrames()

        val currentTimeSec = (SystemClock.uptimeMillis() - startTime) / 1000.0f
        val slots = currentSlots

        // 更新有新视频帧可用的 SurfaceTexture 硬件纹理
        for (slot in slots) {
            val channelIdx = slot.channelIndex
            if (channelIdx in 0 until MAX_CHANNELS) {
                if (frameAvailableFlags[channelIdx].getAndSet(false)) {
                    val st = surfaceTextures[channelIdx]
                    if (st != null) {
                        try {
                            st.updateTexImage()
                            st.getTransformMatrix(texMatrices[channelIdx])
                            hasOesFrame[channelIdx] = true
                        } catch (e: Exception) {
                            Log.w(TAG, "updateTexImage failed for ch $channelIdx: ${e.message}")
                        }
                    }
                }
            }
        }

        // 单路全屏模式优先处理
        if (fullscreenChannelIndex >= 0) {
            val chIdx = fullscreenChannelIndex.coerceIn(0, MAX_CHANNELS - 1)
            if (frameAvailableFlags[chIdx].getAndSet(false)) {
                val st = surfaceTextures[chIdx]
                if (st != null) {
                    try {
                        st.updateTexImage()
                        st.getTransformMatrix(texMatrices[chIdx])
                        hasOesFrame[chIdx] = true
                    } catch (e: Exception) {
                        Log.w(TAG, "updateTexImage fullscreen failed: ${e.message}")
                    }
                }
            }

            drawChannel(
                channelIndex = chIdx,
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
            val vx = (slot.normalizedLeft * surfaceWidth).toInt()
            val vy = ((1.0f - slot.normalizedBottom) * surfaceHeight).toInt()
            val vw = ((slot.normalizedRight - slot.normalizedLeft) * surfaceWidth).toInt()
            val vh = ((slot.normalizedBottom - slot.normalizedTop) * surfaceHeight).toInt()

            if (slot.isEmptySlot || slot.channelIndex < 0 || slot.channelIndex >= MAX_CHANNELS) {
                // 空槽位：绘制深色背景
                drawEmptySlot(vx, vy, vw, vh)
            } else {
                drawChannel(
                    channelIndex = slot.channelIndex,
                    glX = vx,
                    glY = vy,
                    glW = vw,
                    glH = vh,
                    timeSec = currentTimeSec
                )
            }
        }
    }

    private fun drawEmptySlot(glX: Int, glY: Int, glW: Int, glH: Int) {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(glX, glY, glW, glH)
        GLES20.glViewport(glX, glY, glW, glH)

        GLES20.glClearColor(0.043f, 0.067f, 0.078f, 1.0f) // #0B1114
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    /**
     * 计算保持原比例居中自适应 (Aspect Fit) 的实际绘制视口与裁剪区域
     *
     * @param glX 槽位在 OpenGL 视口中的 X 坐标 (左下角)
     * @param glY 槽位在 OpenGL 视口中的 Y 坐标 (左下角)
     * @param glW 槽位宽度
     * @param glH 槽位高度
     * @param aspectRatio 视频流原始宽高比 (width / height)
     * @return 实际渲染视口数组 [renderX, renderY, renderW, renderH]
     */
    private fun calculateAspectFitViewport(
        glX: Int,
        glY: Int,
        glW: Int,
        glH: Int,
        aspectRatio: Float
    ): IntArray {
        if (aspectRatio <= 0f || glW <= 0 || glH <= 0) {
            return intArrayOf(glX, glY, glW, glH)
        }
        val slotRatio = glW.toFloat() / glH.toFloat()
        return if (slotRatio > aspectRatio) {
            // 槽位比视频更宽 -> 左右留黑边 (Pillarbox)，高度填满槽位
            val targetW = (glH * aspectRatio).toInt().coerceAtLeast(1)
            val offsetX = (glW - targetW) / 2
            intArrayOf(glX + offsetX, glY, targetW, glH)
        } else {
            // 槽位比视频更高 -> 上下留黑边 (Letterbox)，宽度填满槽位
            val targetH = (glW / aspectRatio).toInt().coerceAtLeast(1)
            val offsetY = (glH - targetH) / 2
            intArrayOf(glX, glY + offsetY, glW, targetH)
        }
    }

    private fun drawChannel(channelIndex: Int, glX: Int, glY: Int, glW: Int, glH: Int, timeSec: Float) {
        // 设置当前分屏的裁剪与视口
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(glX, glY, glW, glH)
        GLES20.glViewport(glX, glY, glW, glH)

        // 先以监控底色填充整槽，保证黑边(Letterbox/Pillarbox)区域纯净无残留
        GLES20.glClearColor(0.043f, 0.067f, 0.078f, 1.0f) // #0B1114
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val isOesActive = hasOesFrame[channelIndex]
        val isRgbaActive = hasRealFrame[channelIndex]

        if (isOesActive) {
            // 原比例居中自适应：计算并应用等比自适应视口
            val aspect = videoAspects[channelIndex] ?: (16f / 9f)
            val (vx, vy, vw, vh) = calculateAspectFitViewport(glX, glY, glW, glH, aspect)
            GLES20.glScissor(vx, vy, vw, vh)
            GLES20.glViewport(vx, vy, vw, vh)

            // 方案 A：使用 OES 外部纹理渲染硬件解码视频（ExoPlayer / MediaCodec 零拷贝）
            GLES20.glUseProgram(oesProgram)

            GLES20.glEnableVertexAttribArray(oesAPosition)
            GLES20.glVertexAttribPointer(oesAPosition, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            GLES20.glEnableVertexAttribArray(oesATexCoord)
            GLES20.glVertexAttribPointer(oesATexCoord, 2, GLES20.GL_FLOAT, false, 0, oesTexCoordBuffer)

            GLES20.glUniformMatrix4fv(oesUTexMatrix, 1, false, texMatrices[channelIndex], 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureIds[channelIndex])
            GLES20.glUniform1i(oesUTexture, 0)

            GLES20.glDrawElements(GLES20.GL_TRIANGLES, INDICES.size, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

            GLES20.glDisableVertexAttribArray(oesAPosition)
            GLES20.glDisableVertexAttribArray(oesATexCoord)
        } else if (isRgbaActive || !isTestPatternEnabled) {
            // 原比例居中自适应：计算并应用等比自适应视口
            val aspect = videoAspects[channelIndex] ?: (16f / 9f)
            val (vx, vy, vw, vh) = calculateAspectFitViewport(glX, glY, glW, glH, aspect)
            GLES20.glScissor(vx, vy, vw, vh)
            GLES20.glViewport(vx, vy, vw, vh)

            // 方案 B：使用 2D 纹理渲染软解/投递画面
            GLES20.glUseProgram(textureProgram)

            GLES20.glEnableVertexAttribArray(texAPosition)
            GLES20.glVertexAttribPointer(texAPosition, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            GLES20.glEnableVertexAttribArray(texATexCoord)
            GLES20.glVertexAttribPointer(texATexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[channelIndex])
            GLES20.glUniform1i(texUTexture, 0)

            GLES20.glDrawElements(GLES20.GL_TRIANGLES, INDICES.size, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

            GLES20.glDisableVertexAttribArray(texAPosition)
            GLES20.glDisableVertexAttribArray(texATexCoord)
        } else {
            // 方案 C：使用动态扫描着色器渲染科技感模拟监控背景
            GLES20.glUseProgram(proceduralProgram)

            GLES20.glEnableVertexAttribArray(procAPosition)
            GLES20.glVertexAttribPointer(procAPosition, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            GLES20.glEnableVertexAttribArray(procATexCoord)
            GLES20.glVertexAttribPointer(procATexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

            val baseColor = channelBaseColors[channelIndex % MAX_CHANNELS]
            GLES20.glUniform3f(procUBaseColor, baseColor[0], baseColor[1], baseColor[2])
            GLES20.glUniform1f(procUTime, timeSec)
            GLES20.glUniform1f(procUChannelIndex, channelIndex.toFloat())

            GLES20.glDrawElements(GLES20.GL_TRIANGLES, INDICES.size, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

            GLES20.glDisableVertexAttribArray(procAPosition)
            GLES20.glDisableVertexAttribArray(procATexCoord)
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
        canvas.drawColor(Color.rgb(11, 17, 20))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(48, 82, 114)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }

        // 网格线
        for (x in 0..width step 40) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), paint)
        }
        for (y in 0..height step 40) {
            canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), paint)
        }

        // 居中通道编号
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 229, 255)
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }
        val channelNum = String.format("%02d", channelIndex + 1)
        val bounds = Rect()
        textPaint.getTextBounds("CAM $channelNum", 0, 6, bounds)
        canvas.drawText("CAM $channelNum", width / 2f, height / 2f + bounds.height() / 2f, textPaint)

        return bitmap
    }

    // --- IStreamFrameFeeder 接口实现 ---

    override fun getChannelSurface(channelIndex: Int): Surface? {
        return if (channelIndex in 0 until MAX_CHANNELS) surfaces[channelIndex] else null
    }

    override fun setOnSurfaceAvailableListener(listener: ((channelIndex: Int, surface: Surface) -> Unit)?) {
        this.onSurfaceAvailableListener = listener
        if (listener != null) {
            for (i in 0 until MAX_CHANNELS) {
                surfaces[i]?.let { listener.invoke(i, it) }
            }
        }
    }

    override fun feedRgbaFrame(
        channelIndex: Int,
        width: Int,
        height: Int,
        rgbaBuffer: ByteBuffer
    ) {
        if (channelIndex in 0 until MAX_CHANNELS) {
            setVideoSize(channelIndex, width, height)
            frameQueue[channelIndex] = PendingFrame(width, height, rgbaBuffer)
        }
    }

    override fun setVideoSize(channelIndex: Int, width: Int, height: Int) {
        if (channelIndex in 0 until MAX_CHANNELS && width > 0 && height > 0) {
            videoAspects[channelIndex] = width.toFloat() / height.toFloat()
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
        // 对接 Native YUV 解码时使用
    }

    override fun setTestPatternEnabled(enabled: Boolean) {
        this.isTestPatternEnabled = enabled
    }

    override fun clearChannel(channelIndex: Int) {
        if (channelIndex in 0 until MAX_CHANNELS) {
            hasRealFrame[channelIndex] = false
            hasOesFrame[channelIndex] = false
            frameAvailableFlags[channelIndex].set(false)
            videoAspects.remove(channelIndex)
            frameQueue.remove(channelIndex)
        }
    }

    override fun setOnFrameRenderedListener(listener: ((channelIndex: Int) -> Unit)?) {
        this.onFrameRenderedListener = listener
    }

    fun release() {
        videoAspects.clear()
        if (textureProgram != 0) {
            GLES20.glDeleteProgram(textureProgram)
            textureProgram = 0
        }
        if (proceduralProgram != 0) {
            GLES20.glDeleteProgram(proceduralProgram)
            proceduralProgram = 0
        }
        if (oesProgram != 0) {
            GLES20.glDeleteProgram(oesProgram)
            oesProgram = 0
        }
        GLES20.glDeleteTextures(MAX_CHANNELS, textureIds, 0)
        GLES20.glDeleteTextures(MAX_CHANNELS, oesTextureIds, 0)

        for (i in 0 until MAX_CHANNELS) {
            surfaces[i]?.release()
            surfaces[i] = null
            surfaceTextures[i]?.release()
            surfaceTextures[i] = null
        }
    }
}
