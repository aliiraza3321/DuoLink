package com.phonepc.pairing

import com.google.gson.JsonObject
import com.phonepc.pairing.network.Protocol
import com.phonepc.pairing.network.TlsPinningSocket
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {

    @Test
    fun testEnvelopeCreationAndParsing() {
        val payload = JsonObject().apply {
            addProperty("token", "secret123")
            addProperty("deviceName", "Pixel 8")
        }

        val json = Protocol.createEnvelope(Protocol.TYPE_PAIR_REQUEST, payload)
        val envelope = Protocol.parseEnvelope(json)

        assertNotNull("Envelope must parse successfully", envelope)
        assertEquals(Protocol.VERSION, envelope?.v)
        assertEquals(Protocol.TYPE_PAIR_REQUEST, envelope?.type)
        assertEquals("secret123", envelope?.payload?.get("token")?.asString)
        assertEquals("Pixel 8", envelope?.payload?.get("deviceName")?.asString)
    }

    @Test
    fun testRejectsInvalidVersionEnvelope() {
        val badJson = """
            {
                "v": 999,
                "type": "PAIR_REQUEST",
                "id": "uuid-123",
                "ts": 1234567890,
                "payload": {}
            }
        """.trimIndent()

        val envelope = Protocol.parseEnvelope(badJson)
        assertNull("Envelope with wrong version must be rejected", envelope)
    }

    @Test
    fun testHmacSha256Calculation() {
        val secretHex = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
        val nonce = "challenge_nonce_123"

        val proof1 = TlsPinningSocket.computeHmacSha256(secretHex, nonce)
        val proof2 = TlsPinningSocket.computeHmacSha256(secretHex, nonce)

        assertEquals("HMAC proof must be deterministic", proof1, proof2)
        assertEquals("SHA-256 HMAC output must be 64 hex characters", 64, proof1.length)

        val proofOther = TlsPinningSocket.computeHmacSha256(secretHex, "different_nonce")
        assertNotEquals("Different nonce must produce different proof", proof1, proofOther)
    }
}
