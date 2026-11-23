package app.amethyst.core

import app.amethyst.core.engine.HttpReason
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpReasonTest {

    @Test
    fun `a refused key, no credit and a missing address are told apart`() {
        assertEquals(HttpReason.KEY_REFUSED, HttpReason.of(401))
        assertEquals(HttpReason.KEY_REFUSED, HttpReason.of(403))
        assertEquals(HttpReason.NO_CREDIT, HttpReason.of(402))
        assertEquals(HttpReason.NOT_FOUND, HttpReason.of(404))
        assertEquals(HttpReason.NOT_FOUND, HttpReason.of(410))
    }

    @Test
    fun `too large, too many and too slow`() {
        assertEquals(HttpReason.TOO_LARGE, HttpReason.of(413))
        assertEquals(HttpReason.TOO_MANY, HttpReason.of(429))
        assertEquals(HttpReason.TOO_SLOW, HttpReason.of(408))
        assertEquals(HttpReason.TOO_SLOW, HttpReason.of(504))
        assertEquals(HttpReason.TOO_SLOW, HttpReason.of(524))
    }

    @Test
    fun `any other server error is a server that is down`() {
        listOf(500, 502, 503, 529, 599).forEach { assertEquals(HttpReason.SERVER_DOWN, HttpReason.of(it), "$it") }
    }

    @Test
    fun `the rest is a refused request`() {
        listOf(400, 405, 409, 415, 422, 301, 308, 0, 600).forEach { assertEquals(HttpReason.REFUSED, HttpReason.of(it), "$it") }
    }

    @Test
    fun `a download needs no key, costs nothing and asks for no size, so those refusals are just refusals`() {
        listOf(401, 402, 403, 413).forEach { assertEquals(HttpReason.REFUSED, HttpReason.forDownload(it), "$it") }
    }

    @Test
    fun `a download keeps every other reason`() {
        assertEquals(HttpReason.NOT_FOUND, HttpReason.forDownload(404))
        assertEquals(HttpReason.NOT_FOUND, HttpReason.forDownload(410))
        assertEquals(HttpReason.TOO_MANY, HttpReason.forDownload(429))
        assertEquals(HttpReason.TOO_SLOW, HttpReason.forDownload(504))
        listOf(500, 502, 503).forEach { assertEquals(HttpReason.SERVER_DOWN, HttpReason.forDownload(it), "$it") }
        listOf(206, 400, 416).forEach { assertEquals(HttpReason.REFUSED, HttpReason.forDownload(it), "$it") }
    }
}
