package com.psphere.core

import com.psphere.core.identity.DeviceIdentity
import com.psphere.core.identity.Fingerprint
import com.psphere.core.net.Tls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.concurrent.thread

class IdentityTlsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun certificateIsValidAndSelfSigned() {
        val id = DeviceIdentity.generate()
        id.certificate.checkValidity()
        id.certificate.verify(id.publicKey)
        assertEquals(16, id.deviceId.length)
        assertTrue(id.deviceId.all { it in 'a'..'z' || it in '2'..'7' })
    }

    @Test fun savedIdentityLoadsBackUnchanged() {
        val file = tmp.root.resolve("identity.bin")
        val a = DeviceIdentity.loadOrCreate(file)
        val b = DeviceIdentity.loadOrCreate(file)
        assertEquals(a.fingerprint, b.fingerprint)
        assertEquals(a.deviceId, b.deviceId)
        assertNotEquals(a.fingerprint, DeviceIdentity.generate().fingerprint)
    }

    @Test fun fingerprintTextRoundTrips() {
        val fp = DeviceIdentity.generate().fingerprint
        assertEquals(fp, Fingerprint.parse(fp.text))
        assertEquals(fp, Fingerprint.parseHex(fp.hex))
        assertEquals(null, Fingerprint.parse("short"))
    }

    @Test fun bothSidesSeeEachOthersFingerprint() {
        val server = DeviceIdentity.generate()
        val client = DeviceIdentity.generate()
        val ss = Tls(server).serverSocket(0)
        var seenByServer: Fingerprint? = null
        val t = thread {
            ss.accept().use { s ->
                val ssl = Tls.accept(s)
                seenByServer = Tls.peerFingerprint(ssl)
                ssl.outputStream.write(42); ssl.outputStream.flush()
                ssl.inputStream.read()
            }
        }
        Tls(client).connect("127.0.0.1", ss.localPort).use { ssl ->
            assertEquals(server.fingerprint, Tls.peerFingerprint(ssl))
            assertEquals(42, ssl.inputStream.read())
            ssl.outputStream.write(1); ssl.outputStream.flush()
        }
        t.join(5_000)
        ss.close()
        assertEquals(client.fingerprint, seenByServer)
    }

    @Test fun safeNamesNeverEscapeTheFolder() {
        assertEquals("x", SafeNames.relativePath("../../x", "f"))
        assertEquals("a/b.jpg", SafeNames.relativePath("a/./b.jpg", "f"))
        assertEquals("a/b.jpg", SafeNames.relativePath("a\\..\\b.jpg".replace("..\\", ""), "f"))
        assertEquals("etc/passwd", SafeNames.relativePath("/etc/passwd", "f"))
        assertEquals("f", SafeNames.relativePath("../..", "f"))
        assertEquals("_con.txt", SafeNames.segment("con.txt", "f"))
        assertEquals("a_b", SafeNames.segment("a:b", "f"))
        assertEquals("hidden", SafeNames.segment(".hidden", "f"))
    }
}
