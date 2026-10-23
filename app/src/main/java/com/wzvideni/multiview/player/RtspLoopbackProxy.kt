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
 *    标准的 OpusHead 与 OpusTags RTP 包，使 Android 原生 MediaCodec Opus 解码器顺利初始化并播放声音；
 * 3. 增强 RTSP 鉴权代理兼容（Transparent Auth Proxying）：
 *    - 当远端开启 Basic/Digest 鉴权且返回 401 时，自动基于设备 ID（或 URL 中的 userInfo）透明完成认证质询并重发，
 *      无需客户端 ExoPlayer 面对 401 质询与 Digest URI 校验失败崩溃；
 *    - 严格遵循 RFC 2326，将 Request-URI 剥离账号密码保持为合法的规范 RTSP URI，杜绝远端 400 报错。
 */
object RtspLoopbackProxy {

    private const val TAG = "RtspLoopbackProxy"
    private const val DEFAULT_RTSP_PORT = 554

    // 默认兜底 H.264 SPS/PPS (1920x1080 High Profile Level 4.2)
    // 用于应对 RTSP 服务端在 SDP 中未下发 fmtp 时的 ExoPlayer 强制校验
    private const val DEFAULT_H264_FMTP =
        "packetization-mode=1;profile-level-id=64002a;sprop-parameter-sets=Z2QAKqwsaoHgCJ+WbgICAoAAAfSAAHUwdDA=,aO48gA=="

    // 默认兜底 H.265 VPS/SPS/PPS (1920x1080 Main Profile Level 4.0)
    // 用于应对 RTSP 服务端在 SDP 中未下发 fmtp 或缺失参数集时的 ExoPlayer 强制校验
    private const val DEFAULT_H265_FMTP =
        "sprop-vps=QAEMAf//AWAAAAMAkAAAAwAAAwB4lZgJ;sprop-sps=QgEBAWAAAAMAkAAAAwAAAwB4oAPAgBDlllZpJMrwFoCAAAADAIAAAAyE;sprop-pps=RAHBcrRiQA=="

    data class StreamTarget(
        val originalUrl: String,
        val cleanUrl: String,
        val credentials: RtspCredentials?
    )

