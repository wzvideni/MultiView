package com.wzvideni.multiview.gl

import java.nio.ByteBuffer

/**
 * 视频流解码帧投递接口（用于对接 FFmpeg / NDK / MediaCodec 解码器）
 */
interface IStreamFrameFeeder {

    /**
     * 投递 RGBA 格式的一帧视频画面
     *
     * @param channelIndex 目标通道槽位 (0..31)
     * @param width 画面宽度
     * @param height 画面高度
     * @param rgbaBuffer RGBA 像素数据 DirectByteBuffer
     */
    fun feedRgbaFrame(channelIndex: Int, width: Int, height: Int, rgbaBuffer: ByteBuffer)

    /**
     * 投递 NV21 / YUV420P 像素数据
     */
    fun feedYuvFrame(channelIndex: Int, width: Int, height: Int, yData: ByteBuffer, uData: ByteBuffer, vData: ByteBuffer)

    /**
     * 设置是否开启内置模拟画面生成器（在未接通真实 RTSP 流前方便测试 1~32 路画面与流畅度）
     */
    fun setTestPatternEnabled(enabled: Boolean)

    /**
     * 清空指定通道的画面（恢复为空闲等待画面）
     */
    fun clearChannel(channelIndex: Int)
}
