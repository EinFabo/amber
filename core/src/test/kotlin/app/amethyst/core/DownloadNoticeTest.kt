package app.amethyst.core

import app.amethyst.core.engine.DownloadNotice
import app.amethyst.core.engine.ModelDownload
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadNoticeTest {

    @Test
    fun `the percentage is floored and never 100 while running`() {
        assertEquals(45, DownloadNotice.percent(ModelDownload.Running(45, 100)))
        assertEquals(99, DownloadNotice.percent(ModelDownload.Running(999, 1000)))
        assertEquals(99, DownloadNotice.percent(ModelDownload.Running(1000, 1000)))
        assertEquals(0, DownloadNotice.percent(ModelDownload.Running(0, 0)))
    }
}
