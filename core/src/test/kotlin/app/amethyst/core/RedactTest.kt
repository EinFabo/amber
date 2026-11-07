package app.amethyst.core

import app.amethyst.core.engine.AmberCatalog
import app.amethyst.core.net.Redact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RedactTest {

    @Test
    fun `an address keeps scheme, host and port, never its path, query or user`() {
        assertEquals("http://192.168.1.20:8765/…", Redact.url("http://192.168.1.20:8765/k3y-s3cret/v1"))
        assertEquals("https://api.groq.com/…", Redact.url("HTTPS://api.groq.com/openai/v1?key=abc"))
        assertEquals("https://host.example/…", Redact.url("https://user:pass@host.example/v1"))
        assertEquals("https://host.example", Redact.url("https://host.example"))
        assertEquals("<address>", Redact.url("not an address"))
        assertEquals("", Redact.url(null))
    }

    @Test
    fun `addresses inside a message are redacted, the rest stays`() {
        val message = "java.io.IOException: unexpected end of stream on http://192.168.1.20:8765/k3y/v1/audio/transcriptions..."
        val redacted = Redact.text(message)
        assertFalse("k3y" in redacted, redacted)
        assertTrue(redacted.startsWith("java.io.IOException: unexpected end of stream on http://192.168.1.20:8765/…"), redacted)
    }

    @Test
    fun `a model list keeps only plain model ids and one-line labels`() {
        val body = """{"amber":{"service":"amber-pc","version":"1"},"data":[
            {"id":"../../etc/passwd","amber":{"kind":"speech"}},
            {"id":"/abs","amber":{"kind":"speech"}},
            {"id":"bad id","amber":{"kind":"speech"}},
            {"id":"qwen/qwen3-4b:q4","amber":{"kind":"cleanup","label":"Qwen\n3‮","note":"${"x".repeat(200)}"}},
            {"id":"large-v3","amber":{"kind":"speech","default":true}}]}"""
        val catalog = assertNotNull(AmberCatalog.parse(body))
        assertEquals(listOf("large-v3"), catalog.speech.map { it.id })
        val cleanup = catalog.cleanup.single()
        assertEquals("qwen/qwen3-4b:q4", cleanup.id)
        assertEquals("Qwen3", cleanup.label)
        assertEquals(80, cleanup.note.length)
    }
}
