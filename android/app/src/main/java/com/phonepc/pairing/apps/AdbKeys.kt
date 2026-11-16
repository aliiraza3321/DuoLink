package com.phonepc.pairing.apps

import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The key DuoLink uses for this phone's own wireless debugging: an RSA key and
 * a self-signed certificate, which is what adbd remembers after pairing.
 *
 * The key never leaves the phone (app-private storage). Whoever holds it can
 * open a shell on this phone, which is why it is generated here, kept here,
 * and only used against 127.0.0.1.
 */
internal class AdbKeys(val privateKey: PrivateKey, val certificate: Certificate) {

    companion object {
        private const val KEY = "adb.key"
        private const val CERT = "adb.crt"
        private const val DAY_MS = 24L * 3600 * 1000

        /** The stored key, or a freshly made one (written into [dir]). */
        fun load(dir: File): AdbKeys {
            val keyFile = File(dir, KEY)
            val certFile = File(dir, CERT)
            if (keyFile.isFile && certFile.isFile) {
                runCatching {
                    val factory = KeyFactory.getInstance("RSA")
                    val key = factory.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    val cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream())
                    return AdbKeys(key, cert)
                }
            }
            val pair = KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048, SecureRandom())
            }.generateKeyPair()
            val der = selfSigned(pair, "CN=DuoLink")
            dir.mkdirs()
            keyFile.writeBytes(pair.private.encoded)
            certFile.writeBytes(der)
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream())
            return AdbKeys(pair.private, cert)
        }

        /** A new self-signed X.509 certificate for [pair] (SHA256withRSA, 30 years). */
        private fun selfSigned(pair: java.security.KeyPair, subject: String): ByteArray {
            val algorithm = seq(
                oid(byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)),
                octetNull(),
            )
            val name = name(subject)
            val now = System.currentTimeMillis()
            val body = seq(
                tlv(0xa0, integer(BigInteger.valueOf(2))),                 // v3
                integer(BigInteger(96, SecureRandom()).add(BigInteger.ONE)), // serial
                algorithm,
                name,
                seq(time(now - DAY_MS), time(now + 30 * 365 * DAY_MS)),
                name,
                pair.public.encoded,
            )
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(pair.private)
                update(body)
                sign()
            }
            return seq(body, algorithm, tlv(0x03, byteArrayOf(0) + signature))
        }

        // ---- Minimal DER writer (the structures a certificate needs) ----

        private fun tlv(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val n = content.size
            when {
                n < 0x80 -> out.write(n)
                n < 0x100 -> { out.write(0x81); out.write(n) }
                n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xff) }
                else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xff); out.write(n and 0xff) }
            }
            out.write(content)
            return out.toByteArray()
        }

        private fun seq(vararg parts: ByteArray): ByteArray {
            val all = ByteArrayOutputStream()
            parts.forEach(all::write)
            return tlv(0x30, all.toByteArray())
        }

        private fun integer(value: BigInteger) = tlv(0x02, value.toByteArray())

        private fun oid(encoded: ByteArray) = tlv(0x06, encoded)

        private fun octetNull() = byteArrayOf(0x05, 0x00)

        private fun name(dn: String): ByteArray {
            val commonName = oid(byteArrayOf(0x55, 0x04, 0x03))
            val value = tlv(0x0c, dn.removePrefix("CN=").toByteArray(Charsets.UTF_8))
            return seq(tlv(0x31, seq(commonName, value)))
        }

        private fun time(millis: Long): ByteArray {
            val utc = TimeZone.getTimeZone("UTC")
            val date = Date(millis)
            val year = java.util.Calendar.getInstance(utc).apply { time = date }.get(java.util.Calendar.YEAR)
            return if (year < 2050) {
                val text = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT).apply { timeZone = utc }.format(date)
                tlv(0x17, text.toByteArray(Charsets.US_ASCII))
            } else {
                val text = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.ROOT).apply { timeZone = utc }.format(date)
                tlv(0x18, text.toByteArray(Charsets.US_ASCII))
            }
        }
    }
}
