package app.amethyst.core

import app.amethyst.core.engine.DownloadNotice
import app.amethyst.core.engine.ModelDownload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadNoticeTest {

    @Test
    fun `the percentage is floored and never 100 while running`() {
        assertEquals(45, DownloadNotice.percent(ModelDownload.Running(45, 100)))
        assertEquals(99, DownloadNotice.percent(ModelDownload.Running(999, 1000)))
        assertEquals(99, DownloadNotice.percent(ModelDownload.Running(1000, 1000)))
        assertEquals(0, DownloadNotice.percent(ModelDownload.Running(0, 0)))
    }

    @Test
    fun `a running download has a share, any other state none`() {
        assertEquals(0.25f, DownloadNotice.share(ModelDownload.Running(250, 1000)))
        assertEquals(0f, DownloadNotice.share(ModelDownload.Running(250, 0)))
        assertEquals(1f, DownloadNotice.share(ModelDownload.Running(1200, 1000)))
        assertNull(DownloadNotice.share(ModelDownload.WaitingForWifi))
        assertNull(DownloadNotice.share(null))
    }
}
