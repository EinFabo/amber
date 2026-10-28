# Amber

Sprach-Diktat für Android, das in **jede** App schreibt — lokal-first, mit austauschbaren
Spracherkennungs-Engines und einem sichtbaren Vertrauens-Level pro Diktat.

Im Geist von Wispr Flow, aber mit einer anderen Grundhaltung: nicht eine Blackbox in der
Cloud, sondern du entscheidest pro Diktat, wohin deine Stimme geht — und die App sagt es dir.

## Wie es funktioniert

Eine **Blase** erscheint, sobald du in ein Textfeld tippst, und verschwindet danach wieder.
Antippen, sprechen, fertig: der Text wird an der Cursorstelle direkt ins Feld getippt. Kein
Tastaturwechsel, keine Zwischenablage.

Die Blase ist ein `TYPE_ACCESSIBILITY_OVERLAY` des Bedienungshilfe-Dienstes — deshalb
braucht Amber **kein** „Über anderen Apps anzeigen" und **kein** `SYSTEM_ALERT_WINDOW`.

Bedienung:

- **Tippen** startet ein Diktat, nochmal tippen beendet und schickt es ab.
- **Halten** diktiert, solange der Finger liegt; beim Loslassen wird abgeschickt.
- **Ziehen** verschiebt die Blase jederzeit, auch mitten im Diktat. Sie bleibt, wo du sie
  loslässt, und weicht einer offenen Tastatur nach oben aus.

Aussehen: eine flache Amber-Scheibe mit einem dunklen Zeichen — Mikrofon, drei Pegelbalken
beim Zuhören, ein kleiner Bogen beim Schreiben, ein Haken beim Einfügen.

## Engines

| Engine | Vertrauen | Status |
|---|---|---|
| Android (auf dem Gerät) | `ON_DEVICE` | ✅ Stufe 1 |
| Jeder OpenAI-kompatible Endpunkt — Tinfoil-Enclave, eigener Server (Speaches, whisper.cpp), Groq, Fireworks, OpenAI | `CLOUD` | ✅ Stufe 2 |
| Whisper lokal über whisper.cpp mit FUTO-Modell (Multilingual-74, 82 MB) | `ON_DEVICE` | ✅ 0.2 |
| Lokale Modelle: Parakeet TDT, Moonshine (sherpa-onnx) | `ON_DEVICE` | ⏳ später |
| ElevenLabs Scribe v2 Realtime (Streaming) | `CLOUD` | ⏳ Stufe 4 |
| Tinfoil mit geprüfter Enclave-Attestierung | `ENCLAVE_VERIFIED` | ⏳ Stufe 4 |

**Whisper und Sprachen.** Whisper bekommt eine Liste von Sprachen (Standard: Deutsch und
Englisch) und erkennt pro Diktat, welche davon gesprochen wurde. Eine einzelne fest
eingestellte Sprache zwingt Whisper dazu, alles andere in diese Sprache zu *übersetzen* —
englisch Gesprochenes kam dann deutsch heraus. Mehrere Sprachen innerhalb eines einzigen
Diktats kann Whisper nicht; es wählt die vorherrschende.

Ein Treiber bedient alle OpenAI-kompatiblen Dienste: Base-URL, Modellname und Schlüssel sind
Einstellungen, kein Code. Einen Anbieter hinzuzufügen heißt, drei Felder auszufüllen.

**Amber behauptet nie mehr Vertrauen, als es belegen kann.** Ein Tinfoil-Endpunkt wird als
`CLOUD` angezeigt, bis die Attestierung tatsächlich geprüft wurde — erst dann erscheint
`ENCLAVE_VERIFIED`. Ein stiller Fallback auf eine andere Engine findet nie statt: wenn die
gewählte Engine nicht kann, gibt es eine Fehlermeldung, keine Umleitung deiner Stimme.

## Aufbau

```
core/   reines Kotlin/JVM — Engine-Schicht, Text-Splicing, Einfüge-Kette, VAD,
        Mikrofon-Routing, Settings-Schema, Diagnose-Log   → 111 Tests
app/    Android — Blase, Bedienungshilfe-Adapter, Aufnahme, Compose-UI
```

Die Trennung ist nicht Kosmetik, sondern die Grundregel des Projekts: **alles, was schiefgehen
kann, gehört nach `core`.** Dort läuft es ohne Android SDK und ohne Gerät, also ist es
testbar. In `app` bleibt nur, was zwingend eine Plattform-API braucht — das Finden des
fokussierten Knotens, `AudioRecord`, das Overlay-Fenster.

Zwei echte Fehler sind dadurch vor dem ersten Build aufgefallen: der Cursor wurde gesetzt,
bevor geprüft war, ob das Schreiben überhaupt angekommen ist, und ein leeres Textfeld
(`text == null`) galt als unlesbar — Diktieren in ein leeres Feld hätte nie funktioniert.

### Einfügen in fremde Apps

`ACTION_SET_TEXT` liefert das beste Ergebnis, wird aber von WebViews, Spielen und einigen
Cross-Platform-Toolkits ignoriert — teilweise, während es Erfolg meldet. Darum:

1. **COMMIT_TEXT** — ab Android 13 tippt der Dienst über seine eigene Eingabeverbindung,
   wie eine Tastatur. An der Cursorstelle, mit Undo, auch in WebViews.
2. **SET_TEXT** — Feld lesen, an der Cursorposition spleißen, zurückschreiben, **nachlesen**.
3. **PASTE** — Zwischenablage + `ACTION_PASTE`, ebenfalls mit Kontrolle.
4. **CLIPBOARD_ONLY** — kopieren und Bescheid geben.

