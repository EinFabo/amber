// Stands in for the R class that AAPT2 generates from app/src/main/res.
// The ids listed here are exactly the ones the non-Compose sources reference; the reference
// checker already proves each one exists as a real resource.
package app.nocturne

object R {
    object string {
        const val app_name = 0
        const val clipboard_fallback = 0
        const val notification_channel_description = 0
        const val notification_channel_name = 0
        const val notification_ready_text = 0
        const val notification_ready_title = 0
    }

    object drawable {
        const val ic_moon = 0
    }
}
