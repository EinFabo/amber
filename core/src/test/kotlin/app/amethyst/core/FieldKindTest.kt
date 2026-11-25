package app.amethyst.core

import app.amethyst.core.text.FieldKind
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldKindTest {

    @Test
    fun `addresses by their input variation`() {
        assertEquals(FieldKind.URI, FieldKind.of(0x11))
        assertEquals(FieldKind.EMAIL, FieldKind.of(0x21))
        assertEquals(FieldKind.EMAIL, FieldKind.of(0xd1), "a web form's e-mail field")
    }

    @Test
    fun `search by its variation or by the keyboard's search key`() {
        assertEquals(FieldKind.SEARCH, FieldKind.of(0xb1), "a filter field")
        assertEquals(FieldKind.SEARCH, FieldKind.of(0x1, imeOptions = 3))
        assertEquals(FieldKind.SEARCH, FieldKind.of(0x1, imeOptions = 0x40000000 or 3))
        assertEquals(FieldKind.SEARCH, FieldKind.of(0, imeOptions = 3), "a Compose field names no input type")
    }

    @Test
    fun `everything else is text`() {
        assertEquals(FieldKind.TEXT, FieldKind.of(0x1))
        assertEquals(FieldKind.TEXT, FieldKind.of(0x20001), "multi-line text")
        assertEquals(FieldKind.TEXT, FieldKind.of(0x31), "an e-mail's subject")
        assertEquals(FieldKind.TEXT, FieldKind.of(0x1, imeOptions = 4), "send")
        assertEquals(FieldKind.TEXT, FieldKind.of(0))
        assertEquals(FieldKind.TEXT, FieldKind.of(0x12), "a number class, not a URI")
    }
}
