package com.wzvideni.multiview.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspAuthHelperTest {

    @Test
    fun testExtractDeviceId() {
        assertEquals(
            "867400020316612",
            RtspAuthHelper.extractDeviceId("rtsp://36.138.2.25:6554/867400020316612")
        )
        assertEquals(
            "867400020316612",
            RtspAuthHelper.extractDeviceId("rtsp://867400020316612:@36.138.2.25:6554/867400020316612")
        )
        assertEquals(
            "867400020316612",
            RtspAuthHelper.extractDeviceId("rtsp://36.138.2.25:6554/live/867400020316612")
        )
        assertEquals(
            "867400020316612",
            RtspAuthHelper.extractDeviceId("rtsp://36.138.2.25:6554/867400020316612?token=123")
        )
        assertEquals(
            "867400020316612",
            RtspAuthHelper.extractDeviceId("rtsp://36.138.2.25:6554/867400020316612/")
        )
    }

    @Test
    fun testHasAuthCredentials() {
        assertFalse(RtspAuthHelper.hasAuthCredentials("rtsp://36.138.2.25:6554/867400020316612"))
        assertTrue(RtspAuthHelper.hasAuthCredentials("rtsp://867400020316612:@36.138.2.25:6554/867400020316612"))
        assertTrue(RtspAuthHelper.hasAuthCredentials("rtsp://admin:123456@192.168.1.1:554/live"))
    }

    @Test
    fun testGetCleanUrl() {
        val authUrl = "rtsp://867400020316612:@36.138.2.25:6554/867400020316612"
        val expected = "rtsp://36.138.2.25:6554/867400020316612"
        assertEquals(expected, RtspAuthHelper.getCleanUrl(authUrl))

        val cleanUrl = "rtsp://36.138.2.25:6554/867400020316612"
        assertEquals(cleanUrl, RtspAuthHelper.getCleanUrl(cleanUrl))

        val adminUrl = "rtsp://admin:123456@36.138.2.25:6554/live?foo=bar"
        assertEquals("rtsp://36.138.2.25:6554/live?foo=bar", RtspAuthHelper.getCleanUrl(adminUrl))
    }

    @Test
    fun testFormatWithDeviceAuth() {
        val unauthUrl = "rtsp://36.138.2.25:6554/867400020316612"
        val expected = "rtsp://867400020316612:@36.138.2.25:6554/867400020316612"
        assertEquals(expected, RtspAuthHelper.formatWithDeviceAuth(unauthUrl))

        // 已有鉴权信息的直接保持原样
        assertEquals(expected, RtspAuthHelper.formatWithDeviceAuth(expected))
    }

    @Test
    fun testExtractCredentials() {
        val authUrl = "rtsp://867400020316612:@36.138.2.25:6554/867400020316612"
        val creds1 = RtspAuthHelper.extractCredentials(authUrl)
        assertNotNull(creds1)
        assertEquals("867400020316612", creds1?.username)
        assertEquals("", creds1?.password)

        val unauthUrl = "rtsp://36.138.2.25:6554/867400020316612"
        val creds2 = RtspAuthHelper.extractCredentials(unauthUrl, fallbackToDeviceId = true)
        assertNotNull(creds2)
        assertEquals("867400020316612", creds2?.username)
        assertEquals("", creds2?.password)
    }

    @Test
    fun testBuildDigestAuthorizationHeader() {
        val creds = RtspCredentials(username = "867400020316612", password = "")
        val challenge = """WWW-Authenticate: Digest realm="LIVE555 Streaming Media", nonce="3d1f11c793ff8564daaf68369ec2646d""""
        val authHeader = RtspAuthHelper.buildAuthorizationHeader(
            method = "DESCRIBE",
            uri = "rtsp://36.138.2.25:6554/867400020316612",
            credentials = creds,
            authenticateHeader = challenge
        )
        assertNotNull(authHeader)
        assertTrue(authHeader!!.startsWith("Authorization: Digest "))
        assertTrue(authHeader.contains("""username="867400020316612""""))
        assertTrue(authHeader.contains("""realm="LIVE555 Streaming Media""""))
        assertTrue(authHeader.contains("""nonce="3d1f11c793ff8564daaf68369ec2646d""""))
        assertTrue(authHeader.contains("""uri="rtsp://36.138.2.25:6554/867400020316612""""))
        assertTrue(authHeader.contains("""response="""))
    }
}
