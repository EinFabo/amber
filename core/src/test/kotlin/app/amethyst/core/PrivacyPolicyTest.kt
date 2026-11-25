package app.amethyst.core

import app.amethyst.core.privacy.PolicyBlock
import app.amethyst.core.privacy.PrivacyPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrivacyPolicyTest {

    @Test
    fun `title, headings, paragraphs and points come apart`() {
        val text = """
            # Privacy policy

            sayEmber is a dictation app.
            It types into the app you are in.

            ## What stays on the phone

            - Settings, including the addresses
              of the servers you add.
            - API keys.
            The last line.
        """.trimIndent()
        assertEquals(
            listOf(
                PolicyBlock.Title("Privacy policy"),
                PolicyBlock.Paragraph("sayEmber is a dictation app. It types into the app you are in."),
                PolicyBlock.Heading("What stays on the phone"),
                PolicyBlock.Point("Settings, including the addresses of the servers you add."),
                PolicyBlock.Point("API keys."),
                PolicyBlock.Paragraph("The last line."),
            ),
            PrivacyPolicy.parse(text),
        )
    }

    @Test
    fun `a line in brackets is something still missing`() {
        val text = "Responsible for sayEmber:\n\n[Controller and contact e-mail: to be added before publishing]\n"
        assertEquals(
            listOf(
                PolicyBlock.Paragraph("Responsible for sayEmber:"),
                PolicyBlock.Missing("[Controller and contact e-mail: to be added before publishing]"),
            ),
            PrivacyPolicy.parse(text),
        )
    }

    @Test
    fun `windows line ends and extra blank lines change nothing`() {
        assertEquals(
            listOf(PolicyBlock.Heading("Children"), PolicyBlock.Paragraph("Not directed at children.")),
            PrivacyPolicy.parse("\r\n\r\n## Children\r\n\r\n\r\nNot directed at children.\r\n\r\n"),
        )
    }

    @Test
    fun `the web address is offered only once it is set`() {
        assertNull(PrivacyPolicy.webAddress(""))
        assertNull(PrivacyPolicy.webAddress("  "))
        // Only a page on the web: no plain http, no other scheme.
        assertNull(PrivacyPolicy.webAddress("http://example.org/privacy"))
        assertEquals("https://example.org/privacy", PrivacyPolicy.webAddress(" https://example.org/privacy "))
    }

    @Test
    fun `the app's policy texts read as a title, sections and one line still to fill in`() {
        // The raw resources of :app, in both languages: Markdown they do not use (bold, a table)
        // would show as stray marks on the phone.
        for (folder in listOf("raw", "raw-de")) {
            val file = java.io.File("../app/src/main/res/$folder/privacy_policy.txt")
            val blocks = PrivacyPolicy.parse(file.readText())
            assertEquals(PolicyBlock.Title::class, blocks.first()::class, folder)
            assertEquals(7, blocks.count { it is PolicyBlock.Heading }, folder)
            assertEquals(1, blocks.count { it is PolicyBlock.Missing }, folder)
            val texts = blocks.map { block ->
                when (block) {
                    is PolicyBlock.Title -> block.text
                    is PolicyBlock.Heading -> block.text
                    is PolicyBlock.Paragraph -> block.text
                    is PolicyBlock.Point -> block.text
                    is PolicyBlock.Missing -> block.text
                }
            }
            for (text in texts) {
                kotlin.test.assertFalse("**" in text || text.startsWith("|") || text.startsWith("#"), "$folder: $text")
            }
        }
    }
}

