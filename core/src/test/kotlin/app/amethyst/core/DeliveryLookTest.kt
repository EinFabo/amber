package app.amethyst.core

import app.amethyst.core.bubble.DeliveryLook
import kotlin.test.Test
import kotlin.test.assertEquals

class DeliveryLookTest {

    @Test
    fun `a delivery that only copied looks like the clipboard, not like done`() {
        assertEquals(DeliveryLook.ON_CLIPBOARD, DeliveryLook.of(insertedIntoField = false, copiedToClipboard = true))
    }

    @Test
    fun `a delivery into the field is done, copied as well or not`() {
        assertEquals(DeliveryLook.INSERTED, DeliveryLook.of(insertedIntoField = true, copiedToClipboard = false))
        assertEquals(DeliveryLook.INSERTED, DeliveryLook.of(insertedIntoField = true, copiedToClipboard = true))
    }
}
