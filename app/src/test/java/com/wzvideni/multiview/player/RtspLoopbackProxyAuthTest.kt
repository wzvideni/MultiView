package com.wzvideni.multiview.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspLoopbackProxyAuthTest {

    @Test
    fun testExtractAuthenticateHeader() {
        val response = """
            RTSP/1.0 401 Unauthorized
            CSeq: 1
            WWW-Authenticate: Digest realm="LIVE555 Streaming Media", nonce="3d1f11c793ff8564daaf68369ec2646d"
            Date: Fri, 23 Oct 2026 03:00:00 GMT
        """.trimIndent()

        val authHeader = RtspLoopbackProxy.extractAuthenticateHeader(response)
        assertNotNull(authHeader)
        assertTrue(authHeader!!.contains("Digest realm=\"LIVE555 Streaming Media\""))
    }

    @Test
    fun testInjectAuthorizationHeader() {
        val originalRequest = """
            DESCRIBE rtsp://36.138.2.25:6554/867400020316612 RTSP/1.0
            CSeq: 1
            Accept: application/sdp
        """.trimIndent()

        val authHeader = "Authorization: Digest username=\"867400020316612\", realm=\"test\", nonce=\"abc\", uri=\"rtsp://36.138.2.25:6554/867400020316612\", response=\"12345\""
        val modified = RtspLoopbackProxy.injectAuthorizationHeader(originalRequest, authHeader)

        assertTrue(modified.contains(authHeader))
        assertTrue(modified.lines()[0].startsWith("DESCRIBE"))
        assertEquals(authHeader, modified.lines()[1])
    }

    @Test
    fun testFixSdpWithAuthUrl() {
        val originalSdp = """
            v=0
            o=- 1612000000 1 IN IP4 36.138.2.25
            s=RTSP Session
            c=IN IP4 0.0.0.0
            t=0 0
            m=video 0 RTP/AVP 96
            a=control:rtsp://36.138.2.25:6554/867400020316612/track0
            a=rtpmap:96 H264/90000
        """.trimIndent()

        val targetUrl = "rtsp://867400020316612:@36.138.2.25:6554/867400020316612"
        val proxyBaseUrl = "rtsp://127.0.0.1:55432/token123"

        val fixedSdp = RtspLoopbackProxy.fixSdp(originalSdp, targetUrl, proxyBaseUrl)

        // 验证绝对控制 URL 已被成功替换为本地代理基址（即便传入了带鉴权信息的 targetUrl）
        assertTrue(fixedSdp.contains("a=control:rtsp://127.0.0.1:55432/token123/track0"))
        // 验证 H.264 缺失 fmtp 得到了自动修复补充
        assertTrue(fixedSdp.contains("a=fmtp:96"))
    }
}
