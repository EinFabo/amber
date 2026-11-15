# Polish: what frustrates, what is missing, a security mode - plan

Fabi, 2026-11-15, after four read-only reviews (frustration, battery, competitors, first run;
summary in `docs/OPEN.md`, "Polish reviews") and a device test that found the first run's
stand-in failing. His choices:

- The stand-in: not Android's recognition (it may not keep the voice on the phone, and the
  Pixel had no offline pack for en-DE) - a small Whisper model that is on the phone in seconds.
- Keep a failed recording for "Try again" - yes, explained once more.
- A cleanup that fails says so - yes.
- A buzz when the microphone is really open, an optional sound - yes.
- Pause music while dictating - only as a setting, off by default.
- "Working or stuck" - yes.
- Own words - important.
- Spoken punctuation, "scratch that" - yes.
- A nudge to a real app after the introduction - yes, only naming apps that are installed.
- History, snippets, a tile, live text - yes, with data security in mind.
- New: a **security mode** for people who do not want anyone who takes their phone to see what
  they dictated.

Every phase ends with its checks (`:core:test`, `:app:testDebugUnitTest :app:lintDebug
:app:assembleDebug`, `check_strings.py`, `check_refs.py`, `typecheck-android.sh`), an
independent review, the fixes, and a test build tried on the Pixel 9a over adb.

## Phase A - the first run gets to a dictation at once

1. **A small Whisper model as the stand-in.** Quick setup on a phone that gets Parakeet first
   downloads FUTO Multilingual 74 (about 80 MB, seconds on Wi-Fi), makes it active, and
   fetches Parakeet after it; when Parakeet is ready Amber switches by itself, and the small
   model is deleted unless the user chose it. Android's recognition is no longer a stand-in.
   On mobile data the small model is fetched (it is small - the done step says how much) and
   Parakeet waits for Wi-Fi. The practice step says what is happening and works as soon as the
   small model is there. `FirstRun` in `:core` decides; tests for every path (Wi-Fi, mobile,
   the small model failing, the user choosing meanwhile, a restart in between).
2. **A nudge to a real app.** After the introduction, until the first dictation outside Amber
   (the statistics know it), Home and the done step say "Open WhatsApp and tap a text field"
   - with an app from the known list that is installed (`KnownApps`, `LauncherList`), or
   "any app" when none is.
3. **The microphone after two refusals.** "Allow" opens the app info when Android will not
   ask any more, on Home and in the practice step, as the microphone step already does.

## Phase B - dictating feels sure

1. **Keep a failed recording.** When recognition fails (no network, a timeout, a refused key,
   no credit), the recording stays in memory - never on disk - for 10 minutes. The bubble's
   quick menu shows "Try again" in its first place while it is there; a tap recognizes the
   same recording again, with the speech recognition set now. After 10 minutes, a successful
   dictation, or the security mode, it is gone.
2. **Short messages, the way out first.** Toasts at most two lines (Android cuts the rest at
   target 36): "Not inserted - hold the bubble, Copy". The reason goes to Diagnostics.
   `check_strings.py` checks the length of every toast string.
3. **Text that did not go in.** Where a field refused the text, the hint is "Copy" (hold the
   bubble, Copy last - then paste it yourself), not "Insert again", which fails in the same
   field. Home shows "Last dictation" with Copy while there is one, for the 10 minutes above.
4. **A cleanup that fails says so.** A refused key, no credit or an unknown model: one short
   toast per session ("Cleanup did not run - Groq key refused") and a row on top of Settings.
   A timeout stays silent: the text goes in as heard, as today.
5. **A buzz when the microphone is open**, on by default with the haptics switch; a start and
   stop sound as a setting, off by default.
6. **Pause music while dictating**, a setting, off by default (transient audio focus).
7. **Working or stuck.** Past twice the estimate the ring pulses slower and one hint says
   "Still working - hold the bubble to cancel"; a tap while working buzzes and shows the same.

## Phase C - the words come out right

1. **Own words.** One list under Settings → Text: names and terms. They go to Whisper as its
   prompt, to Parakeet as hotwords (sherpa-onnx), to servers as the prompt they take, and to
   the cleanup. Stored with the settings, on the phone only.
2. **Spoken punctuation on the phone.** "Komma", "Punkt", "Fragezeichen", "neue Zeile",
   "neuer Absatz" and the English ones become the characters, in `TranscriptNormalizer`,
   per language, without a cleanup; a single-line field gets no line break. A switch under
   Text, on by default.
3. **"Scratch that" / "streich das"** at the end of a dictation drops the sentence before it,
   and only an explicit phrase at the very end.

## Phase D - privacy: a security mode, then history

1. **Security mode** (Settings → Text or its own row; a clear sentence on what it does). When
   on, nothing anyone could read later is kept:
   - no history, no dictated text in Diagnostics (only shapes), no "Last dictation", no kept
     failed recording, no "Insert again" text after the insertion;
   - statistics count words and time only, without the app (or are off);
   - the clipboard fallback is off and Amber never writes the clipboard;
   - Amber's own screens are hidden from screenshots and Recents (FLAG_SECURE);
   - switching it on deletes what is already kept (history, diagnostics text, app names in
     the statistics).
   It does not hide that Amber is installed, and it cannot reach text already sent into other
   apps - the explanation says so.
2. **History of today's dictations** (only with the security mode off): text, app and time,
   encrypted with a key in Android's keystore that needs the phone unlocked
   (`setUnlockedDeviceRequired`), so a locked phone cannot read it; kept 24 hours by default
   (choices: off, 24 hours, 7 days), excluded from backups, a "Delete all" button. Copy,
   insert again, try again from it. The accessibility disclosure and the privacy policy name
   it.
3. Later, after D is tried: snippets (a spoken shortcut becomes saved text), a Quick Settings
   tile that dictates to the clipboard (only unlocked), live text near the bubble.

## Phase E - battery, measured first

On the Pixel over adb before any change: model reloads without a dictation, memory under a
trim, frames while listening, energy per dictation. Then only what the numbers confirm: one
model window instead of reload-on-focus, freeing the model at once on phones with 6 GB or
less, onnxruntime without spinning threads, Whisper's boost only after the stop, the bubble at
30 frames a second.

## Order

A, B and C run in parallel (separate areas; new settings in separate data classes to keep the
merge simple), E's measurement meanwhile. D after B (the kept recording and "Last dictation"
must obey the security mode). A test build after each merged phase.
