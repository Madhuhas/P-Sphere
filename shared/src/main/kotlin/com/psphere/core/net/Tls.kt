package com.psphere.core.net

import com.psphere.core.ProtocolException
import com.psphere.core.identity.DeviceIdentity
import com.psphere.core.identity.Fingerprint
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Mutually authenticated TLS between PSphere devices.
 *
 * Both sides always present their self-signed certificate. The TLS layer itself accepts any
 * certificate; who the peer is gets decided right after the handshake by comparing its public-key
 * [Fingerprint] with the linked devices (or with the fingerprint from a pairing QR code). Nothing
 * is read from or sent to a peer before that check.
 */
class Tls(private val identity: DeviceIdentity) {

    val context: SSLContext = SSLContext.getInstance("TLS").apply {
        init(arrayOf(KeyManager(identity.privateKey, identity.certificate)), arrayOf(AcceptAll), SecureRandom())
    }

    fun serverSocket(port: Int): SSLServerSocket =
        (context.serverSocketFactory.createServerSocket(port) as SSLServerSocket).apply {
            needClientAuth = true
            reuseAddress = true
        }

    /** Connects and completes the handshake. The caller must check [peerFingerprint] next. */
    fun connect(host: String, port: Int, timeoutMs: Int = CONNECT_TIMEOUT_MS): SSLSocket {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), timeoutMs)
            raw.soTimeout = HANDSHAKE_TIMEOUT_MS
            val ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            ssl.useClientMode = true
            ssl.startHandshake()
            return ssl
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val HANDSHAKE_TIMEOUT_MS = 15_000

        /** Completes the handshake on an accepted socket. */
        fun accept(socket: Socket): SSLSocket {
            val ssl = socket as SSLSocket
            ssl.soTimeout = HANDSHAKE_TIMEOUT_MS
            ssl.startHandshake()
            return ssl
        }

        fun peerFingerprint(socket: SSLSocket): Fingerprint {
            val certs = socket.session.peerCertificates
            if (certs.isEmpty()) throw ProtocolException("The other device didn't identify itself")
            return Fingerprint.ofCertificate(certs[0])
        }
    }

    private class KeyManager(private val key: PrivateKey, private val cert: X509Certificate) : X509ExtendedKeyManager() {
        private fun isEc(keyType: String?) = keyType != null && keyType.startsWith("EC")
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = if (isEc(keyType)) arrayOf(ALIAS) else null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = if (isEc(keyType)) arrayOf(ALIAS) else null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) =
            if (keyType == null || keyType.any(::isEc)) ALIAS else null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = if (isEc(keyType)) ALIAS else null
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) =
            chooseClientAlias(keyType, issuers, null)
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) =
            chooseServerAlias(keyType, issuers, null)
        override fun getCertificateChain(alias: String?) = if (alias == ALIAS) arrayOf(cert) else null
        override fun getPrivateKey(alias: String?) = if (alias == ALIAS) key else null

        companion object { const val ALIAS = "psphere" }
    }

    /** Identity is checked by fingerprint after the handshake, so certificates aren't validated here. */
    private object AcceptAll : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = requireCert(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = requireCert(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = requireCert(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = requireCert(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = requireCert(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = requireCert(chain)
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        private fun requireCert(chain: Array<out X509Certificate>?) {
            if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("No certificate")
        }
    }
}
