package com.tianercode.opencode

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
























class LocalUiServerTest {
    private val servers = mutableListOf<LocalUiServer>()
    private val upstreams = mutableListOf<MockWebServer>()

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.stop() } }
        upstreams.forEach { runCatching { it.shutdown() } }
    }

    

    private fun startServer(
        enginePort: () -> Int = { 1 },
        authorization: () -> String? = { null },
        hostRpc: ((ticket: String, method: String, argsJson: String) -> String)? =
            { _, _, _ -> """{"accepted":true}""" },
        panelHtml: (() -> String)? = { DEFAULT_PANEL },
        longPollMs: Long = 20_000L,
        preferredPort: Int? = null,
    ): Int {
        val server =
            LocalUiServer(
                context = UnusableContext(),
                enginePort = enginePort,
                authorization = authorization,
                upstream = OkHttpClient(),
                headInjection = { "<script src=\"/assets/bridge/host-shim.js\"></script>" },
                hostRpc = hostRpc,
                panelHtml = panelHtml,
                longPollMs = longPollMs,
                preferredPort = preferredPort,
            )
        servers += server
        return server.start()
    }

    






    @Test
    fun `preferred port is used when it is free`() {
        var expected = -1
        var actual = -1
        
        for (attempt in 0 until 5) {
            val probe = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val candidate = probe.localPort
            probe.close()
            actual = startServer(preferredPort = candidate)
            if (actual == candidate) {
                expected = candidate
                break
            }
        }
        assertEquals(expected, actual)
    }

    


    @Test
    fun `preferred port collision falls back to an ephemeral port`() {
        val squatter = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        try {
            val wanted = squatter.localPort
            val actual = startServer(preferredPort = wanted)
            assertNotEquals(wanted, actual)
            assertTrue(actual > 0)
        } finally {
            squatter.close()
        }
    }

    private fun startUpstream(): MockWebServer =
        MockWebServer().apply {
            start()
            upstreams += this
        }

    





    private fun token(port: Int): String =
        servers.last { it.port == port }.bootPath().substringAfter("t=")

    
    private fun authedHeaders(port: Int): Map<String, String> =
        mapOf("X-Opencode-Host-Token" to token(port))

    
    private fun cookieHeaders(port: Int): Map<String, String> =
        mapOf("Cookie" to "oc_host=${token(port)}")

    
    private fun boot(
        port: Int,
        presentedToken: String = token(port),
    ): RawResponse = request(port, "GET", "/__boot?t=$presentedToken")

    private data class RawResponse(
        val statusCode: Int,
        val statusLine: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        val bodyText: String get() = body.toString(Charsets.UTF_8)

        fun header(name: String): String? = headers[name.lowercase()]
    }

    private fun open(port: Int): Socket =
        Socket().apply {
            connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
            soTimeout = READ_TIMEOUT_MS
        }

    private fun request(
        port: Int,
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        httpVersion: String = "HTTP/1.1",
        close: Boolean = true,
        readBody: Boolean = true,
    ): RawResponse {
        val requestText = buildString {
            append(method).append(' ').append(path).append(' ').append(httpVersion).append("\r\n")
            val all = LinkedHashMap<String, String>()
            all.putAll(headers)
            if (all.keys.none { it.equals("host", ignoreCase = true) }) all["Host"] = "127.0.0.1"
            if (close && all.keys.none { it.equals("connection", ignoreCase = true) }) {
                all["Connection"] = "close"
            }
            if (body != null && all.keys.none { it.equals("content-length", ignoreCase = true) }) {
                all["Content-Length"] = body.toByteArray(Charsets.UTF_8).size.toString()
            }
            all.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
            if (body != null) append(body)
        }
        open(port).use { socket ->
            socket.getOutputStream().apply {
                write(requestText.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            return readResponse(socket.getInputStream(), readBody)
        }
    }

    private fun readResponse(
        input: InputStream,
        readBody: Boolean = true,
    ): RawResponse {
        val statusLine = readLine(input) ?: error("no status line")
        val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: error("malformed status line: $statusLine")
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        if (!readBody) return RawResponse(statusCode, statusLine, headers, ByteArray(0))
        val body =
            when {
                headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
                headers["content-length"] != null -> readExactly(input, headers["content-length"]!!.toInt())
                else -> ByteArray(0)
            }
        return RawResponse(statusCode, statusLine, headers, body)
    }

    private fun readEventStreamFirstLine(
        port: Int,
        headers: Map<String, String> = cookieHeaders(port),
    ): Pair<RawResponse, String> {
        open(port).use { socket ->
            val requestText =
                buildString {
                    append("GET /__host/events HTTP/1.1\r\n")
                    append("Host: 127.0.0.1\r\n")
                    headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                    append("Connection: close\r\n\r\n")
                }
            socket.getOutputStream().apply {
                write(requestText.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val head = readResponse(socket.getInputStream(), readBody = false)
            
            
            
            
            val sizeLine = readLine(socket.getInputStream()) ?: ""
            val firstLine =
                if (head.header("transfer-encoding")?.contains("chunked") == true) {
                    readLine(socket.getInputStream()) ?: ""
                } else {
                    sizeLine
                }
            return head to firstLine
        }
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
            if (byte == '\n'.code) return out.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            out.write(byte)
            if (out.size() > MAX_LINE) return null
        }
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
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            out.write(readExactly(input, size))
            readLine(input)
        }
        return out.toByteArray()
    }

    

    @Test
    fun `port is minus one before start and start binds an ephemeral loopback port`() {
        val server =
            LocalUiServer(
                context = UnusableContext(),
                enginePort = { 1 },
                authorization = { null },
                upstream = OkHttpClient(),
                headInjection = { "" },
            )
        assertEquals(-1, server.port)
        val port = server.start()
        servers += server
        assertTrue("ephemeral port must be a real port: $port", port in 1..65535)
        assertEquals(port, server.port)
    }

    @Test
    fun `starting the same server twice is refused`() {
        val port = startServer()
        try {
            servers.last().start()
            fail("a second start must throw")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("already running"))
        }
    }

    

    @Test
    fun `host rpc without the token is 403`() {
        val port = startServer()
        assertEquals(403, request(port, "GET", "/__host/rpc").statusCode)
    }

    @Test
    fun `host rpc with the header token is accepted and sees the call`() {
        var seen: Triple<String, String, String>? = null
        val port =
            startServer(
                hostRpc = { ticket, method, args ->
                    seen = Triple(ticket, method, args)
                    """{"accepted":true}"""
                },
            )
        val response =
            request(
                port,
                "POST",
                "/__host/rpc",
                body = """{"ticket":"t-1","method":"pickDirectory","args":{"x":1}}""",
                headers = authedHeaders(port),
            )
        assertEquals(200, response.statusCode)
        assertEquals("""{"accepted":true}""", response.bodyText)
        assertEquals("t-1", seen?.first)
        assertEquals("pickDirectory", seen?.second)
        assertEquals("""{"x":1}""", seen?.third)
    }

    @Test
    fun `host rpc with a wrong token is 403`() {
        val port = startServer()
        val response =
            request(
                port,
                "GET",
                "/__host/rpc",
                headers = mapOf("X-Opencode-Host-Token" to "not-the-token"),
            )
        assertEquals(403, response.statusCode)
    }

    @Test
    fun `host rpc with the token in the query is rejected`() {
        
        
        val port = startServer()
        
        val response =
            request(
                port,
                "POST",
                "/__host/rpc?token=${token(port)}",
                body = """{"ticket":"t-2","method":"noop","args":{}}""",
            )
        assertEquals(403, response.statusCode)
    }

    @Test
    fun `a wrong header token is not rescued by a valid cookie`() {
        
        
        val port = startServer()
        val response =
            request(
                port,
                "GET",
                "/__host/rpc",
                headers = mapOf("X-Opencode-Host-Token" to "wrong") + cookieHeaders(port),
            )
        assertEquals(403, response.statusCode)
    }

    @Test
    fun `host rpc with malformed json is 400`() {
        val port = startServer()
        val response =
            request(
                port,
                "POST",
                "/__host/rpc",
                body = "not json",
                headers = authedHeaders(port),
            )
        assertEquals(400, response.statusCode)
    }

    @Test
    fun `host rpc without a host attached is 503`() {
        val port = startServer(hostRpc = null)
        val response =
            request(
                port,
                "GET",
                "/__host/rpc",
                headers = authedHeaders(port),
            )
        assertEquals(503, response.statusCode)
        
        assertEquals("HTTP/1.1 503 Service Unavailable", response.statusLine)
    }

    

    @Test
    fun `event stream without the token is 403`() {
        val port = startServer()
        assertEquals(403, request(port, "GET", "/__host/events").statusCode)
    }

    @Test
    fun `event stream with the boot cookie opens and starts with retry`() {
        val port = startServer()
        
        val (head, firstLine) = readEventStreamFirstLine(port)
        assertEquals(200, head.statusCode)
        assertTrue(head.header("content-type")!!.startsWith("text/event-stream"))
        assertEquals("retry: 1000", firstLine)
    }

    @Test
    fun `event stream with a query token is rejected`() {
        
        val port = startServer()
        assertEquals(403, request(port, "GET", "/__host/events?token=${token(port)}").statusCode)
    }

    
    
    
    
    
    
    
    

    @Test
    fun `host next without the token is 403`() {
        val port = startServer()
        assertEquals(403, request(port, "GET", "/__host/next").statusCode)
    }

    @Test
    fun `host next answers 204 when nothing is queued`() {
        val port = startServer(longPollMs = 250)
        val response = request(port, "GET", "/__host/next", headers = cookieHeaders(port))
        assertEquals(204, response.statusCode)
    }

    @Test
    fun `host next delivers a queued eval as json`() {
        val port = startServer(longPollMs = 10_000)
        
        
        val pusher =
            Thread {
                Thread.sleep(300)
                servers.last { it.port == port }.pushEval("document.documentElement.style.zoom=1.1")
            }
        pusher.isDaemon = true
        pusher.start()

        val response = request(port, "GET", "/__host/next", headers = cookieHeaders(port))
        assertEquals(200, response.statusCode)
        assertTrue("content type is json", response.header("content-type")!!.startsWith("application/json"))
        assertTrue("carries the kind", response.bodyText.contains("\"kind\":\"eval\""))
        assertTrue("carries the statement", response.bodyText.contains("document.documentElement.style.zoom=1.1"))
    }

    

    @Test
    fun `api proxy without the token is 403 and never reaches the engine`() {
        val upstream = startUpstream()
        upstream.enqueue(MockResponse().setBody("engine says hi"))
        val port = startServer(enginePort = { upstream.port })

        val response = request(port, "GET", "/api/info")

        assertEquals("unauthenticated loads must not be proxied", 403, response.statusCode)
        assertEquals("the engine must not see the request at all", 0, upstream.requestCount)
    }

    @Test
    fun `api proxy with the token forwards the request and injects the engine authorization`() {
        val upstream = startUpstream()
        upstream.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val port =
            startServer(
                enginePort = { upstream.port },
                authorization = { "Basic ZW5naW5l" },
            )

        val response =
            request(
                port,
                "GET",
                "/api/info?x=1",
                headers = authedHeaders(port),
            )

        assertEquals(200, response.statusCode)
        assertEquals("""{"ok":true}""", response.bodyText)
        val forwarded = upstream.takeRequest()
        assertEquals("/api/info?x=1", forwarded.path)
        assertEquals("Basic ZW5naW5l", forwarded.getHeader("Authorization"))
    }

    @Test
    fun `api proxy keeps an authorization the page set itself`() {
        val upstream = startUpstream()
        upstream.enqueue(MockResponse().setBody("ok"))
        val port =
            startServer(
                enginePort = { upstream.port },
                authorization = { "Basic ZW5naW5l" },
            )
        request(
            port,
            "GET",
            "/api/info",
            headers = authedHeaders(port) + mapOf("Authorization" to "Basic cGFnZQ=="),
        )
        assertEquals("Basic cGFnZQ==", upstream.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `proxy strips hop by hop headers and keeps ordinary ones`() {
        val upstream = startUpstream()
        upstream.enqueue(MockResponse().setBody("ok"))
        val port = startServer(enginePort = { upstream.port })
        val response =
            request(
                port,
                "GET",
                "/api/info",
                headers =
                    authedHeaders(port) +
                        mapOf(
                            "X-Trace-Me" to "yes",
                            "Keep-Alive" to "timeout=5",
                            "Host" to "spoofed.example",
                        ),
            )
        assertEquals(200, response.statusCode)
        val forwarded = upstream.takeRequest()
        assertEquals("yes", forwarded.getHeader("X-Trace-Me"))
        assertNull("Keep-Alive is hop-by-hop", forwarded.getHeader("Keep-Alive"))
        assertNotEquals("the page cannot spoof upstream Host", "spoofed.example", forwarded.getHeader("Host"))
        assertNotNull(forwarded.getHeader("Host"))
    }

    @Test
    fun `proxy forwards a request body`() {
        val upstream = startUpstream()
        upstream.enqueue(MockResponse().setBody("ok"))
        val port = startServer(enginePort = { upstream.port })
        val payload = """{"title":"hi"}"""
        val response =
            request(
                port,
                "POST",
                "/api/session",
                body = payload,
                headers = authedHeaders(port),
            )
        assertEquals(200, response.statusCode)
        assertEquals(payload, upstream.takeRequest().body.readUtf8())
    }

    @Test
    fun `proxy de-chunks a chunked request body`() {
        val upstream = startUpstream()
        upstream.enqueue(MockResponse().setBody("ok"))
        val port = startServer(enginePort = { upstream.port })
        val token = token(port)
        val raw =
            "POST /api/session HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "X-Opencode-Host-Token: $token\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "5\r\nhello\r\n" +
                "6\r\n world\r\n" +
                "0\r\n\r\n"
        open(port).use { socket ->
            socket.getOutputStream().apply {
                write(raw.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val response = readResponse(socket.getInputStream())
            assertEquals(200, response.statusCode)
        }
        assertEquals("hello world", upstream.takeRequest().body.readUtf8())
    }

    @Test
    fun `proxy answers 502 when the engine is unreachable`() {
        val port = startServer(enginePort = { 1 }) 
        val response =
            request(
                port,
                "GET",
                "/api/info",
                headers = authedHeaders(port),
            )
        assertEquals(502, response.statusCode)
        assertTrue(response.bodyText.contains("engine unreachable"))
    }

    

    @Test
    fun `the boot route rejects a wrong or missing token`() {
        val port = startServer()
        assertEquals(403, boot(port, presentedToken = "wrong").statusCode)
        assertEquals(403, request(port, "GET", "/__boot").statusCode)
    }

    @Test
    fun `the boot route exchanges the token for an HttpOnly cookie and redirects`() {
        val port = startServer()
        val response = boot(port)
        assertEquals(200, response.statusCode)
        val setCookie = response.header("set-cookie")
        assertNotNull("the boot response must set the host cookie", setCookie)
        assertTrue("wrong cookie value: $setCookie", setCookie!!.startsWith("oc_host=${token(port)}"))
        assertTrue("the cookie must be HttpOnly (page scripts must not read it): $setCookie", setCookie.contains("HttpOnly"))
        assertTrue("the cookie must be SameSite=Strict: $setCookie", setCookie.contains("SameSite=Strict"))
        assertTrue("the cookie must be scoped to the whole origin: $setCookie", setCookie.contains("Path=/"))
        
        
        assertEquals("the boot response must navigate on to the document", "0; url=/", response.header("refresh"))
    }

    @Test
    fun `the api proxy accepts the boot cookie`() {
        
        
        val port = startServer(enginePort = { 1 })
        val response = request(port, "GET", "/api/info", headers = cookieHeaders(port))
        assertEquals(502, response.statusCode)
    }

    @Test
    fun `the api proxy does not accept the token in the query anymore`() {
        
        val port = startServer(enginePort = { 1 })
        val response = request(port, "GET", "/api/info?token=${token(port)}")
        assertEquals(403, response.statusCode)
    }

    @Test
    fun `the proxy does not forward the host token or the cookie to the engine`() {
        
        val upstream = startUpstream()
        upstream.enqueue(MockResponse().setBody("ok"))
        val port = startServer(enginePort = { upstream.port })
        val response =
            request(
                port,
                "GET",
                "/api/info",
                headers = authedHeaders(port) + mapOf("Cookie" to "oc_host=${token(port)}; other=1"),
            )
        assertEquals(200, response.statusCode)
        val forwarded = upstream.takeRequest()
        assertNull("the host token must not reach the engine", forwarded.getHeader("X-Opencode-Host-Token"))
        assertNull("cookies must not reach the engine", forwarded.getHeader("Cookie"))
    }

    @Test
    fun `a 403 carries a Forbidden reason on the status line`() {
        
        val port = startServer()
        val response = request(port, "GET", "/api/info")
        assertEquals(403, response.statusCode)
        assertEquals("HTTP/1.1 403 Forbidden", response.statusLine)
    }

    

    @Test
    fun `bridge asset path traversal is refused with 400 before the auth gate`() {
        val upstream = startUpstream()
        val port = startServer(enginePort = { upstream.port })
        val response = request(port, "GET", "/assets/bridge/../proxy-marker")
        assertEquals(400, response.statusCode)
        assertEquals("a traversal must not fall through to the engine", 0, upstream.requestCount)
    }

    @Test
    fun `bridge asset backslash path is refused with 400`() {
        val port = startServer()
        assertEquals(400, request(port, "GET", "/assets/bridge/..\\proxy-marker").statusCode)
    }

    @Test
    fun `percent encoded traversal is not decoded and never reaches the proxy`() {
        val upstream = startUpstream()
        val port = startServer(enginePort = { upstream.port })
        
        
        val response = request(port, "GET", "/assets/bridge/%2e%2e/marker.js")
        assertEquals(404, response.statusCode)
        assertEquals(0, upstream.requestCount)
    }

    @Test
    fun `a missing bridge asset is 404 and not a proxy fallthrough`() {
        val upstream = startUpstream()
        val port = startServer(enginePort = { upstream.port })
        assertEquals(404, request(port, "GET", "/assets/bridge/missing.js").statusCode)
        assertEquals(0, upstream.requestCount)
    }

    @Test
    fun `a renderer path requires the token`() {
        val upstream = startUpstream()
        val port = startServer(enginePort = { upstream.port })
        
        val response = request(port, "GET", "/chunk-abc.js")
        assertEquals(403, response.statusCode)
        assertEquals(0, upstream.requestCount)
    }

    @Test
    fun `the document route is behind the auth gate`() {
        
        
        val port = startServer()
        assertEquals(403, request(port, "GET", "/").statusCode)
        assertEquals(500, request(port, "GET", "/", headers = cookieHeaders(port)).statusCode)
    }

    








    @Test
    fun `the document is not served without the boot cookie`() {
        val port = startServer()
        val response = request(port, "GET", "/")
        assertEquals("the document must not be served without the boot cookie", 403, response.statusCode)
        assertFalse("the response must not inline the host token", response.bodyText.contains("__opencodeHostToken"))
    }

    @Test
    fun `panel is 404 when no document is attached`() {
        val port = startServer(panelHtml = null)
        assertEquals(404, request(port, "GET", "/panel", headers = authedHeaders(port)).statusCode)
    }

    @Test
    fun `the panel document is not served without credentials (regression for the token leak)`() {
        
        
        
        val port = startServer()
        val anonymous = request(port, "GET", "/panel")
        assertEquals(403, anonymous.statusCode)
        assertFalse(
            "unauthenticated response must not carry the host token: ${anonymous.bodyText}",
            anonymous.bodyText.contains("__opencodeHostToken"),
        )

        val authenticated = request(port, "GET", "/panel", headers = cookieHeaders(port))
        assertEquals(200, authenticated.statusCode)
    }

    

    @Test
    fun `keep alive serves two requests on one connection`() {
        val port = startServer()
        val cookie = "Cookie: oc_host=${token(port)}\r\n"
        open(port).use { socket ->
            val output = socket.getOutputStream()
            val input = socket.getInputStream()
            output.write("GET /panel HTTP/1.1\r\nHost: 127.0.0.1\r\n$cookie\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
            val first = readResponse(input)
            assertEquals(200, first.statusCode)
            assertEquals("keep-alive", first.header("connection"))

            val secondRequest = "GET /panel HTTP/1.1\r\nHost: 127.0.0.1\r\n$cookie" + "Connection: close\r\n\r\n"
            output.write(secondRequest.toByteArray(Charsets.ISO_8859_1))
            output.flush()
            val second = readResponse(input)
            assertEquals(200, second.statusCode)
            assertEquals("close", second.header("connection"))
        }
    }

    @Test
    fun `connection close header is honoured`() {
        val port = startServer()
        val response = request(port, "GET", "/panel", headers = authedHeaders(port), close = true)
        assertEquals(200, response.statusCode)
        assertEquals("close", response.header("connection"))
    }

    @Test
    fun `proxy keeps the connection for a streamed response`() {
        
        
        
        
        
        
        
        val upstream = startUpstream()
        upstream.enqueue(MockResponse().setChunkedBody("data: {\"type\":\"server.connected\"}\n\n", 1))
        val port = startServer(enginePort = { upstream.port }, authorization = { "Basic engine" })

        val response = request(port, "GET", "/api/event", headers = cookieHeaders(port), close = false)

        assertEquals(200, response.statusCode)
        assertEquals("keep-alive", response.header("connection"))
    }

    @Test
    fun `http 1_0 closes by default`() {
        val port = startServer()
        val response =
            request(port, "GET", "/panel", headers = authedHeaders(port), httpVersion = "HTTP/1.0", close = false)
        assertEquals(200, response.statusCode)
        assertEquals("HTTP/1.0 must not be kept alive", "close", response.header("connection"))
    }

    

    @Test
    fun `webSocketAccept matches the rfc 6455 example`() {
        
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            LocalUiServer.webSocketAccept("dGhlIHNhbXBsZSBub25jZQ=="),
        )
    }

    @Test
    fun `webSocketAccept is not the raw key`() {
        val key = "dGhlIHNhbXBsZSBub25jZQ=="
        assertNotEquals(key, LocalUiServer.webSocketAccept(key))
        assertFalse(LocalUiServer.webSocketAccept(key).contains(key))
    }

    @Test
    fun `websocket upgrade without a key is 400`() {
        val port = startServer()
        open(port).use { socket ->
            val raw =
                "GET /api/pty/x HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    
                    
                    
                    "Cookie: oc_host=${token(port)}\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "\r\n"
            socket.getOutputStream().apply {
                write(raw.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val response = readResponse(socket.getInputStream())
            assertEquals(400, response.statusCode)
            assertTrue(response.bodyText.contains("not a websocket handshake"))
        }
    }

    @Test
    fun `a websocket upgrade is accepted with the token in the query`() {
        
        
        val engine = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
        val engineThread =
            Thread {
                runCatching {
                    engine.accept().use { upstream ->
                        accepted.set(true)
                        upstream.soTimeout = 5_000
                        
                        val reader = upstream.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        upstream.getOutputStream().apply {
                            val handshake =
                                "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                                    "Connection: Upgrade\r\n\r\n"
                            write(handshake.toByteArray(Charsets.ISO_8859_1))
                            flush()
                        }
                        Thread.sleep(300)
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

        val port = startServer(enginePort = { engine.localPort }, authorization = { "Basic ZW5naW5l" })
        val clientStatus: String?
        open(port).use { socket ->
            val raw =
                "GET /api/pty/x/connect?token=${token(port)} HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "\r\n"
            socket.getOutputStream().apply {
                write(raw.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            clientStatus = readLine(socket.getInputStream())
        }
        runCatching { engine.close() }
        engineThread.join(3_000)

        assertEquals("the upgrade must be proxied when the token is right", 101, statusOf(clientStatus))
        assertTrue("the engine should have been reached", accepted.get())
    }

    private fun statusOf(statusLine: String?): Int =
        statusLine?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: -1

    @Test
    fun `a websocket upgrade is refused without the host credential`() {
        
        
        
        
        val engine = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
        val engineThread =
            Thread {
                runCatching {
                    engine.accept().use { accepted.set(true) }
                }
            }.apply {
                isDaemon = true
                start()
            }

        val port = startServer(enginePort = { engine.localPort }, authorization = { "Basic ZW5naW5l" })
        val clientStatus: String?
        open(port).use { socket ->
            val raw =
                "GET /api/pty/x/connect HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "\r\n"
            socket.getOutputStream().apply {
                write(raw.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            clientStatus = readLine(socket.getInputStream())
        }
        runCatching { engine.close() }
        engineThread.join(2_000)

        assertEquals("no credentials, so the upgrade must not be proxied", "HTTP/1.1 403 Forbidden", clientStatus)
        assertFalse("the engine must not be reached at all: ${capturedUpstream()}", accepted.get())
    }

    
    
    
    

    @Test
    fun `renderer document gets a keyboard-aware viewport`() {
        val html =
            "<!doctype html><html><head>" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />" +
                "</head><body></body></html>"

        val adapted = keyboardAwareRendererDocument(html)

        assertTrue(
            "viewport must resize the layout viewport with the keyboard: $adapted",
            adapted.contains("content=\"width=device-width, initial-scale=1, interactive-widget=resizes-content\""),
        )
        assertFalse("the adaptation must be applied exactly once", adapted.contains("interactive-widget=resizes-content, interactive-widget"))
    }

    @Test
    fun `a document that already declares interactive-widget is left alone`() {
        val html =
            "<html><head><meta name=\"viewport\" " +
                "content=\"width=device-width, initial-scale=1, interactive-widget=overlays-content\">" +
                "</head></html>"

        assertEquals(html, keyboardAwareRendererDocument(html))
    }

    @Test
    fun `a document without a viewport meta is left alone`() {
        val html = "<html><head><title>x</title></head><body>no viewport here</body></html>"

        assertEquals(html, keyboardAwareRendererDocument(html))
    }

    private fun capturedUpstream(): String = "engine accept flag"

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_000
        const val READ_TIMEOUT_MS = 5_000
        const val MAX_LINE = 16 * 1024

        val TOKEN_SCRIPT = Regex("window\\.__opencodeHostToken=\"([^\"]+)\"")

        const val DEFAULT_PANEL = "<html><head></head><body>panel</body></html>"
    }
}
