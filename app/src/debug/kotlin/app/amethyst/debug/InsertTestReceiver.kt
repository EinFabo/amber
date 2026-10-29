package app.amethyst.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.amethyst.AmethystApp
import kotlinx.coroutines.launch

/**
 * Sends a text through Amber's real insertion into whatever field has focus - so insertion can
 * be tested on a device without speaking:
 *
 *     adb shell am broadcast -a app.amber.debug.INSERT -n app.amber.debug/app.amethyst.debug.InsertTestReceiver \
 *         --es text 'Hallo Anna,\n\nbis morgen' --ez formatted true
 *
 * "\n" in the text becomes a line break; `formatted` marks it as a cleanup model's output.
 * Debug build only (src/debug).
 */
class InsertTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text")?.replace("\\n", "\n") ?: return
        val formatted = intent.getBooleanExtra("formatted", false)
        val graph = AmethystApp.graph(context)
        val pending = goAsync()
        graph.appScope.launch {
            try {
                Log.i("Amber", "debug insert test: ${text.length} chars, formatted=$formatted")
                graph.dictation.deliverForTest(text, formatted)
            } finally {
                pending.finish()
            }
        }
    }
}
