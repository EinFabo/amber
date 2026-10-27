package app.nocturne.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nocturne.core.dictation.DictationRecord
import app.nocturne.dictation.DictationState
import app.nocturne.ui.Hint
import app.nocturne.ui.SectionCard
import app.nocturne.ui.TrustBadge
import app.nocturne.ui.theme.NocturnePalette

/**
 * The first thing to try after installing: dictate into a field inside Nocturne itself.
 *
 * This exists so a failure is attributable. The bubble path involves the microphone, the
 * engine, the normalizer, the accessibility service, the insertion chain and a foreign app's
 * text field - six things that can break. Here the app is in the foreground, where microphone
 * access is never refused, and the target is a plain Compose field. If this works and
 * dictating into WhatsApp does not, the problem is the insertion path, not the engine.
 */
@Composable
fun TestDictationCard(
    dictation: DictationState,
    canDictate: Boolean,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var testText by remember { mutableStateOf("") }
    val running = dictation !is DictationState.Idle && dictation !is DictationState.Failed

    SectionCard(
        title = "Testdiktat",
        subtitle = "Diktiere hier hinein, bevor du es in anderen Apps versuchst.",
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = testText,
            onValueChange = { testText = it },
            label = { Text("Zielfeld") },
            placeholder = { Text("Hier erscheint der erkannte Text") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = if (running) onStopTest else onStartTest,
                enabled = canDictate,
            ) {
                Text(if (running) "Stoppen" else "Testdiktat starten")
            }
            if (testText.isNotEmpty()) {
                TextButton(onClick = { testText = "" }) {
                    Text("Feld leeren", color = NocturnePalette.Primary)
                }
            }
        }

        if (!canDictate) {
            Hint(
                "Erst Mikrofon erlauben und die Bedienungshilfe aktivieren – " +
                    "ohne sie kann der Text nicht ins Feld geschrieben werden.",
                NocturnePalette.Error,
            )
        } else {
            Hint(
                "Antippen, sprechen, kurz warten. Das Feld muss dafür den Fokus haben – " +
                    "tippe es an, bevor du startest.",
            )
        }
    }
}

/**
 * What actually happened on the last dictations, so a bug report can be precise.
 */
@Composable
fun DiagnosticsCard(
    records: List<DictationRecord>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Diagnose",
        subtitle = "Die letzten Diktate mit Engine, Dauer und benutzter Einfüge-Methode.",
        modifier = modifier,
    ) {
        if (records.isEmpty()) {
            Hint("Noch nichts diktiert.")
            return@SectionCard
        }

        records.take(MAX_SHOWN).forEach { record ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    record.trustLevel?.let { TrustBadge(it) }
                    Hint(
                        text = record.summary(),
                        color = if (record.failed) NocturnePalette.Error else NocturnePalette.Muted,
                    )
                }
                if (record.text.isNotBlank()) {
                    Text(
                        text = record.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = NocturnePalette.Text,
                    )
                }
            }
        }

        TextButton(onClick = onClear) {
            Text("Diagnose leeren", color = NocturnePalette.Primary)
        }
    }
}

private const val MAX_SHOWN = 5
