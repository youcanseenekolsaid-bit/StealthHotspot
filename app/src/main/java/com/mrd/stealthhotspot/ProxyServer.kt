package com.mrd.stealthhotspot

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HTTP/HTTPS Proxy Server
 *
 * Handles HTTP requests by forwarding them to the target host.
 * Handles HTTPS via HTTP CONNECT tunneling.
 *
 * Connected devices set this as their proxy to route internet
 * traffic through the phone's mobile data connection.
 */
class ProxyServer(private val port: Int = DEFAULT_PORT) {

    companion object {
        const val TAG = "ProxyServer"
        const val DEFAULT_PORT = 8080
        const val BUFFER_SIZE = 32768
        const val CONNECT_TIMEOUT = 10000
        const val SO_TIMEOUT = 60000
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var executor: ExecutorService? = null

    val isActive: Boolean get() = isRunning.get()

    fun start() {
        if (isRunning.get()) return

        executor = Executors.newCachedThreadPool()
        isRunning.set(true)

        executor?.submit {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                Log.i(TAG, "Proxy server started on port $port")

                while (isRunning.get()) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        clientSocket.soTimeout = SO_TIMEOUT
                        executor?.submit { handleClient(clientSocket) }
                    } catch (e: IOException) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Error accepting connection: ${e.message}")
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start proxy server: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        executor?.shutdownNow()
        executor = null
        Log.i(TAG, "Proxy server stopped")
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            val clientInput = clientSocket.getInputStream()
            val clientOutput = clientSocket.getOutputStream()

            // Read the first line of the HTTP request
            val requestLine = readLine(clientInput)
            if (requestLine.isNullOrEmpty()) {
                clientSocket.close()
                return
            }

            // Read all headers
            val headers = mutableListOf<String>()
            var line: String?
            while (true) {
                line = readLine(clientInput)
                if (line.isNullOrEmpty()) break
                headers.add(line)
            }

            val parts = requestLine.split(" ")
            if (parts.size < 3) {
                clientSocket.close()
                return
            }

            val method = parts[0]

            if (method.equals("CONNECT", ignoreCase = true)) {
                handleConnect(clientSocket, clientInput, clientOutput, parts[1])
            } else {
                handleHttp(clientSocket, clientInput, clientOutput, requestLine, headers)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client handler error: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    /**
     * HTTPS tunneling via CONNECT method.
     * Client sends: CONNECT host:port HTTP/1.1
     * We connect to the target, respond with 200, then relay data bidirectionally.
     */
    private fun handleConnect(
        clientSocket: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        hostPort: String
    ) {
        val (host, port) = parseHostPort(hostPort, 443)

        try {
            val targetSocket = Socket()
            targetSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)
            targetSocket.soTimeout = SO_TIMEOUT

            // Send 200 Connection Established
            val response = "HTTP/1.1 200 Connection Established\r\n\r\n"
            clientOutput.write(response.toByteArray())
            clientOutput.flush()

            // Relay data bidirectionally
            relay(clientSocket, targetSocket)
        } catch (e: Exception) {
            val errorResponse = "HTTP/1.1 502 Bad Gateway\r\n\r\n"
            try {
                clientOutput.write(errorResponse.toByteArray())
                clientOutput.flush()
            } catch (_: Exception) {}
            Log.d(TAG, "CONNECT to $host:$port failed: ${e.message}")
        }
    }

    /**
     * Regular HTTP request proxying.
     * Client sends: GET http://host/path HTTP/1.1
     * We forward the request to the target host and relay the response back.
     */
    private fun handleHttp(
        clientSocket: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        requestLine: String,
        headers: List<String>
    ) {
        val parts = requestLine.split(" ")
        val method = parts[0]
        val url = parts[1]
        val httpVersion = parts[2]

        // Parse target host and path from absolute URL
        val hostAndPath = parseUrl(url)
        if (hostAndPath == null) {
            val errorResponse = "HTTP/1.1 400 Bad Request\r\n\r\n"
            clientOutput.write(errorResponse.toByteArray())
            clientOutput.flush()
            return
        }

        val (host, port, path) = hostAndPath

        try {
            val targetSocket = Socket()
            targetSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)
            targetSocket.soTimeout = SO_TIMEOUT

            val targetOutput = targetSocket.getOutputStream()
            val targetInput = targetSocket.getInputStream()

            // Rewrite the request line with relative path
            val newRequestLine = "$method $path $httpVersion\r\n"
            targetOutput.write(newRequestLine.toByteArray())

            // Forward headers (replace Host if needed)
            var hasHost = false
            for (header in headers) {
                if (header.lowercase().startsWith("host:")) {
                    targetOutput.write("Host: $host\r\n".toByteArray())
                    hasHost = true
                } else if (!header.lowercase().startsWith("proxy-")) {
                    targetOutput.write("$header\r\n".toByteArray())
                }
            }
            if (!hasHost) {
                targetOutput.write("Host: $host\r\n".toByteArray())
            }
            targetOutput.write("\r\n".toByteArray())
            targetOutput.flush()

            // If there's a request body (POST, PUT), forward it
            if (method.equals("POST", true) || method.equals("PUT", true)) {
                val contentLength = headers.find {
                    it.lowercase().startsWith("content-length:")
                }?.substringAfter(":")?.trim()?.toIntOrNull()

                if (contentLength != null && contentLength > 0) {
                    val buffer = ByteArray(BUFFER_SIZE)
                    var remaining = contentLength
                    while (remaining > 0) {
                        val read = clientInput.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read == -1) break
                        targetOutput.write(buffer, 0, read)
                        remaining -= read
                    }
                    targetOutput.flush()
                }
            }

            // Relay response back to client
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = try {
                    targetInput.read(buffer)
                } catch (_: Exception) { -1 }
                if (read == -1) break
                clientOutput.write(buffer, 0, read)
                clientOutput.flush()
            }

            targetSocket.close()
        } catch (e: Exception) {
            val errorResponse = "HTTP/1.1 502 Bad Gateway\r\n\r\n"
            try {
                clientOutput.write(errorResponse.toByteArray())
                clientOutput.flush()
            } catch (_: Exception) {}
            Log.d(TAG, "HTTP proxy to $host:$port failed: ${e.message}")
        }
    }

    /**
     * Bidirectional relay between client and target sockets.
     */
    private fun relay(clientSocket: Socket, targetSocket: Socket) {
        val clientToTarget = Thread {
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                val input = clientSocket.getInputStream()
                val output = targetSocket.getOutputStream()
                while (isRunning.get()) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (_: Exception) {}
            try { targetSocket.shutdownOutput() } catch (_: Exception) {}
        }

        val targetToClient = Thread {
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                val input = targetSocket.getInputStream()
                val output = clientSocket.getOutputStream()
                while (isRunning.get()) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (_: Exception) {}
            try { clientSocket.shutdownOutput() } catch (_: Exception) {}
        }

        clientToTarget.start()
        targetToClient.start()

        // Wait for both to finish
        try { clientToTarget.join() } catch (_: Exception) {}
        try { targetToClient.join() } catch (_: Exception) {}

        try { clientSocket.close() } catch (_: Exception) {}
        try { targetSocket.close() } catch (_: Exception) {}
    }

    // --- Helpers ---

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = try { input.read() } catch (_: Exception) { -1 }
            if (b == -1) {
                return if (sb.isEmpty()) null else sb.toString()
            }
            if (b == '\n'.code) {
                if (prev == '\r'.code) {
                    sb.deleteCharAt(sb.length - 1)
                }
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
        }
    }

    private fun parseHostPort(hostPort: String, defaultPort: Int): Pair<String, Int> {
        return if (hostPort.contains(":")) {
            val parts = hostPort.split(":")
            Pair(parts[0], parts[1].toIntOrNull() ?: defaultPort)
        } else {
            Pair(hostPort, defaultPort)
        }
    }

    data class UrlParts(val host: String, val port: Int, val path: String)

    private fun parseUrl(url: String): UrlParts? {
        return try {
            val withoutScheme = when {
                url.startsWith("http://") -> url.removePrefix("http://")
                url.startsWith("https://") -> url.removePrefix("https://")
                else -> return null
            }
            val pathIndex = withoutScheme.indexOf("/")
            val hostPort = if (pathIndex != -1) withoutScheme.substring(0, pathIndex) else withoutScheme
            val path = if (pathIndex != -1) withoutScheme.substring(pathIndex) else "/"

            val (host, port) = parseHostPort(hostPort, 80)
            UrlParts(host, port, path)
        } catch (_: Exception) {
            null
        }
    }
}
