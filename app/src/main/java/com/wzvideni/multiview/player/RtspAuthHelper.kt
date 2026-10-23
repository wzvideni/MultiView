package com.wzvideni.multiview.player

import android.util.Base64
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/**
 * RTSP 认证凭据
 * @property username 认证用户名
 * @property password 认证密码（未设置时默认为空字符串）
 */
data class RtspCredentials(
    val username: String,
    val password: String = ""
)

/**
 * RTSP 鉴权与 URL 规范化助手
 *
 * 核心功能：
 * 1. 从 URL 或 path 末尾提取设备编号作为认证用户名；
 * 2. 剥离 URL 中的 userInfo，生成标准纯净的 RTSP Request-URI；
 * 3. 将无鉴权链接转换为带设备 ID 鉴权的标准格式（rtsp://{deviceId}:@{host}:{port}/{path}）；
 * 4. 支持根据远端 401 WWW-Authenticate 响应计算合法的 Digest / Basic Authorization 请求头。
 */
object RtspAuthHelper {

    /**
     * 判断 URL 是否显式包含认证信息 (user[:password]@)
     */
    fun hasAuthCredentials(url: String): Boolean {
        if (url.isBlank()) return false
        val prefixIndex = url.indexOf("://")
        if (prefixIndex < 0) return false
        val afterScheme = url.substring(prefixIndex + 3)
        val firstSlash = afterScheme.indexOf('/')
        val hostPart = if (firstSlash >= 0) afterScheme.substring(0, firstSlash) else afterScheme
        return hostPart.contains("@")
    }

    /**
     * 从 RTSP URL 的 path 末尾提取设备编号
     * 例如:
     * - rtsp://36.138.2.25:6554/867400020316612 -> "867400020316612"
     * - rtsp://867400020316612:@36.138.2.25:6554/867400020316612 -> "867400020316612"
     * - rtsp://36.138.2.25:6554/live/867400020316612 -> "867400020316612"
     * - rtsp://36.138.2.25:6554/867400020316612?token=123 -> "867400020316612"
     */
    fun extractDeviceId(url: String): String? {
        if (url.isBlank()) return null
        val path = try {
            URI(url).path ?: ""
        } catch (_: Exception) {
            val afterScheme = url.substringAfter("://", "")
            val slashIdx = afterScheme.indexOf('/')
            if (slashIdx >= 0) {
                afterScheme.substring(slashIdx).substringBefore('?')
            } else ""
        }
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        val lastSegment = segments.lastOrNull() ?: return null
        return lastSegment.takeIf { it.isNotBlank() }
    }

    /**
     * 剥离 URL 中的 userInfo，获取符合 RTSP RFC 规范的标准干净 URL
     * 例如:
     * rtsp://867400020316612:@36.138.2.25:6554/867400020316612
     * -> rtsp://36.138.2.25:6554/867400020316612
     */
    fun getCleanUrl(url: String): String {
        if (url.isBlank() || !url.contains("@")) return url
        return try {
            val uri = URI(url)
            val scheme = uri.scheme ?: "rtsp"
            val host = uri.host ?: return fallbackCleanUrl(url)
            val portStr = if (uri.port > 0) ":${uri.port}" else ""
            val rawPath = uri.rawPath ?: ""
            val rawQuery = if (!uri.rawQuery.isNullOrBlank()) "?${uri.rawQuery}" else ""
            "$scheme://$host$portStr$rawPath$rawQuery"
        } catch (_: Exception) {
            fallbackCleanUrl(url)
        }
    }

    private fun fallbackCleanUrl(url: String): String {
        return url.replace(Regex("""^(rtsps?://)[^@]+@""", RegexOption.IGNORE_CASE), "$1")
    }

    /**
     * 将无鉴权链接转换为带设备 ID 鉴权的标准格式：
     * rtsp://{deviceId}:@{host}:{port}/{path}
     * 如果已含凭据或无法解析出 deviceId，则保持原样返回
     */
    fun formatWithDeviceAuth(url: String): String {
        if (url.isBlank()) return url
        if (hasAuthCredentials(url)) return url

        val deviceId = extractDeviceId(url) ?: return url

        return try {
            val uri = URI(url)
            val scheme = uri.scheme ?: "rtsp"
            val host = uri.host ?: return url
            val portStr = if (uri.port > 0) ":${uri.port}" else ""
            val rawPath = uri.rawPath ?: ""
            val rawQuery = if (!uri.rawQuery.isNullOrBlank()) "?${uri.rawQuery}" else ""
            "$scheme://$deviceId:@$host$portStr$rawPath$rawQuery"
        } catch (_: Exception) {
            url.replace(Regex("""^(rtsps?://)""", RegexOption.IGNORE_CASE), "$1$deviceId:@")
        }
    }

