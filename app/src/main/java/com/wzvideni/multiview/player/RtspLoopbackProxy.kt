package com.wzvideni.multiview.player

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * 本地 RTSP 环回代理 (Local RTSP Loopback Proxy)
 *
 * 核心作用：
 * 1. 解决 ExoPlayer (Media3) RTSP 播放非标流（SDP 中缺少 fmtp 属性或缺少 sprop-parameter-sets）时
 *    抛出 IllegalArgumentException 导致应用崩溃的问题；
 * 2. 解决 Media3 RtpOpusReader 对 RTSP OPUS 音频流强依赖 Ogg 头（RFC 7845）的已知缺陷：
 *    针对标准 RTSP 服务器发送的 RFC 7587 裸 Opus 帧，在交织 TCP 通道上首帧前合成注入
 *    标准的 OpusHead 与 OpusTags RTP 包，使 Android 原生 MediaCodec Opus 解码器顺利初始化并播放声音。
 *
 * 工作机制：
 * 1. 在 127.0.0.1 本地开启随机端口监听；
 * 2. 拦截并代理客户端与真实 RTSP 服务端之间的 TCP 握手通信；
 * 3. 针对 DESCRIBE 响应中的 SDP：若发现视频轨缺少 a=fmtp，自动补充标准 H.264/H.265 fmtp 配置并修正 Content-Length；
 * 4. 针对交织二进制流：识别出首个 OPUS 音频 RTP 包时，注入 OpusHead 与 OpusTags 包，然后无缝切换为高速原始透传。
 */
object RtspLoopbackProxy {

    private const val TAG = "RtspLoopbackProxy"
    private const val DEFAULT_RTSP_PORT = 554

    // 默认兜底 H.264 SPS/PPS (1920x1080 High Profile Level 4.2)
    // 用于应对 RTSP 服务端在 SDP 中未下发 fmtp 时的 ExoPlayer 强制校验
    private const val DEFAULT_H264_FMTP =
        "packetization-mode=1;profile-level-id=64002a;sprop-parameter-sets=Z2QAKqwsaoHgCJ+WbgICAoAAAfSAAHUwdDA=,aO48gA=="

