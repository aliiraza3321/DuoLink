package com.phonepc.pairing

import com.phonepc.pairing.pairing.TokenParser
import org.junit.Assert.*
import org.junit.Test

class TokenParserTest {

    private val validHost = "192.168.1.150"
    private val validPort = 3000
    private val validToken = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4" // 40 chars
    private val validFp = "1A2B3C4D5E6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B1C2D3E4F5A6B7C8D9E0F1A2B" // 64 chars

    @Test
    fun testValidUriParsesSuccessfully() {
        val uri = "phonepair://pair?host=$validHost&port=$validPort&token=$validToken&fp=$validFp"
        val result = TokenParser.parse(uri)

        assertTrue("Valid URI must succeed", result.isSuccess)
        val payload = result.getOrThrow()
        assertEquals(validHost, payload.host)
        assertEquals(validPort, payload.port)
        assertEquals(validToken, payload.token)
        assertEquals(validFp, payload.fingerprintSha256)
    }

    @Test
    fun testRejectsInvalidScheme() {
        val uri = "https://pair?host=$validHost&port=$validPort&token=$validToken&fp=$validFp"
        val result = TokenParser.parse(uri)
        assertTrue("Invalid scheme must fail", result.isFailure)
    }

    @Test
    fun testRejectsPublicIp() {
        val uri = "phonepair://pair?host=8.8.8.8&port=$validPort&token=$validToken&fp=$validFp"
        val result = TokenParser.parse(uri)
        assertTrue("Public IP must be rejected", result.isFailure)
    }

    @Test
    fun testAcceptsLocalhostAndPrivateRanges() {
        assertTrue(TokenParser.isPrivateOrLocalIp("127.0.0.1"))
        assertTrue(TokenParser.isPrivateOrLocalIp("192.168.0.1"))
        assertTrue(TokenParser.isPrivateOrLocalIp("10.0.1.20"))
        assertTrue(TokenParser.isPrivateOrLocalIp("172.16.5.4"))
        assertTrue(TokenParser.isPrivateOrLocalIp("172.31.255.254"))
        assertFalse(TokenParser.isPrivateOrLocalIp("172.32.0.1"))
        assertFalse(TokenParser.isPrivateOrLocalIp("142.250.190.46"))
    }

    @Test
    fun testRejectsShortToken() {
        val shortToken = "abc123"
        val uri = "phonepair://pair?host=$validHost&port=$validPort&token=$shortToken&fp=$validFp"
        val result = TokenParser.parse(uri)
        assertTrue("Token < 32 characters must fail", result.isFailure)
    }

    @Test
    fun testRejectsInvalidFingerprint() {
        val shortFp = "A1B2C3"
        val uri = "phonepair://pair?host=$validHost&port=$validPort&token=$validToken&fp=$shortFp"
        val result = TokenParser.parse(uri)
        assertTrue("Fingerprint != 64 chars must fail", result.isFailure)
    }
}