    /**
     * 从 URL 中解析认证凭据：
     * 1. 优先提取 URL 中自带的 userInfo (如 username:password@ 或 username:@)；
     * 2. 若 URL 不含 userInfo，且 [fallbackToDeviceId] 为 true，则提取 path 末尾的设备编号作为 username（密码为空）。
     */
    fun extractCredentials(url: String, fallbackToDeviceId: Boolean = true): RtspCredentials? {
        if (url.isBlank()) return null

        // 1. 优先从 URL 的 userInfo 获取
        val userInfo = try {
            URI(url).userInfo
        } catch (_: Exception) {
            val afterScheme = url.substringAfter("://", "")
            val hostPart = afterScheme.substringBefore("/")
            if (hostPart.contains("@")) hostPart.substringBefore("@") else null
        }

        if (!userInfo.isNullOrBlank()) {
            val username = userInfo.substringBefore(":")
            val password = if (userInfo.contains(":")) userInfo.substringAfter(":") else ""
            if (username.isNotBlank()) {
                return RtspCredentials(username, password)
            }
        }

        // 2. 兜底提取 path 末尾的设备编号
        if (fallbackToDeviceId) {
            val deviceId = extractDeviceId(url)
            if (!deviceId.isNullOrBlank()) {
                return RtspCredentials(username = deviceId, password = "")
            }
        }

        return null
    }

    /**
     * 从 RTSP 请求文本报文中提取 Request-Line (Method 与 Request-URI)
     */
    fun extractRequestMethodAndUri(request: String): Pair<String, String>? {
        val firstLine = request.lines().firstOrNull()?.trim() ?: return null
        val parts = firstLine.split("\\s+".toRegex())
        if (parts.size >= 2) {
            return Pair(parts[0].uppercase(), parts[1])
        }
        return null
    }

    /**
     * 根据 WWW-Authenticate 响应头构造合法的 Authorization 请求头（支持 Digest 和 Basic）
     *
     * @param method RTSP 请求方法 (如 DESCRIBE, SETUP, PLAY)
     * @param uri RTSP 请求的 Request-URI (纯净地址，不含 userInfo)
     * @param credentials 认证凭证
     * @param authenticateHeader 服务端返回的 WWW-Authenticate 头整行或值
     */
    fun buildAuthorizationHeader(
        method: String,
        uri: String,
        credentials: RtspCredentials,
        authenticateHeader: String
    ): String? {
        if (authenticateHeader.isBlank()) return null

        return if (authenticateHeader.contains("Digest", ignoreCase = true)) {
            buildDigestAuthorization(method, uri, credentials, authenticateHeader)
        } else if (authenticateHeader.contains("Basic", ignoreCase = true)) {
            buildBasicAuthorization(credentials)
        } else {
            null
        }
    }

    private fun buildBasicAuthorization(credentials: RtspCredentials): String {
        val userPass = "${credentials.username}:${credentials.password}"
        val encoded = try {
            Base64.encodeToString(userPass.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().encodeToString(userPass.toByteArray(Charsets.UTF_8))
        }
        return "Authorization: Basic $encoded"
    }

    private fun buildDigestAuthorization(
        method: String,
        uri: String,
        credentials: RtspCredentials,
        header: String
    ): String {
        val realm = extractParam(header, "realm") ?: ""
        val nonce = extractParam(header, "nonce") ?: ""
        val opaque = extractParam(header, "opaque")
        val qop = extractParam(header, "qop")

        val ha1 = md5Hex("${credentials.username}:$realm:${credentials.password}")
        val ha2 = md5Hex("$method:$uri")

        return if (qop != null && qop.contains("auth", ignoreCase = true)) {
            val nc = "00000001"
            val cnonce = UUID.randomUUID().toString().replace("-", "").take(8)
            val response = md5Hex("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
            val opaquePart = if (!opaque.isNullOrBlank()) ", opaque=\"$opaque\"" else ""
            "Authorization: Digest username=\"${credentials.username}\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\", qop=auth, nc=$nc, cnonce=\"$cnonce\"$opaquePart"
        } else {
            val response = md5Hex("$ha1:$nonce:$ha2")
            val opaquePart = if (!opaque.isNullOrBlank()) ", opaque=\"$opaque\"" else ""
            "Authorization: Digest username=\"${credentials.username}\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\"$opaquePart"
        }
    }

    private fun extractParam(header: String, paramName: String): String? {
        val regex = Regex("""$paramName=(?:"([^"]*)"|([^\s,]+))""", RegexOption.IGNORE_CASE)
        val match = regex.find(header) ?: return null
        return match.groupValues[1].ifEmpty { match.groupValues.getOrNull(2) }?.takeIf { it.isNotBlank() }
    }

    fun md5Hex(data: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(data.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
