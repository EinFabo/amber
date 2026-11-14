package app.amethyst.core

import app.amethyst.core.bubble.SecretField
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretFieldTest {

    private val text = 0x1
    private val textPassword = 0x81
    private val number = 0x2
    private val none = 0

    // --- when the windows are searched ---------------------------------------------------

    @Test
    fun `a node Android names is asked, nothing is searched`() {
        assertFalse(SecretField.searchNeeded(text, focusFound = true))
        assertFalse(SecretField.searchNeeded(text, focusFound = true, forBubble = true))
    }

    @Test
    fun `a text field Android names no node for is searched for`() {
        // Compose up to 1.7: a password field with the default keyboard type looks like this.
        assertTrue(SecretField.searchNeeded(text, focusFound = false))
        assertTrue(SecretField.searchNeeded(text, focusFound = false, forBubble = true))
    }

    @Test
    fun `an input type that says secret needs no second opinion`() {
        assertFalse(SecretField.searchNeeded(textPassword, focusFound = false))
        assertFalse(SecretField.searchNeeded(textPassword, focusFound = false, forBubble = true))
    }

    @Test
    fun `without a started input nothing is searched`() {
        assertFalse(SecretField.searchNeeded(null, focusFound = false))
    }

    @Test
    fun `for the bubble only a field it would show for is searched for`() {
        assertFalse(SecretField.searchNeeded(number, focusFound = false, forBubble = true))
        assertFalse(SecretField.searchNeeded(none, focusFound = false, forBubble = true))
    }

    @Test
    fun `before text goes in, a number field is searched for too`() {
        // With the bubble shown everywhere a dictation may end in a PIN field that asks for
        // the number keyboard and hides what is typed.
        assertTrue(SecretField.searchNeeded(number, focusFound = false))
        assertTrue(SecretField.searchNeeded(none, focusFound = false))
    }

    // --- what counts as secret -----------------------------------------------------------

    @Test
    fun `a node's password flag makes a plain text field secret`() {
        assertTrue(SecretField.isSecret(text, SecretField.Node(inputType = text, isPassword = true)))
        assertTrue(SecretField.isSecret(null, SecretField.Node(inputType = none, isPassword = true)))
    }

    @Test
    fun `a node's input type counts as the keyboard's does`() {
        assertTrue(SecretField.isSecret(text, SecretField.Node(inputType = textPassword, isPassword = false)))
        assertTrue(SecretField.isSecret(textPassword, SecretField.Node(inputType = text, isPassword = false)))
        assertTrue(SecretField.isSecret(textPassword, null))
    }

    @Test
    fun `a plain field is not secret, and what is not known says nothing`() {
        assertFalse(SecretField.isSecret(text, SecretField.Node(inputType = text, isPassword = false)))
        assertFalse(SecretField.isSecret(text, null))
        assertFalse(SecretField.isSecret(null, null))
    }
}
