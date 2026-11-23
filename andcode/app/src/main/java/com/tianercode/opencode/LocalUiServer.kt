package com.tianercode.opencode

import android.content.Context
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean





































class LocalUiServer(
    private val context: Context,
    
    private val enginePort: () -> Int,
    
    private val authorization: () -> String?,
    private val upstream: OkHttpClient,
    
    private val headInjection: () -> String,
    








    private val hostRpc: ((ticket: String, method: String, argsJson: String) -> String)? = null,
    
    private val panelHtml: (() -> String)? = null,
    





    private val longPollMs: Long = LONG_POLL_MS,
    












    private val preferredPort: Int? = null,
    







    private val onEngineRequest: () -> Unit = {},
) {
    
    private val eventClients = java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.LinkedBlockingQueue<String>>()

    














    private val hostToken: String = processHostToken

    







    fun bootPath(): String = "/__boot?t=$hostToken"

    
    private fun tokenScript(): String =
        "<script>window.__opencodeHostToken=\"$hostToken\";</script>"

    

















    private fun authorised(request: HttpRequest): Boolean {
        val header = request.headers["x-opencode-host-token"]
        if (header != null) return secretEquals(header)
        val cookie = cookieValue(request, COOKIE_NAME) ?: return false
        return secretEquals(cookie)
    }

    private fun secretEquals(presented: String): Boolean {
        if (presented.length != hostToken.length) return false
        var difference = 0
        for (index in hostToken.indices) {
            difference = difference or (presented[index].code xor hostToken[index].code)
        }
        return difference == 0
    }

    private fun cookieValue(
        request: HttpRequest,
        name: String,
    ): String? =
        request.headers["cookie"]
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }

    
    private fun queryValue(
        request: HttpRequest,
        name: String,
    ): String? =
        request.query
            .removePrefix("?")
            .split('&')
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?.takeIf { it.isNotEmpty() }

    




    private fun tokenInQuery(request: HttpRequest): Boolean {
        val presented = queryValue(request, "token") ?: return false
        return secretEquals(presented)
    }

    





    fun pushEvent(
        channel: String,
        payloadJson: String,
    ) {
        pushToPage(
            org.json.JSONObject()
                .put("kind", "emit")
                .put("channel", channel)
                .put("payload", payloadJson),
        )
    }

    







    fun pushResolve(
        ticket: String,
        ok: Boolean,
        payloadJson: String,
    ) {
        pushToPage(
            org.json.JSONObject()
                .put("kind", "resolve")
                .put("ticket", ticket)
                .put("ok", ok)
                .put("payload", payloadJson),
        )
    }

    private fun pushToPage(message: org.json.JSONObject) {
        
        
        
        val payload = message.toString()
        eventClients.forEach { it.offer(payload) }
    }

    










    fun pushEval(script: String) {
        pushToPage(
            org.json.JSONObject()
                .put("kind", "eval")
                .put("script", script),
        )
    }
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val running = AtomicBoolean(false)

    
    val port: Int get() = serverSocket?.localPort ?: -1

    










    fun start(): Int {
        check(!running.get()) { "LocalUiServer is already running" }
        val socket = bindLoopback()
        serverSocket = socket
        running.set(true)
        acceptThread =
            Thread({
                while (running.get()) {
                    val client =
                        try {
                            socket.accept()
                        } catch (error: IOException) {
                            
                            
                            if (running.get()) android.util.Log.w(TAG, "accept failed", error)
                            break
                        }
                    Thread({ serve(client) }, "local-ui-$port").apply { isDaemon = true }.start()
                }
            }, "local-ui-accept").apply { isDaemon = true }
        acceptThread?.start()
        android.util.Log.i(TAG, "local UI server on http://127.0.0.1:$port")
        return socket.localPort
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    






    private fun bindLoopback(): ServerSocket {
        val wanted = preferredPort
        if (wanted != null) {
            val candidate = ServerSocket()
            try {
                candidate.reuseAddress = true
                candidate.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), wanted), BACKLOG)
                return candidate
            } catch (error: IOException) {
                runCatching { candidate.close() }
                android.util.Log.w(TAG, "preferred UI port $wanted is unavailable, using an ephemeral one", error)
            }
        }
        return ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
    }

    

    private fun serve(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = READ_TIMEOUT_MS
                val input = socket.getInputStream().buffered()
                val output = BufferedOutputStream(socket.getOutputStream())
                
                
                
                
                
                
                while (true) {
                    val request = HttpRequest.read(input) ?: break
                    if (request.isWebSocketUpgrade) {
                        
                        
                        
                        
                        
                        
                        
                        
                        
                        
                        
                        
                        
                        
                        
                        if (!authorised(request) && !tokenInQuery(request)) {
                            writeError(output, 403, "host token required", closeAfter = true)
                            output.flush()
                            break
                        }
                        pumpWebSocket(socket, input, output, request)
                        break
                    }
                    val closeAfter = respond(output, request)
                    output.flush()
                    if (closeAfter) break
                }
            }
        } catch (error: Throwable) {
            
            
            if (error !is IOException) android.util.Log.w(TAG, "request failed", error)
        }
    }

    
    private fun respond(
        output: BufferedOutputStream,
        request: HttpRequest,
    ): Boolean {
        val closeAfter = request.wantsClose
        val path = request.path
        
        
        
        
        android.util.Log.i(TAG, "${request.method} $path")

        
        
        
        
        if (path == "/__host/rpc") {
            if (!authorised(request)) {
                writeError(output, 403, "host token required", closeAfter)
                return closeAfter
            }
            val rpc = hostRpc
            if (rpc == null) {
                writeError(output, 503, "no host attached to this server", closeAfter)
                return closeAfter
            }
            val body = request.body.toString(Charsets.UTF_8)
            val call =
                runCatching { org.json.JSONObject(body) }.getOrNull()
            if (call == null) {
                writeError(output, 400, "malformed host call", closeAfter)
                return closeAfter
            }
            
            
            
            
            val reply =
                runCatching {
                    rpc(call.optString("ticket"), call.optString("method"), call.optString("args"))
                }.getOrElse {
                    org.json.JSONObject()
                        .put("accepted", false)
                        .put("reason", it.message ?: it::class.java.simpleName)
                        .toString()
                }
            writeBody(output, 200, "application/json; charset=utf-8", reply.toByteArray(Charsets.UTF_8), closeAfter)
            return closeAfter
        }

        
        
        
        if (path == "/__host/events") {
            
            
            if (!authorised(request)) {
                writeError(output, 403, "host token required", closeAfter)
                return closeAfter
            }
            
            
            
            streamEvents(output, false)
            return true
        }
        
        
        
        
        
        
        
        
        
        
        if (path == "/__host/next") {
            if (!authorised(request)) {
                writeError(output, 403, "host token required", closeAfter)
                return closeAfter
            }
            val queue = java.util.concurrent.LinkedBlockingQueue<String>()
            eventClients.add(queue)
            val payload =
                try {
                    queue.poll(longPollMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                } finally {
                    eventClients.remove(queue)
                }
            if (payload == null) {
                
                
                writeBody(output, 204, "text/plain; charset=utf-8", ByteArray(0), closeAfter)
            } else {
                writeBody(output, 200, "application/json; charset=utf-8", payload.toByteArray(Charsets.UTF_8), closeAfter)
            }
            return closeAfter
        }
        
        
        
        
        if (path == "/__boot") {
            val presented = queryValue(request, "t")
            if (presented == null || !secretEquals(presented)) {
                writeError(output, 403, "boot token required", closeAfter)
                return closeAfter
            }
            val cookie =
                "Set-Cookie: $COOKIE_NAME=$hostToken; Path=/; HttpOnly; SameSite=Strict"
            writeBody(
                output,
                200,
                "text/html; charset=utf-8",
                "<!doctype html><meta charset=utf-8><title>OpenCode</title>".toByteArray(Charsets.UTF_8),
                closeAfter,
                listOf(cookie, "Refresh: 0; url=/"),
            )
            return closeAfter
        }

        
        
        
        
        
        
        
        
        
        
        
        
        if (path == "/panel" || path == "/" || path == "/index.html") {
            if (!authorised(request)) {
                writeError(output, 403, "host token required", closeAfter)
                return closeAfter
            }
        }

        if (path == "/panel") {
            val panel = panelHtml?.invoke()
            if (panel == null) {
                writeError(output, 404, "no panel document on this server", closeAfter)
                return closeAfter
            }
            
            
            
            
            
            val injection =
                "<link rel=\"stylesheet\" href=\"/assets/bridge/platform.css\">\n" +
                    "<script src=\"/assets/bridge/host-shim.js\"></script></head>"
            val body = panel.replace("</head>", tokenScript() + injection).toByteArray(Charsets.UTF_8)
            writeBody(output, 200, "text/html; charset=utf-8", body, closeAfter)
            return closeAfter
        }

        
        
        if (path == "/" || path == "/index.html") {
            val html =
                runCatching {
                    context.assets.open("${DesktopWebAssets.ROOT}/index.html")
                        .use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
            if (html == null) {
                writeError(output, 500, "renderer document missing from assets", closeAfter)
                return closeAfter
            }
            
            
            val adapted = keyboardAwareRendererDocument(html)
            
            
            val body = adapted.replace("</head>", tokenScript() + headInjection()).toByteArray(Charsets.UTF_8)
            writeBody(output, 200, "text/html; charset=utf-8", body, closeAfter)
            return closeAfter
        }

        
        if (path.startsWith("/assets/bridge/")) {
            val name = path.removePrefix("/assets/bridge/")
            if (name.contains("..") || name.contains('\\')) {
                writeError(output, 400, "bad path", closeAfter)
                return closeAfter
            }
            val bytes = runCatching { context.assets.open("bridge/$name").use { it.readBytes() } }.getOrNull()
            if (bytes == null) {
                writeError(output, 404, "no such bridge asset", closeAfter)
                return closeAfter
            }
            writeBody(output, 200, mimeOf(name), bytes, closeAfter)
            return closeAfter
        }

        
        
        
        val asset = runCatching { DesktopWebAssets.open(context, path) }.getOrNull()
        if (asset != null) {
            val bytes = asset.data.use { it.readBytes() }
            writeBody(output, 200, asset.mimeType ?: mimeOf(path), bytes, closeAfter)
            return closeAfter
        }

        
        
        
        
        
        
        
        
        
        
        
        if (!authorised(request)) {
            writeError(output, 403, "host token required", closeAfter)
            return closeAfter
        }

        return proxy(output, request)
    }

    















    private fun streamEvents(
        output: BufferedOutputStream,
        closeAfter: Boolean,
    ) {
        val head =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\n" +
                "X-Accel-Buffering: no\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                connectionHeader(false) + "\r\n"
        runCatching {
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            output.write(chunk("retry: 1000\n\n"))
            output.flush()
        }.onFailure { return }

        val queue = java.util.concurrent.LinkedBlockingQueue<String>()
        eventClients.add(queue)
        try {
            while (running.get()) {
                val line = queue.poll(HEARTBEAT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                
                
                output.write(chunk(line?.let { "data: $it\n\n" } ?: ": keepalive\n\n"))
                output.flush()
            }
        } catch (error: Throwable) {
            
        } finally {
            eventClients.remove(queue)
            runCatching {
                output.write(LAST_CHUNK)
                output.flush()
            }
        }
        android.util.Log.i(TAG, "event stream closed (${eventClients.size} left)")
    }

    
    private fun proxy(
        output: BufferedOutputStream,
        request: HttpRequest,
    ): Boolean {
        
        
        
        runCatching { onEngineRequest() }
        val target = "http://127.0.0.1:${enginePort()}${request.path}${request.query}"
        if (request.method != "GET") {
            
            
            
            
            android.util.Log.d(
                TAG,
                "proxy ${request.method} ${request.path} body=${request.body.size} " +
                    "ct=${request.headers["content-type"]}",
            )
        }
        val body =
            if (request.body.isEmpty()) {
                null
            } else {
                request.body.toRequestBody(request.headers["content-type"]?.toMediaTypeOrNull())
            }
        val builder = Request.Builder().url(target)
        
        
        
        for ((name, value) in request.headers) {
            if (name.lowercase() in HOP_BY_HOP) continue
            
            
            
            if (name.equals("x-opencode-host-token", ignoreCase = true)) continue
            if (name.equals("cookie", ignoreCase = true)) continue
            runCatching { builder.addHeader(name, value) }
        }
        if (request.headers["authorization"] == null) {
            authorization()?.let { builder.header("Authorization", it) }
        }
        builder.method(request.method, body)

        val response: Response =
            try {
                upstream.newCall(builder.build()).execute()
            } catch (error: Throwable) {
                val message = "engine unreachable: ${error.message ?: error::class.java.simpleName}"
                writeError(output, 502, message, request.wantsClose)
                return request.wantsClose
            }
        response.use { result ->
            val headers = mutableListOf<String>()
            for (name in result.headers.names()) {
                if (name.lowercase() in HOP_BY_HOP) continue
                for (value in result.headers.values(name)) headers += "$name: $value"
            }
            val stream = result.body?.byteStream()
            val declaredLength = headers.firstOrNull { it.startsWith("Content-Length", ignoreCase = true) }
            val chunked = declaredLength == null
            
            
            
            
            
            
            
            
            
            
            
            val closeAfter = request.wantsClose
            writeHead(output, result.code, result.message.ifEmpty { "OK" }, headers, chunked, closeAfter)
            
            
            
            
            
            output.flush()
            if (stream == null) {
                if (chunked) output.write(LAST_CHUNK)
            } else {
                if (chunked) copyChunked(stream, output) else stream.use { it.copyTo(output, COPY_BUFFER_BYTES) }
            }
            output.flush()
            return closeAfter
        }
    }

    






    private fun copyChunked(
        input: InputStream,
        output: BufferedOutputStream,
    ) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(Integer.toHexString(read).toByteArray(Charsets.ISO_8859_1))
            output.write(CRLF)
            output.write(buffer, 0, read)
            output.write(CRLF)
            
            
            output.flush()
        }
        output.write(LAST_CHUNK)
        output.flush()
    }

    












    private fun pumpWebSocket(
        client: Socket,
        input: InputStream,
        output: BufferedOutputStream,
        request: HttpRequest,
    ) {
        val key = request.headers["sec-websocket-key"]
        val version = request.headers["sec-websocket-version"]
        if (key == null || version == null) {
            writeError(output, 400, "not a websocket handshake", true)
            return
        }
        val upstreamSocket =
            try {
                Socket(InetAddress.getByName("127.0.0.1"), enginePort())
            } catch (error: Throwable) {
                writeError(output, 502, "engine unreachable: ${error.message ?: "connect failed"}", true)
                return
            }
        upstreamSocket.use { engine ->
            val engineOut = BufferedOutputStream(engine.getOutputStream())
            
            
            val head = StringBuilder()
            head.append("GET ${request.path}${request.query} HTTP/1.1\r\n")
            head.append("Host: 127.0.0.1:${enginePort()}\r\n")
            head.append("Upgrade: websocket\r\n")
            head.append("Connection: Upgrade\r\n")
            head.append("Sec-WebSocket-Key: $key\r\n")
            head.append("Sec-WebSocket-Version: $version\r\n")
            request.headers["sec-websocket-protocol"]?.let { head.append("Sec-WebSocket-Protocol: $it\r\n") }
            authorization()?.let { head.append("Authorization: $it\r\n") }
            head.append("\r\n")
            engineOut.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            engineOut.flush()

            val engineIn = engine.getInputStream().buffered()
            val statusLine = readLine(engineIn) ?: run {
                writeError(output, 502, "engine closed the handshake", true)
                return
            }
            
            
            val engineHeaders = mutableListOf<String>()
            while (true) {
                val line = readLine(engineIn) ?: break
                if (line.isEmpty()) break
                engineHeaders += line
            }
            val accepted = statusLine.contains(" 101")
            if (!accepted) {
                
                
                
                
                output.write("$statusLine\r\n".toByteArray(Charsets.ISO_8859_1))
                if (engineHeaders.none { it.startsWith("Content-Length", ignoreCase = true) }) {
                    
                    
                    output.write("Content-Length: 0\r\n".toByteArray(Charsets.ISO_8859_1))
                }
                engineHeaders.forEach { output.write("$it\r\n".toByteArray(Charsets.ISO_8859_1)) }
                output.write("\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
                return
            }

            
            
            
            output.write("HTTP/1.1 101 Switching Protocols\r\n".toByteArray(Charsets.ISO_8859_1))
            output.write("Upgrade: websocket\r\n".toByteArray(Charsets.ISO_8859_1))
            output.write("Connection: Upgrade\r\n".toByteArray(Charsets.ISO_8859_1))
            output.write("Sec-WebSocket-Accept: ${webSocketAccept(key)}\r\n".toByteArray(Charsets.ISO_8859_1))
            request.headers["sec-websocket-protocol"]?.let {
                output.write("Sec-WebSocket-Protocol: $it\r\n".toByteArray(Charsets.ISO_8859_1))
            }
            output.write("\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()

            val upstreamPump = driver(engine) { engineIn.copyTo(client.getOutputStream(), COPY_BUFFER_BYTES) }
            val pagePump = driver(client) { input.copyTo(engineOut, COPY_BUFFER_BYTES) }
            
            
            while (upstreamPump.isAlive && pagePump.isAlive) {
                upstreamPump.join(WEBSOCKET_POLL_MS)
            }
        }
    }

    






    private fun driver(
        closeable: java.io.Closeable,
        copy: () -> Unit,
    ): Thread {
        val thread =
            Thread({
                try {
                    copy()
                } catch (error: Throwable) {
                    
                } finally {
                    runCatching { closeable.close() }
                }
            }, "local-ui-pump").apply { isDaemon = true }
        thread.start()
        return thread
    }

    

    private fun writeBody(
        output: BufferedOutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
        closeAfter: Boolean = true,
        extraHeaders: List<String> = emptyList(),
    ) {
        val head =
            "HTTP/1.1 $status ${reason(status)}\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                
                
                "Cache-Control: no-store\r\n" +
                extraHeaders.joinToString("") { "$it\r\n" } +
                connectionHeader(closeAfter) + "\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    





    private fun writeHead(
        output: BufferedOutputStream,
        status: Int,
        reason: String,
        headers: List<String>,
        chunked: Boolean,
        closeAfter: Boolean,
    ) {
        val head = StringBuilder("HTTP/1.1 $status $reason\r\n")
        headers.forEach { head.append(it).append("\r\n") }
        if (chunked) head.append("Transfer-Encoding: chunked\r\n")
        head.append(connectionHeader(closeAfter)).append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun connectionHeader(closeAfter: Boolean): String =
        if (closeAfter) "Connection: close\r\n" else "Connection: keep-alive\r\n"

    private fun writeError(
        output: BufferedOutputStream,
        status: Int,
        message: String,
        closeAfter: Boolean = true,
    ) {
        writeBody(output, status, "text/plain; charset=utf-8", message.toByteArray(Charsets.UTF_8), closeAfter)
    }

    private fun reason(status: Int): String =
        when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            
            
            
            403 -> "Forbidden"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "OK"
        }

    private fun mimeOf(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json", "webmanifest", "map" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "woff2" -> "font/woff2"
            
            
            
            
            
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "wasm" -> "application/wasm"
            "txt" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }

    companion object {
        private const val TAG = "AndCode.LocalUi"

        









        private val processHostToken: String = java.util.UUID.randomUUID().toString()

        







        const val PREFERRED_PORT = 39678
        private const val BACKLOG = 64
        private const val READ_TIMEOUT_MS = 30_000
        private const val COPY_BUFFER_BYTES = 32 * 1024

        








        private const val COOKIE_NAME = "oc_host"

        
        private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)
        private val LAST_CHUNK = "0\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

        






        private fun chunk(text: String): ByteArray {
            val body = text.toByteArray(Charsets.UTF_8)
            val head = (body.size.toString(16) + "\r\n").toByteArray(Charsets.ISO_8859_1)
            return head + body + CRLF
        }

        
        private val KEEPALIVE = ": keepalive\n\n".toByteArray(Charsets.ISO_8859_1)
        private const val WEBSOCKET_POLL_MS = 500L

        
        private const val HEARTBEAT_MS = 15_000L

        
        private const val LONG_POLL_MS = 20_000L

        private val HOP_BY_HOP =
            setOf(
                "connection",
                "keep-alive",
                "proxy-authenticate",
                "proxy-authorization",
                "te",
                "trailer",
                "transfer-encoding",
                "upgrade",
                "host",
                "content-length",
            )

        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        
        fun webSocketAccept(key: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
            val hash = digest.digest((key + WEBSOCKET_GUID).toByteArray(Charsets.ISO_8859_1))
            return Base64.getEncoder().encodeToString(hash)
        }

        private fun readLine(input: InputStream): String? {
            val out = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte < 0) return if (out.isEmpty()) null else out.toString()
                if (byte == '\n'.code) return out.toString().trimEnd('\r')
                out.append(byte.toChar())
                if (out.length > MAX_LINE) return null
            }
        }

        private const val MAX_LINE = 16 * 1024
    }

    
    private class HttpRequest(
        val method: String,
        val path: String,
        val query: String,
        val headers: Headers,
        val body: ByteArray,
        
        val httpVersion: String,
    ) {
        val isWebSocketUpgrade: Boolean =
            headers["upgrade"]?.equals("websocket", ignoreCase = true) == true

        





        val wantsClose: Boolean =
            headers["connection"]?.contains("close", ignoreCase = true) == true || httpVersion == "HTTP/1.0"

        companion object {
            fun read(input: InputStream): HttpRequest? {
                val requestLine = readLine(input) ?: return null
                val parts = requestLine.split(' ')
                if (parts.size < 3) return null
                val target = parts[1]
                val at = target.indexOf('?')
                val path = if (at >= 0) target.substring(0, at) else target
                val query = if (at >= 0) target.substring(at) else ""

                val builder = Headers.Builder()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    builder.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
                }
                val headers = builder.build()
                val body =
                    when {
                        headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true ->
                            readChunked(input)
                        else -> {
                            val length = headers["content-length"]?.toIntOrNull() ?: 0
                            if (length <= 0) ByteArray(0) else readExactly(input, length)
                        }
                    }
                return HttpRequest(parts[0], path, query, headers, body, parts[2].trim())
            }

            private fun readExactly(
                input: InputStream,
                length: Int,
            ): ByteArray {
                val buffer = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(buffer, read, length - read)
                    if (n < 0) break
                    read += n
                }
                return if (read == length) buffer else buffer.copyOf(read)
            }

            private fun readChunked(input: InputStream): ByteArray {
                val out = java.io.ByteArrayOutputStream()
                while (true) {
                    val sizeLine = readLine(input) ?: break
                    val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
                    if (size == 0) {
                        
                        while (true) {
                            val line = readLine(input) ?: break
                            if (line.isEmpty()) break
                        }
                        break
                    }
                    out.write(readExactly(input, size))
                    readLine(input)
                }
                return out.toByteArray()
            }
        }
    }
}

















private val VIEWPORT_META = Regex("<meta\\s+name=\"viewport\"[^>]*>")
private val VIEWPORT_CONTENT = Regex("content=\"([^\"]*)\"")

internal fun keyboardAwareRendererDocument(html: String): String {
    if (html.contains("interactive-widget=")) return html
    return VIEWPORT_META.replace(html) { meta ->
        VIEWPORT_CONTENT.replace(meta.value) { content ->
            "content=\"${content.groupValues[1]}, interactive-widget=resizes-content\""
        }
    }
}
