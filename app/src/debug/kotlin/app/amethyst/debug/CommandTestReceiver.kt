package app.amethyst.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.amethyst.AmethystApp
import kotlinx.coroutines.launch

/**
 * Runs a typed voice command through Amber's real path - the rules, the switch, the executor -
 * so commands can be tested on a device without speaking:
 *
 *     adb shell am broadcast -a app.amber.debug.COMMAND -n app.amber.debug/app.amethyst.debug.CommandTestReceiver \
 *         --es text 'offne whatsapp'
 *
 * Accents may be left out: the text is folded before it is matched. Debug build only (src/debug).
 */
class CommandTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        val graph = AmethystApp.graph(context)
        val pending = goAsync()
        graph.appScope.launch {
            try {
                Log.i("Amber", "debug command test: ${text.length} chars")
                graph.dictation.commandForTest(text)
            } finally {
                pending.finish()
            }
        }
    }
}
