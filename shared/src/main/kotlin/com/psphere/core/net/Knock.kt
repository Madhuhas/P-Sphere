package com.psphere.core.net

import com.psphere.core.node.HostPort
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException

/** What a plain connection attempt says about a device that didn't answer PSphere. */
enum class Knock {
    /** The device is there, but nothing listens on PSphere's port: PSphere isn't running. */
    REFUSED,
    /** Nothing at that address: the device is off, asleep or gone. */
    GONE,
    /** No answer: PSphere isn't open there, or a firewall drops the connection (Windows hides closed ports, so the two look the same). */
    SILENT;

    companion object {
        /** Knocks on [at] without speaking PSphere. Blocking, up to [timeoutMs]. */
        fun at(at: HostPort, timeoutMs: Int = 3_000): Knock = try {
            Socket().use { it.connect(InetSocketAddress(at.host, at.port), timeoutMs) }
            SILENT // It connected, so PSphere's own handshake is what failed.
        } catch (e: SocketTimeoutException) {
            SILENT
        } catch (e: IOException) {
            classify(e)
        }

        internal fun classify(e: IOException): Knock {
            val m = listOfNotNull(e.message, e.cause?.message).joinToString(" ")
            return when {
                e is NoRouteToHostException || "EHOSTUNREACH" in m || "ENETUNREACH" in m || "No route to host" in m -> GONE
                "ECONNREFUSED" in m || (e is ConnectException && "refused" in m.lowercase()) -> REFUSED
                else -> SILENT
            }
        }
    }
}
