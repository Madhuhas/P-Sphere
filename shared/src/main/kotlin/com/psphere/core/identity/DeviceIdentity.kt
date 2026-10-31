package com.psphere.core.identity

import com.psphere.core.sha256
import com.psphere.core.toHex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * This device's long-term key pair and self-signed certificate.
 *
 * The device ID is derived from the public key, so a peer that presents a different key can't
 * claim to be a linked device. Peers are pinned by [Fingerprint] (SHA-256 of the public key),
 * never by certificate, so a certificate can be reissued without unlinking.
 */
class DeviceIdentity private constructor(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {
    val publicKey: PublicKey get() = certificate.publicKey
    val fingerprint: Fingerprint = Fingerprint.of(publicKey)
    val deviceId: String get() = fingerprint.deviceId

    fun save(file: File) {
        val bytes = ByteArrayOutputStream().also { buf ->
            DataOutputStream(buf).use { out ->
                out.writeInt(FORMAT)
                out.writeBytesWithLength(privateKey.encoded)
                out.writeBytesWithLength(certificate.encoded)
            }
        }.toByteArray()
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        private const val FORMAT = 1

        fun generate(): DeviceIdentity {
            val gen = KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            val pair = gen.generateKeyPair()
            val fp = Fingerprint.of(pair.public)
            val cert = selfSign(pair.private, pair.public.encoded, "PSphere ${fp.deviceId}")
            return DeviceIdentity(pair.private, cert)
        }

        fun load(file: File): DeviceIdentity {
            DataInputStream(ByteArrayInputStream(file.readBytes())).use { input ->
                require(input.readInt() == FORMAT) { "Unknown identity file format" }
                val keyBytes = input.readBytesWithLength()
                val certBytes = input.readBytesWithLength()
                val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
                return DeviceIdentity(key, parseCertificate(certBytes))
            }
        }

        /** Loads the identity in [file], creating and saving a new one the first time. */
        fun loadOrCreate(file: File): DeviceIdentity =
            if (file.isFile) load(file) else generate().also { it.save(file) }

        fun parseCertificate(der: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

        private fun selfSign(key: PrivateKey, subjectPublicKeyInfo: ByteArray, commonName: String): X509Certificate {
            val ecdsaSha256 = Der.sequence(Der.oid("1.2.840.10045.4.3.2"))
            val name = Der.sequence(Der.set(Der.sequence(Der.oid("2.5.4.3"), Der.utf8(commonName))))
            val serial = BigInteger(1, ByteArray(16).also { SecureRandom().nextBytes(it) })
            val notBefore = Date(System.currentTimeMillis() - 24L * 3600 * 1000)
            val tbs = Der.sequence(
                Der.explicit(0, Der.integer(BigInteger.valueOf(2))),
                Der.integer(serial),
                ecdsaSha256,
                name,
                // UTCTime only covers years up to 2049, which is plenty for a pinned, self-signed key.
                Der.sequence(Der.utcTime(utc(notBefore)), Der.utcTime("491231235959Z")),
                name,
                subjectPublicKeyInfo,
            )
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(key); update(tbs); sign()
            }
            return parseCertificate(Der.sequence(tbs, ecdsaSha256, Der.bitString(signature)))
        }

        private fun utc(date: Date): String =
            SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)

        private fun DataOutputStream.writeBytesWithLength(b: ByteArray) { writeInt(b.size); write(b) }
        private fun DataInputStream.readBytesWithLength(): ByteArray {
            val n = readInt()
            require(n in 0..65_536) { "Corrupt identity file" }
            return ByteArray(n).also { readFully(it) }
        }
    }
}

/** SHA-256 of a public key (SubjectPublicKeyInfo DER). Used to pin linked devices. */
class Fingerprint(val bytes: ByteArray) {
    val hex: String get() = bytes.toHex()
    /** URL-safe text form used in QR codes. */
    val text: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    /** 16 lowercase base32 characters (80 bits): short enough to read, long enough to be unique. */
    val deviceId: String get() = base32(bytes.copyOf(10))

    fun matches(other: Fingerprint) = java.security.MessageDigest.isEqual(bytes, other.bytes)
    override fun equals(other: Any?) = other is Fingerprint && matches(other)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = hex

    companion object {
        fun of(key: PublicKey) = Fingerprint(sha256(key.encoded))
        fun ofCertificate(cert: java.security.cert.Certificate) = of(cert.publicKey)

        fun parse(text: String): Fingerprint? = runCatching {
            Fingerprint(Base64.getUrlDecoder().decode(text)).takeIf { it.bytes.size == 32 }
        }.getOrNull()

        fun parseHex(hex: String): Fingerprint? = runCatching {
            require(hex.length == 64)
            Fingerprint(ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        }.getOrNull()

        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
        private fun base32(data: ByteArray): String {
            val sb = StringBuilder()
            var buffer = 0
            var bits = 0
            for (b in data) {
                buffer = (buffer shl 8) or (b.toInt() and 0xff)
                bits += 8
                while (bits >= 5) {
                    sb.append(ALPHABET[(buffer ushr (bits - 5)) and 31])
                    bits -= 5
                }
            }
            if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
            return sb.toString()
        }
    }
}
