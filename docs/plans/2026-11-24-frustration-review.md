# Frustration review 2026-11-24: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix every code-side finding of the six read-only reviews of 0.35.0-rc5 (onboarding,
the dictation loop, polish, robustness, Google Play, F-Droid): no recording lost, no setup dead
end, no silent "ready" lie, cleaner text at the seams, plainer words - and the build parts
F-Droid would push back on.

**Architecture:** Same shape as `docs/plans/2026-11-22-store-review.md`: one commit per task on
the branch `frustration-review` (from `amber`), each task implemented by a fresh agent and
checked by a second agent before the next starts, a phase review after each phase, a
whole-branch review at the end, then the device checks of phase H. Every decision that can go
wrong is a value in `:core` with a unit test; `:app` only wires it to Android and words it in
`Texts.kt` / string resources.

**Tech Stack:** Kotlin, Jetpack Compose, `:core` pure JVM (JUnit), Android accessibility
service, whisper.cpp / sherpa-onnx (JNI), Go (gomobile) for Tinfoil's verifier.

**Spec:** the six review reports of 2026-11-24, summarized in the chat of that day and below
per task (evidence `file:line` as the reviewers gave it, at commit eca1931). The reviewers read
code only: **every task starts by confirming its finding in the code; a finding that does not
hold is reported back and skipped, not "fixed".**

## Global Constraints

- English everywhere in the repo; app text in `values/` **and** `values-de/`, plain words from
  `docs/GLOSSARY.md` ("speech recognition", not "engine"; German "du", never "Sie").
- No sentence in a screen's code: `:core` says what as a value (`Problem`, `Summary` ...),
  `Texts.kt` words it (`tools/check_strings.py` enforces it).