    class SessionAuthContext(
        val credentials: RtspCredentials?,
        val cleanTargetUrl: String,
        val proxyBaseUrl: String
    ) {
        @Volatile var lastClientRequest: String? = null
        @Volatile var authChallenge: String? = null
        @Volatile var authRetried: Boolean = false
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var localPort: Int = 0

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "RtspLoopbackProxy-worker").apply { isDaemon = true }
    }

    // 映射 token -> 目标原始 RTSP 配置
    private val streamTargetMap = ConcurrentHashMap<String, StreamTarget>()
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

        val cleanUrl = RtspAuthHelper.getCleanUrl(originalUrl)
        val credentials = RtspAuthHelper.extractCredentials(originalUrl, fallbackToDeviceId = true)
        val token = hashKey(originalUrl)

        val target = StreamTarget(originalUrl, cleanUrl, credentials)
        streamTargetMap[token] = target
        targetUrlMap[token] = cleanUrl

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
            } catch (_: Exception) {
                ""
            }

            val token = path.trimStart('/').split('/').firstOrNull() ?: ""
            val streamTarget = streamTargetMap[token]
            val cleanTargetUrl = streamTarget?.cleanUrl ?: targetUrlMap[token]
            if (cleanTargetUrl == null) {
                Log.w(TAG, "No target RTSP URL mapped for token: $token")
                clientSocket.close()
                return
            }

            val credentials = streamTarget?.credentials ?: RtspAuthHelper.extractCredentials(cleanTargetUrl, fallbackToDeviceId = true)
            val targetUri = URI(cleanTargetUrl)
            val remoteHost = targetUri.host
            val remotePort = if (targetUri.port > 0) targetUri.port else DEFAULT_RTSP_PORT

            // 2. 连接远端真实 RTSP 服务 (扩大 Socket 缓冲区为 512KB，应对 I 帧爆发并防止拥塞降速)
            val remote = Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                receiveBufferSize = 512 * 1024
                sendBufferSize = 512 * 1024
            }
            remote.connect(java.net.InetSocketAddress(remoteHost, remotePort), 10000)
            remote.soTimeout = 25000
            remoteSocket = remote
            val remoteIn = remote.getInputStream()
            val remoteOut = remote.getOutputStream()

            val proxyBaseUrl = "rtsp://127.0.0.1:$localPort/$token"
            val normProxyBaseUrl = proxyBaseUrl.trimEnd('/')
            val normCleanTargetUrl = cleanTargetUrl.trimEnd('/')

            val authContext = SessionAuthContext(
                credentials = credentials,
                cleanTargetUrl = cleanTargetUrl,
                proxyBaseUrl = proxyBaseUrl
            )

            // 3. 转发客户端首个请求（将代理 URL 还原为符合规范的干净目标 URL）
            val modifiedInitialRequest = initialRequest.replace(normProxyBaseUrl, normCleanTargetUrl)
            authContext.lastClientRequest = modifiedInitialRequest
            remoteOut.write(modifiedInitialRequest.toByteArray(Charsets.UTF_8))
            remoteOut.flush()

            // 4. 启动客户端 -> 远端的数据转发线程
            executor.execute {
                forwardClientToRemote(clientIn, remoteOut, proxyBaseUrl, cleanTargetUrl, clientSocket, remote, authContext)
            }

            // 5. 在当前线程处理 远端 -> 客户端 响应（处理 401 自动鉴权、拦截并修复 DESCRIBE SDP，并注入 OPUS 虚拟头）
            forwardRemoteToClient(remoteIn, remoteOut, clientOut, cleanTargetUrl, proxyBaseUrl, clientSocket, remote, authContext)

        } catch (e: Exception) {
            Log.d(TAG, "Session ended: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            try { remoteSocket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * 转发客户端请求到远端服务器，保持将 proxyBaseUrl 替换为 cleanTargetUrl。
     * 若会话已有认证挑战信息，自动为后续信令注入 Authorization 头。
     */
    private fun forwardClientToRemote(
        clientIn: InputStream,
        remoteOut: OutputStream,
        proxyBaseUrl: String,
        cleanTargetUrl: String,
        clientSocket: Socket,
        remoteSocket: Socket,
        authContext: SessionAuthContext
    ) {
        val buffer = ByteArray(8192)
        try {
            while (!clientSocket.isClosed && !remoteSocket.isClosed) {
                val len = clientIn.read(buffer)
                if (len <= 0) break

                // 判断是否包含代理基础 URL（二进制交织包如 RTCP 直接透传，不转为字符串）
                if (buffer[0] != '$'.code.toByte()) {
                    val text = String(buffer, 0, len, Charsets.UTF_8)
                    val normProxy = proxyBaseUrl.trimEnd('/')
                    val normCleanTarget = cleanTargetUrl.trimEnd('/')
                    var replaced = if (text.contains(normProxy)) {
                        text.replace(normProxy, normCleanTarget)
                    } else {
                        text
                    }

                    // 若已认证过，且客户端请求未携带 Authorization 头，自动计算并附加对应的 Authorization 头部
                    val challenge = authContext.authChallenge
                    val creds = authContext.credentials
                    if (challenge != null && creds != null && !replaced.contains("Authorization:", ignoreCase = true)) {
                        val reqLine = RtspAuthHelper.extractRequestMethodAndUri(replaced)
                        if (reqLine != null) {
                            val authHeader = RtspAuthHelper.buildAuthorizationHeader(
                                method = reqLine.first,
                                uri = reqLine.second,
                                credentials = creds,
                                authenticateHeader = challenge
                            )
                            if (authHeader != null) {
                                replaced = injectAuthorizationHeader(replaced, authHeader)
                            }
                        }
                    }

                    authContext.lastClientRequest = replaced
                    remoteOut.write(replaced.toByteArray(Charsets.UTF_8))
                } else {
                    remoteOut.write(buffer, 0, len)
                }
                remoteOut.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            try { remoteSocket.close() } catch (_: Exception) {}
        }
    }

    /**
     * 转发远端响应到客户端：
     * 1. 拦截 401 Unauthorized 并自动完成 Digest/Basic 鉴权重试；
     * 2. 拦截并修正 DESCRIBE 的 SDP 内容（补充视频 fmtp）；
     * 3. 识别 OPUS 音频流，并在交织 TCP 码流中首帧前注入合成的 OpusHead / OpusTags RTP 包；
     * 4. 注入完成后无缝切换为高速流式透传。
     */
    private fun forwardRemoteToClient(
        remoteIn: InputStream,
        remoteOut: OutputStream,
        clientOut: OutputStream,
        cleanTargetUrl: String,
        proxyBaseUrl: String,
        clientSocket: Socket,
        remoteSocket: Socket,
        authContext: SessionAuthContext
    ) {
        val buffer = ByteArrayOutputStream()
        val readBuf = ByteArray(8192)

        var opusPayloadType: Int? = null
        var opusChannels: Int = 1
        var trailingBytes = ByteArray(0)

        try {
            // 第一阶段：读取并拦截 DESCRIBE SDP 响应（支持 401 自动鉴权）
            while (!clientSocket.isClosed && !remoteSocket.isClosed) {
                val len = remoteIn.read(readBuf)
                if (len <= 0) break
                buffer.write(readBuf, 0, len)

                val currentBytes = buffer.toByteArray()
                val headerEndIndex = findHeaderEnd(currentBytes)

                if (headerEndIndex > 0) {
                    val headerString = String(currentBytes, 0, headerEndIndex, Charsets.UTF_8)

                    // 1. 自动检测是否需要鉴权 (401 Unauthorized)
                    if (headerString.contains("401 Unauthorized", ignoreCase = true) && !authContext.authRetried) {
                        val challenge = extractAuthenticateHeader(headerString)
                        val creds = authContext.credentials
                        val lastReq = authContext.lastClientRequest
                        if (challenge != null && creds != null && lastReq != null) {
                            Log.i(TAG, "RTSP 401 received, auto-authenticating for user: ${creds.username}")
                            authContext.authRetried = true
                            authContext.authChallenge = challenge

                            val reqLine = RtspAuthHelper.extractRequestMethodAndUri(lastReq)
                            val method = reqLine?.first ?: "DESCRIBE"
                            val uri = reqLine?.second ?: cleanTargetUrl

                            val authHeader = RtspAuthHelper.buildAuthorizationHeader(
                                method = method,
                                uri = uri,
                                credentials = creds,
                                authenticateHeader = challenge
                            )

                            if (authHeader != null) {
                                val authenticatedRequest = injectAuthorizationHeader(lastReq, authHeader)
                                authContext.lastClientRequest = authenticatedRequest

                                // 消费掉当前 401 响应的全部字节（包含 header 以及可能的 Content-Length body）
                                val contentLength = parseContentLength(headerString)
                                val totalResponseLen = headerEndIndex + 4 + contentLength
                                if (currentBytes.size >= totalResponseLen) {
                                    val excess = currentBytes.copyOfRange(totalResponseLen, currentBytes.size)
                                    buffer.reset()
                                    buffer.write(excess)
                                } else {
                                    val remaining = totalResponseLen - currentBytes.size
                                    skipBytes(remoteIn, remaining)
                                    buffer.reset()
                                }

                                // 重新发送带 Authorization 的请求
                                remoteOut.write(authenticatedRequest.toByteArray(Charsets.UTF_8))
                                remoteOut.flush()

                                // 继续循环读取下一个远端响应（通常为 200 OK + SDP）
                                continue
                            }
                        }
                    }

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
                            val fixedSdp = fixSdp(sdpString, cleanTargetUrl, proxyBaseUrl)
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
                            fixedHeader = fixedHeader.replace(cleanTargetUrl.trimEnd('/'), proxyBaseUrl.trimEnd('/'))

                            // 发送修复后的 DESCRIBE 响应给客户端
                            clientOut.write(fixedHeader.toByteArray(Charsets.UTF_8))
                            clientOut.write("\r\n\r\n".toByteArray(Charsets.UTF_8))
                            clientOut.write(fixedSdpBytes)
                            clientOut.flush()

                            buffer.reset()
                            break
                        }
                    } else {
                        // 普通响应（如 OPTIONS 200 OK，或者认证失败的 401），直接转发输出
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

                    val textStr = textBaos.toString(Charsets.UTF_8.name())
                    val replacedText = textStr.replace(cleanTargetUrl.trimEnd('/'), proxyBaseUrl.trimEnd('/'))
                    clientOut.write(replacedText.toByteArray(Charsets.UTF_8))
                    clientOut.flush()
                } else {
                    // 交织二进制数据包 ($ + channel + 2-byte length + payload)
                    val channelId = bis.read()
                    if (channelId == -1) break
                    val lenHigh = bis.read()
                    if (lenHigh == -1) break
                    val lenLow = bis.read()
                    if (lenLow == -1) break
                    val packetLen = ((lenHigh and 0xFF) shl 8) or (lenLow and 0xFF)

                    val packetData = ByteArray(packetLen)
                    readFully(bis, packetData)

                    // 检查是否为 OPUS 音频 RTP 包（RTP 报头至少 12 字节，Byte 1 为 M+PT）
                    if (packetLen >= 12 && (packetData[0].toInt() and 0xC0) == 0x80) {
                        val pt = packetData[1].toInt() and 0x7F
                        if (pt == opusPayloadType) {
                            // 提取原包的序列号、时间戳、SSRC，构造虚拟头包
                            val origSeq = ((packetData[2].toInt() and 0xFF) shl 8) or (packetData[3].toInt() and 0xFF)
                            val tsBytes = packetData.copyOfRange(4, 8)
                            val ssrcBytes = packetData.copyOfRange(8, 12)

                            Log.i(
                                TAG,
                                "Intercepted first OPUS RTP packet (seq=$origSeq, ch=$channelId). Injecting synthetic OpusHead & OpusTags packets..."
                            )

                            // 1. 注入合成的 OpusHead 包 (seq = origSeq - 2)
                            val opusHeadSeq = (origSeq - 2 + 65536) % 65536
                            val opusHeadRtp = buildOpusHeadRtpPacket(pt, opusHeadSeq, tsBytes, ssrcBytes, opusChannels)
                            clientOut.write('$'.code)
                            clientOut.write(channelId)
                            clientOut.write((opusHeadRtp.size ushr 8) and 0xFF)
                            clientOut.write(opusHeadRtp.size and 0xFF)
                            clientOut.write(opusHeadRtp)

                            // 2. 注入合成的 OpusTags 包 (seq = origSeq - 1)
                            val opusTagsSeq = (origSeq - 1 + 65536) % 65536
                            val opusTagsRtp = buildOpusTagsRtpPacket(pt, opusTagsSeq, tsBytes, ssrcBytes)
                            clientOut.write('$'.code)
                            clientOut.write(channelId)
                            clientOut.write((opusTagsRtp.size ushr 8) and 0xFF)
                            clientOut.write(opusTagsRtp.size and 0xFF)
                            clientOut.write(opusTagsRtp)

                            // 3. 输出原始音频数据包
                            clientOut.write('$'.code)
                            clientOut.write(channelId)
                            clientOut.write(lenHigh)
                            clientOut.write(lenLow)
                            clientOut.write(packetData)
                            clientOut.flush()

                            opusInjected = true
                            Log.i(TAG, "Synthetic Opus headers injected successfully. Switching to transparent passthrough.")
                            break
                        }
                    }

                    // 非目标 OPUS 包，原样写出
                    clientOut.write('$'.code)
                    clientOut.write(channelId)
                    clientOut.write(lenHigh)
                    clientOut.write(lenLow)
                    clientOut.write(packetData)
                    clientOut.flush()

                    nonOpusPacketCount++
                    if (nonOpusPacketCount > 100 && opusPayloadType == null) {
                        opusInjected = true
                        break
                    }
                }
            }

            // 第三阶段：已完成初始化与注入，切换为全速原始字节透传
            val passthroughBuf = ByteArray(65536)
            while (!clientSocket.isClosed && !remoteSocket.isClosed) {
                val r = bis.read(passthroughBuf)
                if (r <= 0) break
                clientOut.write(passthroughBuf, 0, r)
                clientOut.flush()
            }

        } catch (e: Exception) {
            Log.d(TAG, "Remote to client stream closed: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            try { remoteSocket.close() } catch (_: Exception) {}
        }
    }

    /**
     * 修复并标准化 SDP 报文：
     * 1. 替换绝对控制 URL 为本地代理基址；
     * 2. 将非标 HEVC 编码名称替换为标准 H265；
     * 3. 针对缺失 a=fmtp 或缺少参数集的 H.264/H.265 视频轨，自动注入兜底的标准 fmtp 参数集。
     */
    fun fixSdp(sdp: String, targetUrl: String, proxyBaseUrl: String): String {
        var result = sdp
        val cleanTargetUrl = RtspAuthHelper.getCleanUrl(targetUrl)

        // 1. 替换绝对控制 URL 为本地代理基址
        result = result.replace(cleanTargetUrl.trimEnd('/'), proxyBaseUrl.trimEnd('/'))
        if (cleanTargetUrl != targetUrl) {
            result = result.replace(targetUrl.trimEnd('/'), proxyBaseUrl.trimEnd('/'))
        }

        // 将可能存在的非标 HEVC 编码名称标准化为 ExoPlayer 支持的 H265
        result = result.replace(Regex("""a=rtpmap:(\d+)\s+HEVC/""", RegexOption.IGNORE_CASE)) { matchResult ->
            "a=rtpmap:${matchResult.groupValues[1]} H265/"
        }

        val delimiter = if (result.contains("\r\n")) "\r\n" else "\n"

        // 检查 H.264 视频轨是否缺失 fmtp 或缺少 sprop-parameter-sets
        val h264Regex = Regex("""a=rtpmap:(\d+)\s+H264/90000""", RegexOption.IGNORE_CASE)
        val h264Match = h264Regex.find(result)
        if (h264Match != null) {
            val pt = h264Match.groupValues[1]
            val fmtpRegex = Regex("""a=fmtp:$pt([ \t]+[^\r\n]*)""", RegexOption.IGNORE_CASE)
            val fmtpMatch = fmtpRegex.find(result)
            if (fmtpMatch == null) {
                Log.w(TAG, "Detected H.264 video payload $pt missing a=fmtp, injecting default fmtp fallback")
                val fmtpLine = "${delimiter}a=fmtp:$pt $DEFAULT_H264_FMTP"
                result = result.replace(h264Match.value, h264Match.value + fmtpLine)
            } else if (!fmtpMatch.value.contains("sprop-parameter-sets", ignoreCase = true)) {
                Log.w(TAG, "Detected H.264 video payload $pt missing sprop-parameter-sets in fmtp, injecting parameter sets")
                val updatedFmtp = "${fmtpMatch.value};sprop-parameter-sets=Z2QAKqwsaoHgCJ+WbgICAoAAAfSAAHUwdDA=,aO48gA=="
                result = result.replace(fmtpMatch.value, updatedFmtp)
            }
        }

        // 检查 H.265 视频轨是否缺失 fmtp 或缺少 sprop-vps/sprop-sps/sprop-pps
        val h265Regex = Regex("""a=rtpmap:(\d+)\s+H265/90000""", RegexOption.IGNORE_CASE)
        val h265Match = h265Regex.find(result)
        if (h265Match != null) {
            val pt = h265Match.groupValues[1]
            val fmtpRegex = Regex("""a=fmtp:$pt([ \t]+[^\r\n]*)""", RegexOption.IGNORE_CASE)
            val fmtpMatch = fmtpRegex.find(result)
            if (fmtpMatch == null) {
                Log.w(TAG, "Detected H.265 video payload $pt missing a=fmtp, injecting default fmtp fallback")
                val fmtpLine = "${delimiter}a=fmtp:$pt $DEFAULT_H265_FMTP"
                result = result.replace(h265Match.value, h265Match.value + fmtpLine)
            } else {
                val valStr = fmtpMatch.value
                val hasVps = valStr.contains("sprop-vps", ignoreCase = true)
                val hasSps = valStr.contains("sprop-sps", ignoreCase = true)
                val hasPps = valStr.contains("sprop-pps", ignoreCase = true)
                if (!hasVps || !hasSps || !hasPps) {
                    Log.w(TAG, "Detected H.265 video payload $pt missing VPS/SPS/PPS in fmtp, replacing with fallback fmtp")
                    val updatedFmtp = "a=fmtp:$pt $DEFAULT_H265_FMTP"
                    result = result.replace(fmtpMatch.value, updatedFmtp)
                }
            }
        }

        return result
    }

    fun extractAuthenticateHeader(headerText: String): String? {
        return headerText.lines().firstOrNull { line ->
            line.startsWith("WWW-Authenticate:", ignoreCase = true)
        }
    }

    fun injectAuthorizationHeader(request: String, authHeader: String): String {
        val delimiter = if (request.contains("\r\n")) "\r\n" else "\n"
        val lines = request.split(delimiter).toMutableList()
        if (lines.isEmpty()) return request

        val authIndex = lines.indexOfFirst { it.startsWith("Authorization:", ignoreCase = true) }
        if (authIndex >= 0) {
            lines[authIndex] = authHeader
        } else {
            val insertPos = if (lines.size > 1) 1 else lines.size
            lines.add(insertPos, authHeader)
        }
        return lines.joinToString(delimiter)
    }

    private fun skipBytes(input: InputStream, count: Int) {
        var remaining = count
        val buf = ByteArray(minOf(remaining, 4096))
        while (remaining > 0) {
            val read = input.read(buf, 0, minOf(remaining, buf.size))
            if (read < 0) break
            remaining -= read
        }
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
