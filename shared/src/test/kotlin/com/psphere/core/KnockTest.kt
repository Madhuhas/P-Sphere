package com.psphere.core

import com.psphere.core.net.Knock
import com.psphere.core.node.HostPort
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ServerSocket

class KnockTest {
    @Test fun aClosedPortOnThisMachineIsRefused() {
        // Grab a free port, then close it so nothing listens there.
        val port = ServerSocket(0).use { it.localPort }
        assertEquals(Knock.REFUSED, Knock.at(HostPort("127.0.0.1", port)))
    }

    @Test fun somethingListeningButNotAnsweringIsSilent() {
        ServerSocket(0).use { s -> assertEquals(Knock.SILENT, Knock.at(HostPort("127.0.0.1", s.localPort))) }
    }

    @Test fun errorsAreReadTheSameOnAndroidAndDesktop() {
        assertEquals(Knock.GONE, Knock.classify(NoRouteToHostException("No route to host")))
        assertEquals(Knock.GONE, Knock.classify(ConnectException("failed to connect to /10.0.0.9 (port 47820) after 3000ms: connect failed: EHOSTUNREACH (No route to host)")))
        assertEquals(Knock.REFUSED, Knock.classify(ConnectException("Connection refused: connect")))
        assertEquals(Knock.REFUSED, Knock.classify(ConnectException("failed to connect to /10.0.0.9 (port 47820): connect failed: ECONNREFUSED (Connection refused)")))
        assertEquals(Knock.SILENT, Knock.classify(java.io.IOException("something else")))
    }
}