- Logic that can go wrong lives in `:core` with a test; `:app` only what needs an Android API.
- Speech data stays as spoken (German filler words, Whisper's German hallucinations).
- Commits carry Fabi's name only: no `Co-Authored-By`, no `Claude-Session`, no "Generated
  with" line. Commit messages in the repo's style: one plain sentence of what the user gets.
- Agent worktrees start on main: every implementer prompt begins with
  `git fetch && git reset --hard frustration-review` (the branch, not main).
- Privacy: no recording, transcript or field text in logs, Diagnostics or crash records -
  lengths, class names, paths and reasons only.
- After every task, all of these green before the commit:
  ```bash
  ./gradlew :core:test
  python3 -I tools/check_refs.py
  python3 -I tools/check_strings.py
  ./gradlew :app:lintDebug :app:assembleDebug   # lint: no new errors, no new warnings
  tools/typecheck-android.sh
  ```
- One entry per task in `docs/OPEN.md` section "Frustration review 2026-11-24": built, and
  what to try on a phone.

## Review Focus

1. **A 3 GB phone under memory pressure** (A20e): every new fallback, retry and stand-in must
   not load a second model while the first is still held - release first, then load.
2. **The German phone** - every new message and label exists in `values-de/`, in "du", within
   two lines on a 360 dp screen at 100 % and readable (wrapping, not clipped) at 200 % font.
3. **A dictation cancelled, killed or failed at each state** (Starting, Listening,
   Transcribing, CleaningUp, Inserting): the user either gets the text or a kept recording
   with "Try again" - never a silent loss, never a spinning bubble.
4. **De-Googled phones** (no `SpeechRecognizer` at all, no Play services): setup, Home and the
   practice step say honestly that nothing recognizes yet and show the way to a model or server.
5. **A field that already has text** (cursor in the middle, a composing word underlined by the
   keyboard, a search field): insertion never eats or duplicates existing text.

Each line has its test in the owning task (A1/A2, F1-F2, A1-A3, C1-C3, D1-D2).

---

## Phase A - no recording is lost

### Task A1: Cancel after the recording ended keeps it for "Try again"

Finding: "Still working. Hold the bubble to cancel." (`strings_dictation.xml:85`) leads to
`cancel()` (`DictationController.kt:598-602`), and `run`'s `finally` (~997) drops `runAudio`.

**Files:** Modify `app/.../dictation/DictationController.kt`, `core/.../dictation/KeptDictation.kt`,
strings EN/DE. Test: `core/src/test/.../KeptDictationTest.kt`.

**Interfaces:** Produces `Problem.Cancelled` (data object in `core/.../engine/Problem.kt`),
which `KeptDictation.worthTryingAgain` accepts.

- [ ] Confirm the finding (cancel while Transcribing/CleaningUp keeps nothing).
- [ ] Test `cancelled after recording is worth trying again`:
  `assertTrue(KeptDictation.worthTryingAgain(Problem.Cancelled))`; and that the toast for it
  is the reason followed by "Try again" (the existing two-line rule in that companion).
- [ ] Implement: in `cancel()`, when the state is past Listening and `runAudio != null`,
  `keepForRetry(Problem.Cancelled)` before cancelling the job; the message "Stopped. The
  recording is kept for Try again." / "Gestoppt. Die Aufnahme bleibt für „Nochmal versuchen“."
  A cancel during Listening stays as today (the user ended the recording on purpose).
- [ ] Checks green, commit.

### Task A2: A local model that fails or runs out of memory keeps the recording, and falls back

Finding: `Parakeet.kt:136-149`, `WhisperCppEngine.kt:168-186` raise `OutOfMemory` /
`LocalModelFailed`; `KeptDictation.worthTryingAgain` (`KeptDictation.kt:154-168`) leaves both
out; `Fallback.recoverable` (`Fallback.kt:21`) never falls back from an on-device engine.

**Files:** `core/.../dictation/KeptDictation.kt`, `core/.../engine/Fallback.kt`,
`app/.../dictation/DictationController.kt` (`failRecognition` ~1467). Tests:
`KeptDictationTest.kt`, `FallbackTest.kt` (create if missing).

- [ ] Tests: `worthTryingAgain(Problem.OutOfMemory)` and `(Problem.LocalModelFailed)` true;
  `Fallback.recoverable(<OutOfMemory failure>, TrustLevel.ON_DEVICE, mode)` returns a failure
  when a *different* on-device engine (or Android's recognition) is set as fallback, null
  when the fallback is the same engine.
- [ ] Implement; before a retry or fallback after `OutOfMemory`, release the failed model
  (the existing release path) so two models are never held at once (Review Focus 1).
- [ ] Checks green, commit.

### Task A3: Thinking before speaking no longer ends the recording

Finding: `noSpeechTimeoutMs = 4_000` (`VadPolicy.kt:134`) applies even with auto-stop off;
`toVadConfig` (`AmethystSettings.kt:135-144`) never changes it; a no-speech end keeps nothing.

**Files:** `core/.../settings/AmethystSettings.kt`, `core/.../audio/VadPolicy.kt`,
`DictationController.kt` (~1241-1270), strings EN/DE. Tests: `VadPolicyTest.kt`, settings test.

- [ ] Tests: `toVadConfig()` with `autoStopOnSilence = false` gives `noSpeechTimeoutMs ==
  12_000`; with it on, 4 000 as before. A no-speech end with more than 1 000 ms of audio is
  kept for Try again (`worthTryingAgain(Problem.NoSpeech)` or the controller's rule - name
  the one the code uses).
- [ ] Implement; message reworded: "Didn't hear anything. Speak closer to the phone, or Try
  again." / "Nichts gehört. Sprich näher am Handy oder tippe auf „Nochmal versuchen“."
- [ ] Checks green, commit.

### Task A4: A damaged model file no longer crashes the app on every dictation

Finding: `SherpaRuntime.kt:47` checks file sizes only; sherpa-onnx's `newFromFile` aborts the
process on a bad file; Whisper only checks the header (`ModelInstall.kt:128`).

**Files:** Create `core/.../engine/ModelLoadGuard.kt`; modify `app/.../engine/SherpaRuntime.kt`,
`app/.../engine/WhisperNative.kt` (or the loader that calls into JNI), the model page's
"damaged" state. Test: `ModelLoadGuardTest.kt`.

**Interfaces:** Produces `object ModelLoadGuard { fun before(modelId: String): Marker;
fun verdictAtStart(marker: Marker?, nowMs: Long): Verdict }` with `Verdict { NONE, DAMAGED }`
and `Marker(modelId: String, startedAtMs: Long)`; the app stores the marker as a small file in
`noBackupFilesDir`.

- [ ] Tests: a marker left from a load that never finished -> `DAMAGED(modelId)`; no marker
  -> `NONE`; a marker older than 24 h -> `NONE` (an unrelated kill long ago).
- [ ] Implement: write the marker before the native load, delete it after (success or a
  caught failure). At start, `DAMAGED` marks the model so it is not loaded again, re-checks
  its hash where the catalog has one, and the model page offers "Download again"
  (`Problem`/value in `:core`, words in `Texts.kt`). A diagnostic line names the model.
- [ ] Checks green, commit.

### Task A5 (decision D1: yes): the recording survives a process kill

Finding: `KeptDictation.kt:10` "in memory only - never on disk"; a kill loses up to 5 min.

**Files:** Create `core/.../dictation/RecordingSpool.kt` (when to write, when to delete,
expiry); `app/.../dictation/` a spool writer using History's per-day keys (`HistoryStore`).

- [ ] Tests: spool kept only while a run is open; deleted on delivery, on Cancel during
  Listening, and after 24 h; never written when History is off (the user chose no disk).
- [ ] Implement: encrypted PCM chunks while recording; at start a leftover spool offers
  "Recover last recording" on Home (recognize it with the current speech recognition).
  Privacy policy texts (`docs/PRIVACY.md`, `raw/` and `raw-de/privacy_policy.txt`) say so.
- [ ] Checks green, commit.

**Phase A review:** one agent reviews A1-A5 together against Review Focus 1 and 3.

---

## Phase B - the app tells the truth

### Task B1: "Ready" only when the bubble's service is really connected

Finding: `ui/Setup.kt:104-109` reads only `Settings.Secure`; Home (`HomeScreen.kt:707`) and the
practice step (`PracticeStep.kt:251`) trust it; `graph.accessibilityService`
(`AmethystApp.kt:536`) is the live connection and nobody in the UI reads it.

**Files:** Create `core/.../onboarding/ServiceHealth.kt`; modify `ui/Setup.kt`,
`HomeScreen.kt`, `PracticeStep.kt`, `Texts.kt`, strings EN/DE. Test: `ServiceHealthTest.kt`.

**Interfaces:** `enum class ServiceHealth { OFF, STARTING, RUNNING, NOT_RUNNING }`;
`fun ServiceHealth.Companion.of(switchOn: Boolean, connected: Boolean, msSinceAppStart: Long)`
- `NOT_RUNNING` when on, not connected and more than 2 000 ms since start.

- [ ] Tests for the four cases, including the 2 000 ms edge.
- [ ] Implement: Home shows a card for `NOT_RUNNING`: "The bubble's service is switched on
  but not running. Switch it off and on again." with "Open settings" (the existing
  highlight intent); the practice step blocks the same way. German in "du".
- [ ] Checks green, commit.

### Task B2: Crashes and kills leave a trace in Diagnostics

Finding: no `UncaughtExceptionHandler`, no `getHistoricalProcessExitReasons`;
`DictationLog.kt:11` keeps the log in memory; the service scope
(`AmethystAccessibilityService.kt:633`) has no handler.

**Files:** Create `core/.../dictation/ExitRecords.kt`; modify `AmethystApp.kt`,
`AmethystAccessibilityService.kt`, the Diagnostics screen and its Copy text. Test:
`ExitRecordsTest.kt`.

**Interfaces:** `data class ExitRecord(atMs: Long, kind: ExitKind, detail: String)`,
`enum class ExitKind { CRASH, NATIVE_CRASH, ANR, LOW_MEMORY, OTHER }`;
`object ExitRecords { fun fromThrowable(t: Throwable, nowMs: Long): ExitRecord;
fun merge(old: List<ExitRecord>, new: List<ExitRecord>): List<ExitRecord> }` - newest first,
at most 10, deduplicated by `atMs` and kind.

- [ ] Tests: `fromThrowable` keeps the exception class and at most 8 frames from
  `app.amethyst`, never the message (it can hold text); `merge` caps at 10 and dedups.
- [ ] Implement: a chained uncaught-exception handler (calls the previous one) writes the
  record to `noBackupFilesDir/exits.json`; at start on API 30+ read
  `ActivityManager.getHistoricalProcessExitReasons` since the last read and map reasons to
  `ExitKind`; Diagnostics shows "Embersay stopped unexpectedly at 14:02 (out of memory)"
  and the Copy text includes the records. Add the service scope's handler.
- [ ] Checks green, commit.

### Task B3: The bubble tells "on the clipboard" apart from "inserted"

Finding: `DictationController.kt:2151-2186` treats `copiedToClipboard` as success: green
bubble, success buzz.

**Files:** `core/.../dictation/DictationState.kt` (a `Done.copiedOnly` flag or a state),
`app/.../bubble/BubbleController.kt`, the haptics call. Test: the state's mapping test.

- [ ] Test: a delivery that only copied maps to the clipboard look, not DONE.
- [ ] Implement: amber (not green) with the copy glyph, the short double buzz the app uses
  for "needs you" (reuse, don't invent), the message as today.
- [ ] Checks green, commit.

### Task B4: With Android's speech recognition, the bubble listens visibly only once it listens

Finding: `BubbleController.kt:643` draws Starting as LISTENING; `Listening.open`
(`DictationState.kt:26`) is read by no one in the bubble; the comment at
`DictationController.kt:1046` ("the bubble buzzes") is stale since e59b694.

**Files:** `BubbleController.kt`, the bubble's drawing, `DictationController.kt:1046`
comment. Test: the bubble-look mapping in `:core` if it lives there.

- [ ] Implement an "opening" look (dim ember, no flames) until `open`; then the listening
  look as today. The app's own recorder (frames kept before the mic opens,
  `AudioCapture.kt:216`) is open at once, so only Android's recognition shows it.
- [ ] Fix the stale comment. Checks green, commit.

**Phase B review.**

---

## Phase C - setup without dead ends

### Task C1: Phones with no speech recognition at all get an honest setup

Finding: `EngineAdvice.recommend()` (`EngineAdvice.kt:52-54`) falls back to `ANDROID`
regardless of `PhoneFacts.androidRecognizer` (set in `FirstRunSetup.kt:165`, used nowhere);
the practice step treats ANDROID as ready.

**Files:** `core/.../engine/EngineAdvice.kt`, `core/.../onboarding/FirstRun.kt` (new
`DoneState.NO_RECOGNITION`), `core/.../onboarding/Practice.kt`, the done step and Home's
recognition row, strings EN/DE. Tests: `EngineAdviceTest.kt`, `FirstRunTest.kt`,
`PracticeTest.kt`.

- [ ] Tests: `recommend(PhoneFacts(totalRamMb = 2048, freeStorageMb = 500, whisperCpu =
  false, androidOnDevice = false, androidRecognizer = false))` recommends `SERVER` (or a
  new `NONE`, pick one and say why in the KDoc) and `others` does not contain `ANDROID`;
  with a model that fits, that model; `DoneState` is `NO_RECOGNITION`; `Practice` blocks.
- [ ] Implement; the done step: "Nothing on this phone can recognize speech yet. Download a
  model, or use a server." with both ways; Home's row says the same.
- [ ] Checks green, commit.

### Task C2: The introduction counts as done once the bubble works

Finding: `onboardingDone` is set only by the done step's button (`MainActivity.kt:816`) or
Skip (`FirstRun.kt:161`); leaving at the practice step brings the welcome back.

**Files:** `core/.../onboarding/FirstRun.kt`, `MainActivity.kt`. Test: `FirstRunTest.kt`.

- [ ] Test `reaching practice with setup ready finishes the introduction`: a pure function
  `FirstRun.finished(step: Step, setupReady: Boolean): Boolean` true for the practice and
  done steps when ready, false before.
- [ ] Implement: store `onboardingDone` as soon as it is true. Checks green, commit.

### Task C3: "All set" only when it is

Finding: `DoneStep` gets no `SetupState` (`OnboardingScreen.kt:146-149`); `FirstRun.atDone`
(`FirstRun.kt:243`) only looks at models and the language pack.

**Files:** `FirstRun.kt` (`DoneState.SETUP_MISSING`, ranked before the model states),
`ChoiceSteps.kt` / done step, strings. Test: `FirstRunTest.kt`.

- [ ] Test: service or microphone missing -> `SETUP_MISSING`, whatever the model state.
- [ ] Implement: title "Two things are still missing" / "One thing is still missing",
  a button back to the first missing step. Checks green, commit.

### Task C4: Dismissing Android's microphone dialog is not a refusal

Finding: `MainActivity.kt:181-186, 342-347` store `KEY_MIC_REFUSED = true` on any denial;
`MicrophoneAsk.way` (`MicrophoneAsk.kt:64-68`) then sends the next "Allow" to app info.

**Files:** `core/.../onboarding/MicrophoneAsk.kt`, `MainActivity.kt`. Test:
`MicrophoneAskTest.kt`.

- [ ] Tests: denied with no rationale within 300 ms of the request (no dialog shown) ->
  APP_INFO; denied after a dialog was on screen longer than 300 ms -> ask again next time
  (count it as one refusal; Android's own "don't ask again" after two still leads to
  APP_INFO).
- [ ] Implement: the app passes the elapsed time; it always tries the dialog first.
- [ ] Add under "Allow" the hint "Choose “While using the app”." / "Wähle „Während der
  Nutzung der App“." (Android's wording; check it on the Pixel in phase H). Commit.

### Task C5: The accessibility step names the maker's way (Samsung, Xiaomi)

Finding: `Setup.kt:143-152` relies on AOSP's highlight; One UI lists the service under
"Installed apps", Xiaomi under "Downloaded apps"; no string says so.

**Files:** Create `core/.../onboarding/SwitchOnSteps.kt`; modify the accessibility step and
its picture, `Texts.kt`, strings. Test: `SwitchOnStepsTest.kt`.

**Interfaces:** `enum class SettingsMaker { AOSP, SAMSUNG, XIAOMI }`,
`fun SettingsMaker.Companion.of(manufacturer: String)` (case-insensitive; "samsung";
"xiaomi", "redmi", "poco"), and the step list as values.

- [ ] Tests for the mapping and that SAMSUNG's first step is the "Installed apps" one.
- [ ] Implement; German labels as One UI shows them ("Installierte Apps"); check on the
  A20e in phase H. Commit.

### Task C6: The battery step leads to the right switch

Finding: `Setup.kt:165-166` opens `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (a filtered
list without Embersay); `ReliabilityRows` (`HomeScreen.kt:555-570`) has one line.

**Files:** `Setup.kt`, `HomeScreen.kt` reliability rows, `SwitchOnSteps.kt` (battery steps per
`SettingsMaker`), strings. Test: `SwitchOnStepsTest.kt`.

- [ ] Test: battery steps per maker (AOSP 12+: App info -> Battery -> Unrestricted; Samsung:
  + "Never sleeping apps"; Xiaomi: + "Autostart").
- [ ] Implement: open the app's own details page (`ACTION_APPLICATION_DETAILS_SETTINGS`)
  with numbered steps; keep the auto-return. Commit.

### Task C7: "Step by step" with a big model gets the small stand-in too

Finding: `FirstRun.use` (`FirstRun.kt:182-191`) sets `pendingEngine = null`; the practice
step blocks for the whole 670 MB download.

**Files:** `FirstRun.kt`, the engine card's line. Test: `FirstRunTest.kt`.

- [ ] Test: `use(PARAKEET)` on a phone where the small model fits plans the small model now
  and Parakeet next, like `plan()` does; on a phone where it doesn't fit, as today.
- [ ] Implement; the card says "Starts with a small model, the better one follows." Commit.

### Task C8: The setup sheets keep what you typed

Finding: the cleanup sheet and its key/model fields are `remember`
(`ChoiceSteps.kt:74`, `Pipeline.kt:78`, `AppStyle.kt:99`, `CleanupServices.kt:363-374`);
rotation or a process kill on the way back from the key website loses them.

**Files:** those four. No `:core` logic.

- [ ] Implement with `rememberSaveable` (the key field too - it lives in the saved state
  only, never in settings). Add a show/hide eye to the masked key field
  (`CleanupServices.kt:779-792`), content description EN/DE, `FLAG_SECURE` as today.
- [ ] Checks green, commit.

### Task C9: Quick setup explains before Android asks, and says what it downloads

Finding: `FirstRunSetup.quick()` fires `requestLanguageDownload` at once; `welcome_ways`
(`strings_setup.xml:57`) does not say Quick setup downloads ~82 MB, also over mobile data
(`FirstRun.anyNetwork`, `FirstRun.kt:312`).

**Files:** `FirstRunSetup.kt`, `FirstRun.kt`, the welcome step, strings. Test: `FirstRunTest.kt`.

- [ ] Test: the welcome's note value is `DOWNLOADS_SMALL_MODEL(sizeMb)` when the plan
  downloads one and the network is metered, none otherwise.
- [ ] Implement: the language pack is asked from the practice/done step's existing button,
  not at the tap; the welcome line "Downloads a speech model (82 MB) now, the better one on
  Wi-Fi." on metered networks. Commit.

### Task C10 (decision D4: yes): the microphone before the accessibility service

Finding: `core/.../onboarding/Onboarding.kt:10,36` asks accessibility first; Home's setup card
asks the microphone first (`HomeScreen.kt:689-710`).

- [ ] Swap the order in `Onboarding.kt`, tests in `OnboardingTest`
  updated, the steps' texts still read in order. Commit.

**Phase C review** (with Review Focus 4).

---

## Phase D - the words land right

### Task D1: Inserting never eats the word the keyboard is still writing

Finding (unverified, device check in H): `TextInserter.kt:519-537` `commitText` replaces the
composing region; `InsertionEngine.kt:271-291` and `Landed.kt:47-58` check only that the new
words arrived.

**Files:** `core/.../insert/Landed.kt`, `InsertionEngine.kt`, `TextInserter.kt`. Test:
`LandedTest.kt` / `InsertionEngineTest.kt`.

- [ ] Tests: `Landed` reports `PREFIX_LOST` when the field's text before the seam is shorter
  than before the insert ("Hallo|" + "wie geht's" -> "wie geht's"); the engine then
  restores by committing prefix + words, or falls back to SET_TEXT; a fake field that keeps
  a composing word proves it.
- [ ] Implement; a diagnostic line names the path (lengths only). Commit.

### Task D2: Capitals and full stops fit the text around them

Finding: `TextSplicer.kt:36-89` only ever capitalizes and never drops a final mark; no field
type is looked at (search, URI, e-mail).

**Files:** `core/.../text/TextSplicer.kt` (+ a `FieldKind` input), the caller in the
inserter (from `EditorInfo`/node input type). Test: `TextSplicerTest.kt`.

- [ ] Tests: "Meet me at |" + "The station." -> "Meet me at the station." (English common
  word lowered); German "Ich gehe zum |" + "Bahnhof." keeps "Bahnhof" (a noun - keep
  capitals in German unless the word is in a small list of common lower-case words);
  "| tomorrow" + "See you." -> "See you tomorrow"; `FieldKind.SEARCH`/`URI`/`EMAIL`:
  no auto-capital, no final full stop ("pizza near me").
- [ ] Implement. Commit.

### Task D3: Long dictations get no stray full stops at the cuts

Finding: `ChunkPlanner.kt:19` cuts at 450 ms; `ChunkedTranscription.kt:140-150` `join` only
adds spaces; each chunk ends as its own sentence.

**Files:** `core/.../capture/ChunkPlanner.kt`, `core/.../engine/ChunkedTranscription.kt`.
Tests: `ChunkingTest.kt`.

- [ ] Tests: the planner prefers a pause of 800 ms or more and uses 450 ms only within the
  last quarter before the longest chunk. `join` rule: when the cut was short (< 800 ms) and
  the next chunk starts with a common lower-case word (the D2 list), drop the "." and lower
  the word ("Ich wollte dir sagen." + "Dass ich komme." -> "Ich wollte dir sagen dass ich
  komme." is accepted - no comma is guessed); after a long pause, or a next word not on the
  list, both stay. Test both outcomes.
- [ ] Implement. Commit.

### Task D4: Whisper's hallucinations are filtered on servers too

Finding: `WhisperOutput.clean` (`WhisperInput.kt:126-148`) runs only for the phone's Whisper
(`WhisperCppEngine.kt:127`); `OpenAiCompatibleEngine.kt:152-162` checks only empty/echo; a
trailing hallucinated sentence after real speech stays everywhere.

**Files:** `WhisperInput.kt`, `OpenAiCompatibleEngine.kt`. Test: `WhisperOutputTest.kt`.

- [ ] Tests: "Wir sehen uns morgen. Untertitel im Auftrag des ZDF, 2020." -> "Wir sehen uns
  morgen."; "Thanks for watching!" alone -> ""; a sentence that only contains the phrase
  mid-text stays. Server output from a Whisper-family model (model id contains "whisper")
  goes through `clean`.
- [ ] Implement. Commit.

### Task D5: A hung server hands over in seconds, not minutes

Finding: `OpenAiCompatibleEngine.kt:255` `requestTimeoutMs = 60_000 + audioMs`;
`ChunkedTranscription.kt:77` re-sends the whole recording to the same server after any chunk
failure; `ReadyFallback.deadlineMs` only with `fallback.prepare` (off by default).

**Files:** `OpenAiCompatibleEngine.kt`, `ChunkedTranscription.kt`, a deadline value in
`:core`. Tests: the engine's and `ChunkingTest.kt`.

- [ ] Tests: answer deadline after the upload = `max(10_000, 3 × expectedMs)` and counts as
  `NoAnswerInTime` (recoverable); a chunk failing with timeout or no connection does not
  re-send the whole recording to the same server - it goes to the fallback (or keeps the
  recording for Try again).
- [ ] Implement. Commit.

### Task D6: A slow tap starts, it doesn't open the quick menu

Finding: `BubbleGesture.kt:175` `HOLD_MS = 300L`.

- [ ] Test in `BubbleGestureTest.kt`: a 350 ms press is a tap; 450 ms a hold.
- [ ] Implement 400 ms (or `ViewConfiguration.getLongPressTimeout()` passed in from `:app`,
  clamped 400-500). Commit.

### Task D7: Music pauses while recording, by default

Finding: music from the speaker ends up in the recording; `pauseMedia = false` by default
(`AmethystSettings.kt:759`). Fabi (D3): pause by default; the headset microphone not now.

- [ ] Test: a fresh settings file has `pauseMedia = true`; an existing file where the user
  set it keeps its value (migration: only a value never touched by the user changes - check
  how the settings file tells the two apart, and if it cannot, leave existing users alone).
- [ ] Implement; the settings row's text unchanged. Commit.

**Phase D review** (with Review Focus 5).

---

## Phase E - fast and light

### Task E1: Settings warns before a model too big for the phone

Finding: `EngineAdvice.fits` (`EngineAdvice.kt:69-79`) is used only by the introduction; "Add
speech recognition" (`SettingsScreen.kt:716`) and the model list (`EngineDetailScreen.kt:569`)
offer everything.

- [ ] Test: a mapping from a preset/model to `EngineOption` so `fits()` answers for it
  (Parakeet and large-v3-turbo do not fit 3 GB).
- [ ] Implement: such rows say "Too big for this phone's memory" and switching asks first.
  Commit.

### Task E2: No 1 500-node walks on the service's main thread

Finding: `onStartInput` -> `focusedIsPassword()` -> `searchedField()`
(`AmethystAccessibilityService.kt:455-461, 528`) -> `TextInserter.searchFocusedEditable`
(cap 1 500, `TextInserter.kt:465`), also on the bubble tap (`DictationController.kt:2036`).

- [ ] Implement: the password search on a background dispatcher with a 50 ms budget, the
  result posted back (hide the bubble until known only when the input type says password);
  the tap decides from the keyboard's input type first. A diagnostic line with the walk's
  node count and time. Commit.

### Task E3: The first tap does not build the key store on the main thread

Finding: `EngineRouting.notUsable` always calls `hasKey` (`EngineRouting.kt:67`), touching the
lazy `secretStore` (`AmethystApp.kt:163, 575-576`) from `start()` (`DictationController.kt:417`).

- [ ] Test in `EngineRoutingTest.kt`: `hasKey` is not called for an engine without a key
  alias (a counting fake).
- [ ] Implement; when the store is not ready yet, decide routing off the main thread.
  Commit.

### Task E4: Cooler on long dictations

Finding: four threads at top priority plus a performance hint that always reports "late"
(`WhisperNative.kt:205-211, 253-271, 286-288`, `SherpaRuntime.kt:34`); no thermal or
battery-saver check.

- [ ] Test: `InferenceThreads.choose(cores: Int, thermal: Thermal, saver: Boolean): Plan`
  (new, `:core`) - fewer threads and no hint at MODERATE or above or with battery saver.
- [ ] Implement with `PowerManager.currentThermalStatus` / `isPowerSaveMode`. Commit.

### Task E5: Old model files are swept

Finding: `WhisperModelStore.kt:88-91` sweeps only `.deleting`; the Parakeet folder carries its
version (`Parakeet.kt:34`, `ParakeetModelStore.kt:47`).

- [ ] Test: `ModelSweep.stale(files: List<String>, current: Set<String>): List<String>`
  keeps current slots and current unfinished parts, returns the rest.
- [ ] Implement at start on IO, a diagnostic line with the freed MB. Commit.

**Phase E review.**

---

## Phase F - polish and words

Each task: strings EN and DE, `check_strings.py` green, the glossary obeyed.

- [ ] **F1 Home on a fresh install.** One group for History and Today, one line ("Your
  dictations show up here") until the first dictation; the test field right under the orb
  and status line (`HomeScreen.kt:209-213`); the language-pack failure's two buttons in a
  `FlowRow` (`HomeScreen.kt:654-657`). Check at 200 % font in the emulator.
- [ ] **F2 German messages read as German.** No quotes around service names (EN and DE
  alike, `strings_messages.xml:5,6,26,36`); full "du" sentences instead of infinitives
  (`strings_dictation.xml:43`, `strings_settings.xml:46`); "Ohne Mikrofon weiter"
  (`strings_setup.xml:75`); "Ausgeblendet in diesen Apps" (`strings_settings.xml:85`);
  `strings_messages.xml:18`, `strings_dictation.xml:46` rewritten.
- [ ] **F3 "Add speech recognition" in plain words.** The dialog uses the short names the
  list uses (`SettingsScreen.kt:730-737`), one plain line each (speed, size, where it runs);
  "whisper.cpp", "large-v3", "/v1/audio/transcriptions" only on the server's own page
  (`strings_catalog.xml`).
- [ ] **F4 History: the texts first.** Entries on top, keeping and the notes below or behind
  "How it's kept" (`HistoryScreen.kt:76-105`).
- [ ] **F5 Tinfoil page in the phone's language.** Verifier failures as `Problem` values
  worded in `Texts.kt` (`TinfoilVerifier.kt:74,76,101,169-171`); the log and the hex behind a
  "Details" fold; a localized date (`EngineDetailScreen.kt:860-876`).
- [ ] **F6 One word per trust level.** One string set instead of `place_*` (twice) and
  `trust_*`; the badge's word wins; "Server" vs "Cloud" settled by what the badge shows for an
  own server (`SettingsScreen.kt:303`, `StatsScreen.kt:188`, `Texts.kt:287`).
- [ ] **F7 Names, icons, typography.** One name per setting in setup and settings
  (`strings_setup.xml:90` vs `strings_settings.xml:76`, `:71`, `:38`, `strings_engine.xml:5`,
  `strings_cleanup.xml:3,16`); distinct icons (shield for Security mode, document for the
  policy, a menu glyph for the Quick menu, two for own recognition/cleanup); English "50%";
  German "Aufnahme läuft …"; the comma splice in `strings_settings.xml:36`.
- [ ] **F8 Store listing and changelog.** `fastlane/metadata/android/{en-US,de-DE}`: title
  "Embersay: Voice Dictation" / "Embersay: Diktieren in jeder App" (≤ 30); short and full
  description in plain words first, product names in a closing "Works with" line, the
  cleanup's need for a key or server said, "du", no " - " dashes, „…“ quotes; a launch note
  as the next versionCode's changelog. A check in `tools/check_strings.py` (or a sibling)
  for the listing: limits, banned glossary words, "Sie".
- [ ] **F9 Honest privacy texts.** "normally" for the recording notification (it is not
  guaranteed, `MicForegroundService.kt:22-27`) in `docs/PRIVACY.md` and both raw texts; the
  Diagnostics line of OPEN.md's note for item 22. Doc fixes: `docs/PLAY-CONSOLE.md:13`
  (GitHub/F-Droid use the GitHub key c3f69e72…, not the store key - both options with their
  trade-off), `:45` (the rows are on screen), `:113` and `docs/OPEN.md:317` (Play's rule is
  2.3:1 - 1080x2340 fits, only the alpha must go), OPEN.md's bookworm -> trixie.

**Phase F review** (with Review Focus 2; a native German reader's pass over every changed
German string).

---

## Phase G - store and build

### Task G1 (decision D2: yes): the update check asks first

Finding: `AmethystSettings.kt:669` `enabled = true`; F-Droid counts an update check without
consent as Tracking; an APK from f-droid.org installed by the package installer would check
GitHub daily (`ReleaseChannel.kt:39-59`).

- [ ] Off by default with a one-time question on Home ("Check GitHub for new
  versions?") for GitHub-signed installs not from a store; a settings migration keeps an
  existing user's explicit choice. Tests in `UpdatePolicyTest`/`ReleaseChannelTest`. Commit.

### Task G2: Tinfoil's verifier builds from a submodule and a local Go

Finding: `tools/build-tinfoil-aar.sh:94` curls the tinfoil-go source; `:114`
`GOTOOLCHAIN=go1.27.3` downloads a prebuilt Go.

- [ ] Add tinfoil-go as a submodule in `third_party/` at the pinned commit (`fad7780…`); the
  script builds from it; `GOTOOLCHAIN` honours an existing `local` (F-Droid's srclib) and
  only downloads when the local Go is not 1.27.3. The AAR's bytes stay the same (compare
  sha256 before/after). README's build section updated.
- [ ] `docs/fdroid/com.embersay.yml`: `srclibs: go@go1.27.3`, prebuild
  `sdkmanager 'platforms;android-36'` (and `cmake;3.22.1`), the submodule; the Builds entry
  stays marked "next stable release" (no stable com.embersay release yet). Commit.

### Task G3: NOTICE names everything inside the APK

- [ ] Add ONNX Runtime's third-party notices and the Go modules in `libgojni.so` (from
  tinfoil-go's `go.mod`, with each license) to `NOTICE` and the in-app licenses page; a
  small script in `tools/` that lists the Go modules so it stays current. Commit.

### Task G4: Store images pass Play's checks

- [ ] `tools/flatten_store_images.py`: screenshots to 24-bit RGB (no alpha), the 512 icon a
  full square on the app's background; run it on `fastlane/metadata/android/*/images`.
  Commit. (New screenshots stay Fabi's item 53.)

**Phase G review.**

---

## Phase H - on the phones, then the whole branch

Done by the main session over adb (A20e R58N13WKMYV directly; the Pixel 9a through Windows'
`adb.exe`, screen-off disabled during the tests and restored after; screenshots and logcat only,
no `uiautomator dump`):

- [ ] D1: type a word without a space (Gboard and Samsung keyboard), dictate - prefix kept.
- [ ] Compose text fields on Android 11 (A20e) in a third-party Compose app: bubble and
  insertion. If they fail, a follow-up task: `deliver` falls back to the inserter's tree
  search below Android 13 when the app in front is the start app.
- [ ] C5/C6 on the A20e: the Samsung steps match One UI's screens in German.
- [ ] C4: the "While using the app" wording on the Pixel; whether "Only this time" is taken
  back overnight.
- [ ] The "Recording …" notification on the A20e (the direct microphone path from API 30).
- [ ] A3, A1, B4 with the TTS voice (Windows SAPI "Hedda").
- [ ] Final whole-branch review by a fresh agent on the most capable model; fixes; then
  `docs/OPEN.md` updated and the branch ready to merge into `amber`.

## Fabi's decisions (2026-11-24)

- **D1** (A5): yes - a running recording is kept encrypted on disk (History's day keys),
  deleted after delivery, never written with History off.
- **D2** (G1): yes - the update check is off until the user agrees.
- **D3** (D7): music paused while recording by default. The Bluetooth headset microphone is
  **not** built: it needs Android's call mode (0.5-2 s to connect, so the first words are
  lost; 8 kHz on older headsets; the headset switches to a call) - later as an opt-in
  setting, on the list in `docs/OPEN.md`.
- **D4** (C10): yes - the microphone before the accessibility service.

Not in this plan (Fabi's or after the release): the privacy policy's controller and URL, the
Console, videos, closed test, signing choice, a stable release for F-Droid (from
2027-01-27), a Play build without `MicProxyActivity`, the "bubble gone?" daily check beyond B1.
