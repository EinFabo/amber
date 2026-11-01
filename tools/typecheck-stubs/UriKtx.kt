// Stand-in for androidx.core.net.toUri (core-ktx), which the update notifier uses; the real
// one is the same one-liner.
package androidx.core.net

import android.net.Uri

fun String.toUri(): Uri = Uri.parse(this)
