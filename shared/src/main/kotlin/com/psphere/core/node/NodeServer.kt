package com.psphere.core.node

import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * Accepts connections from other devices and hands each to [Node.handle] on a worker thread.
 * At most [maxConnections] are served at once; extra connections wait in the accept queue.
 */
class NodeServer(private val node: Node, private val port: Int = 0, private val maxConnections: Int = 4) {
    @Volatile private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null

    /** The port actually listened on (useful when started with port 0). */
    val localPort: Int get() = socket?.localPort ?: 0

    @Synchronized
    fun start() {
        if (socket != null) return
        val ss = node.tls.serverSocket(port)
        val workers = Executors.newFixedThreadPool(maxConnections) { r -> Thread(r, "psphere-peer").apply { isDaemon = true } }
        val slots = Semaphore(maxConnections)
        socket = ss
        pool = workers
        Thread({
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (e: SocketException) { break } catch (e: Exception) { Thread.sleep(200); continue }
                slots.acquire()
                workers.execute {
                    try { node.handle(client) } catch (_: Exception) { } finally { slots.release() }
                }
            }
        }, "psphere-accept").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        pool?.shutdownNow()
        socket = null
        pool = null
    }
}
