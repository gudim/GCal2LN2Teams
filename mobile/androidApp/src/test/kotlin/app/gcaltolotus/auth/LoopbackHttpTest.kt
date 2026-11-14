package app.gcaltolotus.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackHttpTest {
    @Test
    fun parsesCallbackWithEncodedQuery() {
        val r = LoopbackHttp.parseRequestLine("GET /?code=0.AB%2Bc%3D&state=s1&session_state=x%20y HTTP/1.1")!!
        assertEquals("GET", r.method)
        assertEquals("/", r.path)
        assertEquals(mapOf("code" to "0.AB+c=", "state" to "s1", "session_state" to "x y"), r.query)
    }

    @Test
    fun parsesErrorReply() {
        val r = LoopbackHttp.parseRequestLine(
            "GET /?error=access_denied&error_description=The+user+declined&state=s HTTP/1.1",
        )!!
        assertEquals("access_denied", r.query["error"])
        assertEquals("The user declined", r.query["error_description"])
    }

    @Test
    fun pathWithoutQueryAndFirstValueWins() {
        assertEquals(emptyMap<String, String>(), LoopbackHttp.parseRequestLine("GET /favicon.ico HTTP/1.1")!!.query)
        assertEquals("/favicon.ico", LoopbackHttp.parseRequestLine("GET /favicon.ico HTTP/1.1")!!.path)
        assertEquals(mapOf("a" to "1", "b" to ""), LoopbackHttp.parseQuery("a=1&a=2&b"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(LoopbackHttp.parseRequestLine(""))
        assertNull(LoopbackHttp.parseRequestLine("GET /"))
        assertNull(LoopbackHttp.parseRequestLine("GET http://evil/ HTTP/1.1"))
        assertNull(LoopbackHttp.parseRequestLine("GET /?a=%zz HTTP/1.1"))
        assertNull(LoopbackHttp.parseRequestLine("\u0016\u0003\u0001 junk"))
    }

    @Test
    fun responseHasLengthAndClose() {
        val text = LoopbackHttp.response("404 Not Found", "<title>404</title>").decodeToString()
        assertTrue(text.startsWith("HTTP/1.1 404 Not Found\r\n"))
        assertTrue("Connection: close" in text)
        val body = text.substringAfter("\r\n\r\n")
        val length = Regex("Content-Length: (\\d+)").find(text)!!.groupValues[1].toInt()
        assertEquals(body.encodeToByteArray().size, length)
    }

    @Test
    fun redirectSendsBrowserBackToTheApp() {
        val text = LoopbackHttp.redirectToApp().decodeToString()
        assertTrue(text.startsWith("HTTP/1.1 302 Found\r\n"))
        assertTrue("\r\nLocation: app.gcaltolotus://signed-in\r\n" in text)
        assertTrue(text.endsWith("Connection: close\r\n\r\n"))
    }
}
