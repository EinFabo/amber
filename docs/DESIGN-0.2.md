# Amethyst 0.2 – lokales Whisper, direktes Tippen, Glas-Design

Stand 2026-10-27. Entstanden aus dem ersten Gerätetest von 0.1: Die Bedienungshilfe war
gesperrt (eingeschränkte Einstellungen), das deutsche Offline-Sprachpaket fehlte, und Text
landete in der Zwischenablage.

## 1. Engine „Whisper lokal (FUTO)“

**Ziel:** Diktat komplett offline, ohne Google-Sprachpaket.

- **Modell:** FUTO `voice-input-multilingual-74.bin` – Whisper base, ACFT-nachtrainiert,
  q8_0, 81 768 602 Bytes, SHA-256 `e44f352c…efc4`. FUTO empfiehlt es für Deutsch.
  Wird nicht mit der APK ausgeliefert (Größe, ungeklärte Weiterverteilung), sondern in der
  App geladen oder aus einer Datei übernommen.
- **Laufzeit:** whisper.cpp v1.9.5, per CMake `FetchContent` mit gepinntem Hash geholt,
  statisch gelinkt in `libamethyst_whisper.so`. Nur `arm64-v8a`, `-march=armv8.2-a+fp16+dotprod`,
  ohne OpenMP. Ninja ist auf 4 Compiler-Jobs begrenzt (8 GB RAM).
- **Aufteilung nach der Projektregel:**
  - `:core` – `WhisperInput` (16 kHz Float, Mindestlänge 1,2 s, Audio-Kontext nach Länge),
    `WhisperOutput` (Klangmarken und Untertitel-Halluzinationen entfernen),
    `WhisperModelFile` (ggml-Kopf prüfen), `ModelInstall` (streamen, hashen, atomar
    umbenennen), `WhisperCppEngine` gegen das Interface `WhisperRuntime`. Getestet.
  - `:app` – `whisper_jni.cpp` (dünn: Arrays rüber, Rückgabecodes zu Exceptions),
    `NativeWhisperRuntime` (hält den Kontext zwischen Diktaten, lädt bei Dateiwechsel neu),
    `WhisperModelStore` (Download über OkHttp, Import über SAF, Zustand als Flow).
- **Audio-Kontext:** `ceil(samples / 320) + 64`, mindestens 256, ab 30 s volle 1500. Die
  ACFT-Modelle sind dafür trainiert; das ist der Hauptgrund für ihre Geschwindigkeit.
- **Abbruch:** whisper.cpp prüft ein `abort_callback`; ein Geschwister-Coroutine setzt das
  Flag, wenn das Diktat abgebrochen wird oder der Watchdog greift.
- **Speicher:** Kontext wird beim Aktivieren der Engine vorgeladen und bei
  `TRIM_MEMORY_RUNNING_CRITICAL` freigegeben.

**Nicht geprüft:** whisper.cpp konnte auf der Entwicklungsmaschine nicht ausgeführt werden
(der Testlauf wurde blockiert). Ob das Modell auf dem Gerät korrekt erkennt und wie schnell,
zeigt erst der Gerätetest.

## 2. Direkt tippen statt Zwischenablage

- Neue Einfüge-Strategie `COMMIT_TEXT`, ganz vorn in der Kette: Ab Android 13 bekommt der
  Bedienungshilfe-Dienst mit `flagInputMethodEditor` eine eigene Eingabeverbindung und tippt
  wie eine Tastatur – an der Cursorstelle, mit Undo, auch in WebViews.
- Commit wird **nicht** zurückgelesen: Er kommt asynchron an, ein sofortiges Nachlesen sähe
  oft noch den alten Text, und die Kette würde denselben Text dann ein zweites Mal per
  `SET_TEXT` einfügen. Genutzt wird er nur, wenn die Verbindung zur App des fokussierten
  Felds gehört.
- Neue Einstellung `insertion.allowClipboard`, **Standard aus**: Dann gibt es weder Einfügen
  über die Zwischenablage noch Kopieren als Notlösung. Ein Diktat, das kein Feld annimmt,
  wird gemeldet und steht mit Wortlaut in der Diagnose.

## 3. Oberfläche

- Glas-Stil: dunkler Hintergrund mit drei langsam wandernden Lichtflecken, halbtransparente
  Karten mit violetter Kante, 28-dp-Radien, Pillen-Knöpfe, gezeichnete runde Icon-Knöpfe.
- Startseite: Diktier-Kugel (atmet, Ringe mit der Stimme, Bogen beim Schreiben), Testfeld,
  Einrichtung, einklappbare Karten für Zuverlässigkeit und Diagnose.
- Animationen: Karten erscheinen gestaffelt, Bildschirme gleiten je nach Richtung,
  Statuszeile wechselt animiert, Statuspunkte zeichnen ihr Häkchen.
- Fehler bringen Lösungs-Knöpfe mit (`SttFailure.Remedy`): „Sprachpaket laden“
  (`SpeechRecognizer.triggerModelDownload`), „Whisper lokal nutzen“, „Modell laden“.
- Einrichtung: Hinweis zu „Eingeschränkten Einstellungen“ mit Knopf zur App-Info, solange die
  Bedienungshilfe aus ist (ab Android 13; ob die Sperre greift, ist nicht abfragbar).

## 4. Blase nur bei Bedarf

- `bubble.onlyWithTextField` (Standard an) ersetzt `dimWhenNoTextField`.
- Sichtbar, solange ein Textfeld Eingabe annimmt (ab Android 13 über `onStartInput` /
  `onFinishInput` der Eingabeverbindung, sonst über Fokus-Ereignisse) oder ein Diktat läuft.
- Einblenden sofort mit Überschwinger, Ausblenden nach 400 ms, damit Feldwechsel nicht
  flackern. Versteckt nimmt das Fenster keine Berührungen an (`FLAG_NOT_TOUCHABLE`).

## Version

`versionCode 2`, `versionName 0.2.0`.
