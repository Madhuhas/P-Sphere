package com.psphere.core.node

import com.psphere.core.identity.Fingerprint
import com.psphere.core.sha256
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/**
 * What a linking QR code carries. The scanner connects to [hosts]:[port], checks the device there
 * has [fingerprint], and proves it scanned the code by sending [token] (single use, short-lived).
 */
data class PairingOffer(
    val deviceId: String,
    val name: String,
    val hosts: List<String>,
    val port: Int,
    val fingerprint: Fingerprint,
    val token: String,
) {
    fun toUri(): String = buildString {
        append("psphere://link?v=3")
        append("&id=").append(deviceId)
        append("&n=").append(enc(name))
        append("&h=").append(hosts.joinToString(",") { enc(it) })
        append("&p=").append(port)
        append("&f=").append(fingerprint.text)
        append("&t=").append(token)
    }

    companion object {
        fun parse(uri: String): PairingOffer? = runCatching {
            if (!uri.startsWith("psphere://link?")) return null
            val q = uri.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            if (q["v"] != "3") return null
            val fp = Fingerprint.parse(q["f"] ?: return null) ?: return null
            val id = q["id"] ?: return null
            if (id != fp.deviceId) return null
            PairingOffer(
                deviceId = id,
                name = dec(q["n"].orEmpty()),
                hosts = q["h"].orEmpty().split(',').map(::dec).filter { it.isNotBlank() },
                port = q["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null,
                fingerprint = fp,
                token = q["t"]?.takeIf { it.length >= 16 } ?: return null,
            ).takeIf { it.hosts.isNotEmpty() }
        }.getOrNull()

        fun newToken(): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(18).also { SecureRandom().nextBytes(it) })

        /**
         * 6-digit code shown on both screens when linking without a QR code. It is derived from both
         * keys, so a device in the middle would make the two screens show different codes.
         */
        fun shortCode(a: Fingerprint, b: Fingerprint): String {
            val (x, y) = if (a.hex < b.hex) a to b else b to a
            val h = sha256(x.bytes + y.bytes)
            val n = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or
                ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
            return "%06d".format(n % 1_000_000)
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
    }
}

/** Asks the user to compare a 6-digit code. Blocks until they answer; false if they decline or time out. */
fun interface CodeConfirmer {
    fun confirm(otherDeviceName: String, code: String): Boolean
}
