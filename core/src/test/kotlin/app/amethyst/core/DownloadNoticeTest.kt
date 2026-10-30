package app.amethyst.core

import app.amethyst.core.engine.DownloadNotice
import app.amethyst.core.engine.ModelDownload
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadNoticeTest {

    @Test
    fun `the percentage is floored and never 100 while running`() {
        assertEquals("Model downloading - 45 %", DownloadNotice.short(ModelDownload.Running(45, 100)))
        assertEquals("Model downloading - 99 %", DownloadNotice.short(ModelDownload.Running(999, 1000)))
        assertEquals("Model downloading - 0 %", DownloadNotice.short(ModelDownload.Running(0, 0)))
    }

    @Test
    fun `waiting for wifi says so`() {
        assertEquals("Waiting for Wi-Fi", DownloadNotice.short(ModelDownload.WaitingForWifi))
        assertEquals("Waiting for Wi-Fi. Amber is ready when it is done.", DownloadNotice.atStart(ModelDownload.WaitingForWifi))
    }

    @Test
    fun `a dictation started during the download is told to wait`() {
        assertEquals(
            "Model downloading - 45 %. Amber is ready when it is done.",
            DownloadNotice.atStart(ModelDownload.Running(45, 100)),
        )
    }
}