Schritte 3 und 4 laufen nur mit der Einstellung „Zwischenablage als Notlösung“ (Standard:
aus). Sonst bleibt die Zwischenablage unberührt, und ein Diktat, das kein Feld annimmt, steht
mit Wortlaut in der Diagnose.

Was in welcher App funktioniert hat, wird gemerkt. Du siehst einen Stolperer pro App, nie
einen wiederkehrenden.

### Whisper über die Blase so schnell wie in der App

Android behandelt eine Tastatur-App beim Rechnen wie die App im Vordergrund
(`BIND_SCHEDULE_LIKE_TOP_APP`), einen Bedienungshilfe-Dienst aber nicht. Über die Blase lief
Whisper deshalb auf kleinen Kernen mit niedrigem Takt, und die Erkennung dauerte ein
Vielfaches. Drei Gegenmaßnahmen:

1. **Feste Rechen-Threads.** whisper.cpp legt sonst für jeden Rechenschritt neue Threads an,
   mehrere hundert pro Diktat. Ein neuer Thread hat keine Lastgeschichte und landet auf einem
   kleinen Kern. `app/src/main/cpp/patch-whisper.cmake` gibt whisper.cpp einen dauerhaften
   Thread-Pool, der zwischen Diktaten schläft.
2. **Hohe Thread-Priorität** für den Whisper-Thread; der Pool erbt sie.
3. **Performance-Hint-Session (ADPF, Android 12+)** über alle Rechen-Threads, solange eine
   Erkennung läuft: Android hebt den Takt und bevorzugt große Kerne.

Die Diagnose zeigt pro Diktat CPU-Gruppe, Priorität, Pool-Größe und ob der Boost lief.

### Mikrofon im Hintergrund

Android 14/15 verbieten, aus dem Hintergrund einen Mikrofon-Foreground-Service zu starten —
die Blase wird aber getippt, während eine andere App vorne ist. Amber geht zwei Wege:

1. `MicForegroundService` (Typ `microphone`) wird **im Vordergrund** gestartet, beim
   Onboarding, und bleibt am Leben. Ein späterer Tipp ist damit kein Hintergrund-Start.
2. Weigert ein Hersteller-ROM sich trotzdem, übernimmt `MicProxyActivity` — ein
   1×1-Pixel-transparentes Fenster, das per Definition im Vordergrund ist.

Weg 2 greift automatisch, wenn Weg 1 scheitert.

## Bauen

```bash
./gradlew :core:test          # läuft überall, ohne Android SDK
python3 -I tools/check_refs.py  # Imports, R.*-IDs, Manifest-Klassen
./gradlew :app:assembleDebug  # braucht ein Android SDK

tools/setup-android-sdk.sh    # SDK holen und bauen, falls keins da ist
```

`settings.gradle.kts` bindet `:app` nur ein, wenn ein SDK gefunden wird (`ANDROID_HOME`,
`ANDROID_SDK_ROOT` oder `sdk.dir` in `local.properties`), oder mit `-Pamethyst.withApp=true`.
Ohne SDK bleibt `:core` voll baubar statt den ganzen Build zu sprengen.

Jeder Push baut in GitHub Actions eine Debug-APK und legt sie als Artifact `amethyst-debug-apk`
ab.

## Auf dem Gerät prüfen

```bash
adb install -r app-debug.apk
adb logcat -s Amber:V     # Zustände, gewählte Einfüge-Strategie, Latenzen
```

Die Reihenfolge ist absichtlich so — sie grenzt Fehler ein, statt sie zu vermischen:

1. App öffnen, Mikrofon erlauben.
2. **Testdiktat** im Testfeld der App. Hier ist Amber im Vordergrund, wo Mikrofonzugriff
   nie verweigert wird, und das Ziel ist ein gewöhnliches Eingabefeld. Klappt das, stehen
   Mikrofon, Engine, Normalisierung und Einfügen.
3. Bedienungshilfe aktivieren → Blase erscheint, lässt sich ziehen und rastet am Rand ein.
4. Blase in WhatsApp, Chrome und Gmail. Die **Diagnose-Karte** zeigt pro Diktat Engine,
   Latenz, benutzte Einfüge-Methode und Ziel-App.
5. Eigene Engine eintragen → **„Verbindung testen"** zeigt Latenz, ohne dass du sprechen musst.
6. Gerät neu starten, ohne die App zu öffnen, Blase antippen → die Proxy-Activity muss
   einspringen. Hier treiben Hersteller-ROMs am ehesten quer.

Wenn Schritt 2 geht und Schritt 4 nicht, liegt es am Einfügen, nicht an der Erkennung.
Geht Schritt 2 schon nicht, ist es Mikrofon oder Engine — und die Diagnose-Karte sagt, welches.

## Wenn etwas im Weg ist

Die Blase lässt sich in den Einstellungen abschalten, ohne die Bedienungshilfe anzufassen —
das Testfeld funktioniert dann weiter. Ein Diktat hat außerdem eine harte Obergrenze, damit
ein hängender Erkenner oder ein stiller Server die Blase nicht endlos drehen lässt.

## Noch nicht drin

LLM-Cleanup · Command Mode auf markiertem Text · Wörterbuch und Snippets · verschlüsselter
Verlauf · App-Profile. Bewusst später: erst muss die Basis auf echter Hardware stehen.