    // 默认兜底 H.265 VPS/SPS/PPS (1920x1080 Main Profile)
    private const val DEFAULT_H265_FMTP =
        "sprop-vps=QAEMAf//AWAAAAMAkAAAAAMAAAMADCAA;sprop-sps=QgEBAWAAAAMAkAAAAAMAAAMADCAPCeKAkg==;sprop-pps=RAHA8vA8kA=="

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var localPort: Int = 0

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "RtspLoopbackProxy-worker").apply { isDaemon = true }
    }

    // 映射 token -> 目标原始 RTSP URL
    private val targetUrlMap = ConcurrentHashMap<String, String>()

    /**
     * 将原始 RTSP URL 包装为本地代理 URL
     * 若输入已是本地地址或为空则直接返回
     */
    fun getProxyUrl(originalUrl: String): String {
        if (originalUrl.isBlank() ||
            originalUrl.startsWith("rtsp://127.0.0.1") ||
            originalUrl.startsWith("rtsp://localhost")
        ) {
            return originalUrl
        }

        ensureStarted()
        val port = localPort
        if (port <= 0) return originalUrl

        val token = hashKey(originalUrl)
        targetUrlMap[token] = originalUrl
        return "rtsp://127.0.0.1:$port/$token"
    }

    @Synchronized
    private fun ensureStarted() {
        RtspStreamPlayerManager.configureMedia3Logger()
        if (serverSocket != null && serverSocket?.isClosed == false) return
        try {
            val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = server
            localPort = server.localPort
            Log.i(TAG, "RTSP Loopback Proxy started on 127.0.0.1:$localPort")
            executor.execute { acceptLoop(server) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTSP Loopback Proxy: ${e.message}", e)
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            try {
                val clientSocket = server.accept()
                clientSocket.tcpNoDelay = true
                clientSocket.receiveBufferSize = 512 * 1024
                clientSocket.sendBufferSize = 512 * 1024
                executor.execute { handleSession(clientSocket) }
            } catch (e: Exception) {
                if (server.isClosed) break
                Log.w(TAG, "Error accepting client socket: ${e.message}")
            }
        }
    }

    private fun handleSession(clientSocket: Socket) {
        var remoteSocket: Socket? = null
        try {
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            // 1. 读取客户端首个 RTSP 请求，解析出 stream token
            val initialRequest = readRtspRequestHeader(clientIn) ?: run {
                clientSocket.close()
                return
            }

            val requestLine = initialRequest.lines().firstOrNull() ?: ""
            val requestUriString = requestLine.split(" ").getOrNull(1) ?: ""
            val path = try {
                URI(requestUriString).path ?: ""
            } catch (e: Exception) {
                ""
            }

            val token = path.trimStart('/').split('/').firstOrNull() ?: ""
            val targetUrl = targetUrlMap[token]
            if (targetUrl == null) {
                Log.w(TAG, "No target RTSP URL mapped for token: $token")
                clientSocket.close()
                return
            }

            val targetUri = URI(targetUrl)
            val remoteHost = targetUri.host
            val remotePort = if (targetUri.port > 0) targetUri.port else DEFAULT_RTSP_PORT

            // 2. 连接远端真实 RTSP 服务 (扩大 Socket 缓冲区为 512KB，应对 I 帧爆发并防止拥塞降速)
            val remote = Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                receiveBufferSize = 512 * 1024
                sendBufferSize = 512 * 1024
            }
            remote.connect(java.net.InetSocketAddress(remoteHost, remotePort), 5000)
            remote.soTimeout = 15000
            remoteSocket = remote
            val remoteIn = remote.getInputStream()
            val remoteOut = remote.getOutputStream()

            val proxyBaseUrl = "rtsp://127.0.0.1:$localPort/$token"

            // 3. 转发客户端首个请求（将代理 URL 还原为目标 URL）
            val modifiedInitialRequest = initialRequest.replace(proxyBaseUrl, targetUrl)
            remoteOut.write(modifiedInitialRequest.toByteArray(Charsets.UTF_8))
            remoteOut.flush()

            // 4. 启动客户端 -> 远端的数据转发线程
            executor.execute {
                forwardClientToRemote(clientIn, remoteOut, proxyBaseUrl, targetUrl, clientSocket, remote)
            }

            // 5. 在当前线程处理 远端 -> 客户端 响应（拦截并修复 DESCRIBE SDP，并注入 OPUS 虚拟头）
            forwardRemoteToClient(remoteIn, clientOut, targetUrl, proxyBaseUrl, clientSocket, remote)

        } catch (e: Exception) {
            Log.d(TAG, "Session ended: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (ignored: Exception) {}
            try { remoteSocket?.close() } catch (ignored: Exception) {}
        }
    }

    /**
     * 转发客户端请求到远端服务器，保持将 proxyBaseUrl 替换为 targetUrl
     */
    private fun forwardClientToRemote(
        clientIn: InputStream,
        remoteOut: OutputStream,
        proxyBaseUrl: String,
        targetUrl: String,
        clientSocket: Socket,
        remoteSocket: Socket
    ) {
        val buffer = ByteArray(8192)
        try {
            while (!clientSocket.isClosed && !remoteSocket.isClosed) {
                val len = clientIn.read(buffer)
                if (len <= 0) break

                // 判断是否包含代理基础 URL（二进制交织包如 RTCP 直接透传，不转为字符串）
                if (buffer[0] != '$'.code.toByte()) {
                    val text = String(buffer, 0, len, Charsets.UTF_8)
                    if (text.contains(proxyBaseUrl)) {
                        val replaced = text.replace(proxyBaseUrl, targetUrl)
                        remoteOut.write(replaced.toByteArray(Charsets.UTF_8))
                    } else {
                        remoteOut.write(buffer, 0, len)
                    }
                } else {
                    remoteOut.write(buffer, 0, len)
                }
                remoteOut.flush()
            }
        } catch (ignored: Exception) {
        } finally {
            try { clientSocket.close() } catch (ignored: Exception) {}
            try { remoteSocket.close() } catch (ignored: Exception) {}
        }
    }

    /**
     * 转发远端响应到客户端：
     * 1. 拦截并修正 DESCRIBE 的 SDP 内容（补充视频 fmtp）；
     * 2. 识别 OPUS 音频流，并在交织 TCP 码流中首帧前注入合成的 OpusHead / OpusTags RTP 包；
     * 3. 注入完成后无缝切换为高速流式透传。
     */
    private fun forwardRemoteToClient(
        remoteIn: InputStream,
        clientOut: OutputStream,
        targetUrl: String,
        proxyBaseUrl: String,
        clientSocket: Socket,
        remoteSocket: Socket
    ) {
        val buffer = ByteArrayOutputStream()
        val readBuf = ByteArray(8192)

        var opusPayloadType: Int? = null
        var opusChannels: Int = 1
        var trailingBytes = ByteArray(0)

        try {
            // 第一阶段：读取并拦截 DESCRIBE SDP 响应
            while (!clientSocket.isClosed && !remoteSocket.isClosed) {
                val len = remoteIn.read(readBuf)
                if (len <= 0) break
                buffer.write(readBuf, 0, len)

                val currentBytes = buffer.toByteArray()
                val headerEndIndex = findHeaderEnd(currentBytes)

                if (headerEndIndex > 0) {
                    val headerString = String(currentBytes, 0, headerEndIndex, Charsets.UTF_8)

                    if (headerString.contains("application/sdp", ignoreCase = true)) {
                        // 该响应为 DESCRIBE SDP 响应
                        val contentLength = parseContentLength(headerString)
                        val bodyStartIndex = headerEndIndex + 4
                        val availableBodyLength = currentBytes.size - bodyStartIndex

                        if (availableBodyLength >= contentLength) {
                            // 已完整接收到 SDP 报文体
                            val sdpString = String(currentBytes, bodyStartIndex, contentLength, Charsets.UTF_8)
                            trailingBytes = if (availableBodyLength > contentLength) {
                                currentBytes.copyOfRange(bodyStartIndex + contentLength, currentBytes.size)
                            } else {
                                ByteArray(0)
                            }

                            // 1. 修复 SDP（补充缺失的视频 a=fmtp，保留并识别音频 OPUS 轨）
                            val fixedSdp = fixSdp(sdpString, targetUrl, proxyBaseUrl)
                            val fixedSdpBytes = fixedSdp.toByteArray(Charsets.UTF_8)

                            // 2. 检测 SDP 中的 OPUS 音频配置
                            val opusMatch = Regex("""a=rtpmap:(\d+)\s+OPUS/48000(?:/(\d+))?""", RegexOption.IGNORE_CASE)
                                .find(fixedSdp)
                            if (opusMatch != null) {
                                opusPayloadType = opusMatch.groupValues[1].toIntOrNull()
                                opusChannels = opusMatch.groupValues.getOrNull(2)?.toIntOrNull() ?: 1
                                Log.i(
                                    TAG,
                                    "Detected OPUS audio track in SDP: PT=$opusPayloadType, channels=$opusChannels"
                                )
                            }

                            // 3. 更新 Content-Length 和 Content-Base
                            var fixedHeader = headerString.replace(
                                Regex("""Content-Length:\s*\d+""", RegexOption.IGNORE_CASE),
                                "Content-Length: ${fixedSdpBytes.size}"
                            )
                            fixedHeader = fixedHeader.replace(targetUrl.trimEnd('/'), proxyBaseUrl.trimEnd('/'))

                            // 发送修复后的 DESCRIBE 响应给客户端
                            clientOut.write(fixedHeader.toByteArray(Charsets.UTF_8))
                            clientOut.write("\r\n\r\n".toByteArray(Charsets.UTF_8))
                            clientOut.write(fixedSdpBytes)
                            clientOut.flush()

                            buffer.reset()
                            break
                        }
                    } else {
                        // 普通响应（如 OPTIONS 200 OK），直接转发输出
                        clientOut.write(currentBytes)
                        clientOut.flush()
                        buffer.reset()
                    }
                }
            }

            // 构建统一输入流（如果有 DESCRIBE 残留未读字节则前置）
            val combinedInput = if (trailingBytes.isNotEmpty()) {
                SequenceInputStream(ByteArrayInputStream(trailingBytes), remoteIn)
            } else {
                remoteIn
            }
            val bis = BufferedInputStream(combinedInput, 65536)

            // 第二阶段：流式解析响应与交织 RTP 包，若含 OPUS 则注入虚拟头
            var opusInjected = (opusPayloadType == null)
            var nonOpusPacketCount = 0

            while (!clientSocket.isClosed && !remoteSocket.isClosed && !opusInjected) {
                val firstByte = bis.read()
                if (firstByte == -1) break

                if (firstByte != '$'.code) {
                    // RTSP 文本响应（如 SETUP 200 OK, PLAY 200 OK）
                    val textBaos = ByteArrayOutputStream()
                    textBaos.write(firstByte)
                    while (!clientSocket.isClosed && !remoteSocket.isClosed) {
                        val nextByte = bis.read()
                        if (nextByte == -1) break
                        textBaos.write(nextByte)
                        val arr = textBaos.toByteArray()
                        if (arr.size >= 4 &&
                            arr[arr.size - 4] == '\r'.code.toByte() &&
                            arr[arr.size - 3] == '\n'.code.toByte() &&
                            arr[arr.size - 2] == '\r'.code.toByte() &&
                            arr[arr.size - 1] == '\n'.code.toByte()
                        ) {
                            break
                        }
                    }
                    val headerStr = textBaos.toString("UTF-8")
                    val cl = parseContentLength(headerStr)
                    if (cl > 0) {
                        val body = ByteArray(cl)
                        readFully(bis, body)
                        textBaos.write(body)
                    }
                    clientOut.write(textBaos.toByteArray())
                    clientOut.flush()
                } else {
                    // 交织二进制帧: $ <channel> <lenHi> <lenLo> <packet>
                    val channel = bis.read()
                    if (channel == -1) break
                    val lenHi = bis.read()
                    if (lenHi == -1) break
                    val lenLo = bis.read()
                    if (lenLo == -1) break
                    val frameLen = (lenHi shl 8) or lenLo
                    val packet = ByteArray(frameLen)
                    readFully(bis, packet)

                    val isRtp = frameLen >= 12 && ((packet[0].toInt() and 0xC0) == 0x80)
                    val pt = if (isRtp) packet[1].toInt() and 0x7F else -1

                    if (isRtp && pt == opusPayloadType) {
                        // 捕获到首个真实 OPUS 音频 RTP 包！
                        val seq0 = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
                        val tsBytes = packet.copyOfRange(4, 8)
                        val ssrcBytes = packet.copyOfRange(8, 12)

                        // 判断实际声道数：优先根据首帧 Opus TOC 字节的 bit 2（1=stereo, 0=mono）或者 SDP 配置
                        val isStereo = packet.size > 12 && ((packet[12].toInt() and 0x04) != 0)
                        val effectiveChannels = if (opusChannels == 2 || isStereo) 2 else 1

                        // 注入合成包 1: OpusHead RTP 包 (RFC 7845 ID Header)
                        val headRtp = buildOpusHeadRtpPacket(pt, (seq0 - 2) and 0xFFFF, tsBytes, ssrcBytes, effectiveChannels)
                        clientOut.write('$'.code)
                        clientOut.write(channel)
                        clientOut.write((headRtp.size ushr 8) and 0xFF)
                        clientOut.write(headRtp.size and 0xFF)
                        clientOut.write(headRtp)

                        // 注入合成包 2: OpusTags RTP 包 (RFC 7845 Comment Header)
                        val tagsRtp = buildOpusTagsRtpPacket(pt, (seq0 - 1) and 0xFFFF, tsBytes, ssrcBytes)
                        clientOut.write('$'.code)
                        clientOut.write(channel)
                        clientOut.write((tagsRtp.size ushr 8) and 0xFF)
                        clientOut.write(tagsRtp.size and 0xFF)
                        clientOut.write(tagsRtp)

                        Log.i(
                            TAG,
                            "Injected synthetic OpusHead and OpusTags on channel $channel (pt=$pt, seq0=$seq0, ch=$effectiveChannels)"
                        )
                        opusInjected = true
                    } else {
                        nonOpusPacketCount++
                        if (nonOpusPacketCount > 300) {
                            // 防御兜底：若远端持续无音频流超过 300 包，退出单帧检查模式
                            Log.w(TAG, "No OPUS packets received after 300 frames, falling back to pass-through")
                            opusInjected = true
                        }
                    }

                    // 转发原始帧给客户端
                    clientOut.write('$'.code)
                    clientOut.write(channel)
                    clientOut.write(lenHi)
                    clientOut.write(lenLo)
                    clientOut.write(packet)
                    clientOut.flush()
                }
            }

            // 第三阶段：虚拟头注入完成，后续码流全速透明透传
            // 设置 12 秒流传输超时：避免网络短时抖动或摄像头 I 帧较长导致误判，同时在对方真正断开时及时释放并触发重连
            try {
                remoteSocket.soTimeout = 12000
            } catch (ignored: Exception) {}

            val copyBuf = ByteArray(65536)
            while (!clientSocket.isClosed && !remoteSocket.isClosed) {
                val len = bis.read(copyBuf)
                if (len <= 0) break
                clientOut.write(copyBuf, 0, len)
                // 仅在当前无更多就绪字节时才执行 flush，消除频繁 flush 带来的系统调用与 TCP 分片开销
                if (bis.available() == 0) {
                    clientOut.flush()
                }
            }

        } catch (ignored: Exception) {
        } finally {
            try { clientSocket.close() } catch (ignored: Exception) {}
            try { remoteSocket.close() } catch (ignored: Exception) {}
        }
    }

    /**
     * 校验并修补 SDP：如果视频轨缺少 fmtp 参数，动态补齐默认的参数集
     */
    fun fixSdp(sdp: String, targetUrl: String, proxyBaseUrl: String): String {
        var result = sdp

        // 1. 替换绝对控制 URL 为本地代理基址
        result = result.replace(targetUrl.trimEnd('/'), proxyBaseUrl.trimEnd('/'))

        val delimiter = if (result.contains("\r\n")) "\r\n" else "\n"

        // 2. 检查 H.264 视频轨 (如 a=rtpmap:100 H264/90000)
        val h264Regex = Regex("""(a=rtpmap:(\d+)\s+H264/\d+[^\r\n]*)""", RegexOption.IGNORE_CASE)
        val h264Match = h264Regex.find(result)
        if (h264Match != null) {
            val pt = h264Match.groupValues[2]
            if (!result.contains("a=fmtp:$pt")) {
                Log.w(TAG, "Detected H.264 video payload $pt missing a=fmtp, injecting default fmtp fallback")
                val fmtpLine = "${delimiter}a=fmtp:$pt $DEFAULT_H264_FMTP"
                result = result.replace(h264Match.value, h264Match.value + fmtpLine)
            }
        }

        // 3. 检查 H.265 视频轨 (如 a=rtpmap:100 H265/90000)
        val h265Regex = Regex("""(a=rtpmap:(\d+)\s+H265/\d+[^\r\n]*)""", RegexOption.IGNORE_CASE)
        val h265Match = h265Regex.find(result)
        if (h265Match != null) {
            val pt = h265Match.groupValues[2]
            if (!result.contains("a=fmtp:$pt")) {
                Log.w(TAG, "Detected H.265 video payload $pt missing a=fmtp, injecting default fmtp fallback")
                val fmtpLine = "${delimiter}a=fmtp:$pt $DEFAULT_H265_FMTP"
                result = result.replace(h265Match.value, h265Match.value + fmtpLine)
            }
        }

        return result
    }

    /**
     * 构造合成的 OpusHead RTP 包 (RFC 7845)
     */
    private fun buildOpusHeadRtpPacket(
        pt: Int,
        seq: Int,
        tsBytes: ByteArray,
        ssrcBytes: ByteArray,
        channelCount: Int
    ): ByteArray {
        val rtp = ByteArray(12 + 19)
        // RTP Header (12 字节)
        rtp[0] = 0x80.toByte() // V=2, P=0, X=0, CC=0
        rtp[1] = (pt and 0x7F).toByte() // M=0, PT=pt
        rtp[2] = ((seq ushr 8) and 0xFF).toByte()
        rtp[3] = (seq and 0xFF).toByte()
        System.arraycopy(tsBytes, 0, rtp, 4, 4)
        System.arraycopy(ssrcBytes, 0, rtp, 8, 4)

        // OpusHead Payload (19 字节, 符合 RFC 7845 规范)
        val magic = "OpusHead".toByteArray(Charsets.US_ASCII)
        System.arraycopy(magic, 0, rtp, 12, 8)
        rtp[20] = 0x01 // Version 1
        rtp[21] = channelCount.toByte() // 声道数 (1=mono, 2=stereo)
        rtp[22] = 0x00 // Pre-skip (uint16 little-endian) = 0
        rtp[23] = 0x00
        rtp[24] = 0x80.toByte() // 采样率 48000 Hz (uint32 little-endian: 0x0000BB80)
        rtp[25] = 0xBB.toByte()
        rtp[26] = 0x00
        rtp[27] = 0x00
        rtp[28] = 0x00 // 输出增益 = 0 dB
        rtp[29] = 0x00
        rtp[30] = 0x00 // 声道映射 Family 0 (无映射表)

        return rtp
    }

    /**
     * 构造合成的 OpusTags RTP 包 (RFC 7845)
     */
    private fun buildOpusTagsRtpPacket(
        pt: Int,
        seq: Int,
        tsBytes: ByteArray,
        ssrcBytes: ByteArray
    ): ByteArray {
        val rtp = ByteArray(12 + 16)
        // RTP Header (12 字节)
        rtp[0] = 0x80.toByte() // V=2, P=0, X=0, CC=0
        rtp[1] = (pt and 0x7F).toByte() // M=0, PT=pt
        rtp[2] = ((seq ushr 8) and 0xFF).toByte()
        rtp[3] = (seq and 0xFF).toByte()
        System.arraycopy(tsBytes, 0, rtp, 4, 4)
        System.arraycopy(ssrcBytes, 0, rtp, 8, 4)

        // OpusTags Payload (16 字节, 符合 RFC 7845 规范)
        val magic = "OpusTags".toByteArray(Charsets.US_ASCII)
        System.arraycopy(magic, 0, rtp, 12, 8)
        rtp[20] = 0x00 // Vendor 字符串长度 = 0 (uint32 little-endian)
        rtp[21] = 0x00
        rtp[22] = 0x00
        rtp[23] = 0x00
        rtp[24] = 0x00 // 评论列表条数 = 0 (uint32 little-endian)
        rtp[25] = 0x00
        rtp[26] = 0x00
        rtp[27] = 0x00

        return rtp
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) {
                throw java.io.EOFException("Premature EOF in RTSP stream")
            }
            offset += count
        }
    }

    private fun findHeaderEnd(data: ByteArray): Int {
        for (i in 0 until data.size - 3) {
            if (data[i] == '\r'.code.toByte() &&
                data[i + 1] == '\n'.code.toByte() &&
                data[i + 2] == '\r'.code.toByte() &&
                data[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private fun parseContentLength(header: String): Int {
        val matcher = Pattern.compile("""Content-Length:\s*(\d+)""", Pattern.CASE_INSENSITIVE).matcher(header)
        return if (matcher.find()) {
            matcher.group(1)?.toIntOrNull() ?: 0
        } else {
            0
        }
    }

    private fun readRtspRequestHeader(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (true) {
            val len = input.read(buf)
            if (len <= 0) return null
            baos.write(buf, 0, len)
            val bytes = baos.toByteArray()
            val end = findHeaderEnd(bytes)
            if (end >= 0) {
                return String(bytes, 0, end + 4, Charsets.UTF_8)
            }
        }
    }

    private fun hashKey(url: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(url.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(12)
    }
}
