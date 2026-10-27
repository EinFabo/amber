package androidx.lifecycle

import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope

val ComponentActivity.lifecycleScope: CoroutineScope
    get() = throw UnsupportedOperationException()
