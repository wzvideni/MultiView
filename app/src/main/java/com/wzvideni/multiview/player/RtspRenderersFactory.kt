package com.wzvideni.multiview.player

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.util.ArrayList

/**
 * 专为 RTSP 监控设计的渲染器工厂：
 * 1. 静音模式 (isMuted == true)：
 *    使用 [NoOpAudioRenderer] 接管音频轨。
 *    既满足 RTSP 服务端对 trackID (如 OPUS/AAC) 必须协商 SETUP 的硬性要求（防止 PLAY 400 报错），
 *    又避免创建底层硬件 AudioTrack，杜绝 AudioTrack underrun 与时钟倒退卡顿，
 *    自动解耦主播放时钟 (使 ExoPlayer 采用基于系统时间的 StandaloneMediaClock 平滑渲染视频)。
 * 2. 正常模式 (isMuted == false)：
 *    创建标准 MediaCodecAudioRenderer，正常输出声音。
 */
@OptIn(UnstableApi::class)
class RtspRenderersFactory(
    context: Context,
    private val isMuted: Boolean
) : DefaultRenderersFactory(context) {

    init {
        setEnableDecoderFallback(true)
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        if (isMuted) {
            out.add(NoOpAudioRenderer())
        } else {
            super.buildAudioRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                audioSink,
                eventHandler,
                eventListener,
                out
            )
        }
    }
}

/**
 * 静音模式下的轻量空操作音频渲染器：
 * - 响应并支持所有音频格式，保证 RTSP 多轨正常 SETUP 与协商；
 * - 不提供 MediaClock (getMediaClock() 返回 null)，由 StandaloneMediaClock 独立精准推进时间；
 * - isReady() 恒为 true，永远不会因为音频丢包/无声音而使播放器陷入 STATE_BUFFERING；
 * - render() 及时清空 SampleQueue 中的音频数据，防止内存堆积。
 */
@OptIn(UnstableApi::class)
class NoOpAudioRenderer : BaseRenderer(C.TRACK_TYPE_AUDIO) {

    private val emptyBuffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private val formatHolder = FormatHolder()

    override fun getName(): String = "NoOpAudioRenderer"

    override fun supportsFormat(format: Format): Int {
        return if (MimeTypes.isAudio(format.sampleMimeType)) {
            RendererCapabilities.create(C.FORMAT_HANDLED)
        } else {
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (state != STATE_STARTED) return
        try {
            // 快速读取并丢弃已就绪的音频帧，确保 SampleQueue 内存正常被回收
            while (true) {
                emptyBuffer.clear()
                val result = readSource(formatHolder, emptyBuffer, androidx.media3.exoplayer.source.SampleStream.FLAG_OMIT_SAMPLE_DATA)
                if (result != C.RESULT_BUFFER_READ) break
            }
        } catch (t: Throwable) {
            // 绝不让静音音频通道的任何异常影响主画面播放
        }
    }

    override fun isReady(): Boolean = true

    override fun isEnded(): Boolean = false
}
