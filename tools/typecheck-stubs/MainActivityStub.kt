// The real MainActivity and QuickStyleActivity are Compose-based and cannot be compiled here;
// the services only need their class references and the intents that start them, so bare
// Activities with the same companion signatures stand in.
package app.amethyst.ui

import android.content.Context
import android.content.Intent

class MainActivity : androidx.activity.ComponentActivity() {
    companion object {
        fun intent(context: Context, appPage: String? = null): Intent = Intent(context, MainActivity::class.java)
    }
}

class QuickStyleActivity : androidx.activity.ComponentActivity() {
    companion object {
        fun intent(context: Context, packageName: String): Intent = Intent(context, QuickStyleActivity::class.java)
    }
}
