# Store review 2026-11-22: the fixes, by phase

A read-only review of 0.35.0-rc3 (seven reviewers: dictation pipeline, OS/OEM compatibility,
security, polish, performance, Google Play, code quality) before the Play Store launch. Fabi
approved everything below on 2026-11-22. Built on the branch `store-review`, one commit per
sub-phase, each reviewed by a second agent before the next starts.

Baseline at f97a3c8: `:core:test` green, `check_refs` and `check_strings` clean, lint 0 errors /
70 warnings.

## Fabi's decisions

- **Scope:** phases 1-6 in full; from phase 7 the Console texts and new screenshots.
- **MicProxyActivity:** "find a way that switching has no downside". The direct microphone in
  the accessibility service's process is the main path (Android 11+: the system binds an
  accessibility service with `BIND_INCLUDE_CAPABILITIES`, so it holds while-in-use microphone
  access while the screen is on). The foreground service runs only for the length of a
  recording. The proxy activity stays as the last fallback - Android 9/10, or when the direct
  path is provably silent - so nothing that works today stops working.
- **Privacy policy:** on Fabi's own domain later (he is writing an imprint). Now: the policy as
  text inside the app (Play accepts "a link or text within the app"); a URL constant to fill
  in later. Controller name, address and e-mail are Fabi's to give - listed in OPEN.md.
- **Importing own Whisper models:** stays. Hardened: stricter header checks, size limits, a
  "only from sources you trust" note.

## Phase 1 - no dictation is lost, no crashes

1. **Cleanup exceptions lose the text.** `DictationController.kt` `cleanUp()` (~1585-1679) has
   no try/catch; `apiKeyProvider()` (EncryptedSharedPreferences can throw SecurityException),
   prompt building and `CleanupGuard` can throw. The generic catch in `run` (~844-855) calls
   `fail(...)` without `text =` and without `keepForRetry`. Fix: guard `cleanUp` and fall back to
   the transcript; hold the last transcript in the run and pass it to `fail(text = ...)` plus
   keep the recording for "Try again" in `run`'s catch.
2. **A settings file that fails to decode resets everything, and the reset is saved.**
   `core/.../settings/AmethystSettings.kt:405-418` (`SettingsCodec.decode` returns defaults on
   any exception; no `coerceInputValues`), `app/.../data/SettingsStore.kt:50-53`. Fix:
   `coerceInputValues = true`; on a failed decode keep the raw blob under a backup key and let
   `update()` refuse to overwrite it until resolved (or restore from the backup); a Diagnostics
   line; tests for an unknown enum value and a malformed field.
3. **"Insert again" runs on the main thread.** `DictationController.insertAgain()` (~706-724),
   called from `QuickActionRunner.kt:55` on a touch handler: `resolveTarget()` walks up to 1500
   nodes and `InsertionEngine` verifies with `Thread.sleep(60)` loops (up to ~3 s). Fix: run it
   like `undoLast()` in `scope.launch` off main, toast afterwards.
4. **"Prepare on the phone" keeps the run busy after the text is in.**
   `DictationController.kt` ~1334-1342 and ~1403-1406: `prepareFallback` starts
   `engine.warmUp()` as a child of the timeout block; the run waits for it (Parakeet cold load:
   seconds). A tap then says "Still working"; Insert again / Undo / Try again refuse; a proxy
   re-entry returns early. Fix: run the prepared warm-up and its chunks in a scope of their own
   (`appScope` + own Job, like `Graph.prepareForDictation`) the run does not wait for.
5. **The run's time limit during the cleanup drops the recognized text.** ~798-827: cleanup and
   delivery sit inside `withPausableTimeout`. Fix: once a transcript exists, deliver it as
   recognized when the limit runs out; bound the cleanup by `min(remaining, own timeout)`.
6. **The last words can be dropped after a forced chunk cut.** `core/.../ChunkPlanner.kt`
   (`speechSinceCut = ms(totalSamples - cut) > 0 && silenceMs == 0`), `AudioCapture.kt:145-149`.
   Fix: remember the sample of the last speech frame; `speechSinceCut = lastSpeechSample > cut`.
   Test in `:core`.
7. **A cancel at the wrong moment leaves the bubble spinning and the screen on.** `cancel()`
   (~523-527) sets Idle from main while the run may write Listening/Transcribing next (~918,
   ~980, ~1196). Fix: in `run`'s `finally`, if cancelled and the run token is still current, set
   Idle.
8. **"Service off" at delivery drops the words.** ~1858-1864: `kept.textRecognized()` clears
   the kept recording, then `fail(...)` without `text = cleaned`. Fix: pass the text; clear the
   kept recording only after a successful outcome.
9. **A proxy re-entry during a running job.** ~351 and ~1071-1077: `start(PROXY_ACTIVITY)`
   returns at `if (isRunning) return` after the handover is used up; 3 s later "microphone not
   opened". Same when a download starts between tap and return (~362, state left as Starting).
   Fix: on an early return set the state back to Idle (or wait for the old job).
10. **Errors that are not exceptions kill the process.** `AmethystApp.kt:113` (appScope without
    a `CoroutineExceptionHandler`), `run` catches `Exception` only. Fix: a handler on appScope
    that logs to Diagnostics; around the engine call turn a `Throwable` (not cancellation) into
    a failure that keeps the audio.
11. **Display timers end a newer run's result early.** ~1961-1965 (Done 1.2 s) and ~2089-2092
    (Failed 3.5 s) are not tied to `runSerial`. Fix: capture the serial and compare.
12. **Call detection gaps (5d1d6fc).** `MicrophoneWatch.kt:73-75`, `PhoneCall.kt`,
    `DictationController.kt` ~909-947: Android's recognizer gets no call check; `callAtStart` is
    latched once, so a second call is missed; the wording says "another app took the
    microphone". Fix: re-arm when the mode returns to normal (latch on false->true); for the
    Android recognizer poll `PhoneCall.on()` and `requestStop()`; its own message ("A call
    ended the recording" / DE) - the recording is kept. Do not stop on MODE_IN_COMMUNICATION
    alone when no call state confirms it, if the code can tell (check what `PhoneCall` reads).
13. **Small hygiene.** `AmethystAccessibilityService.kt:89,97,102` (`foregroundPackage`,
    `appInFront`, `editableFieldFocused`) need `@Volatile`; `MainActivity.kt:572-574`
    `updateSettings` launches on `rememberCoroutineScope` - use `graph.appScope`.
14. **One reset for the run's state.** About 17 `@Volatile` per-run fields
    (`DictationController.kt:150-293`) are reset by hand in `start()` (~366-378), `tryAgain()`
    (~1310-1318) and `run()` (~750-757). Fix now: one `resetRunState()` used by all of them.
    (The full `RunContext` split waits until after the release.)

## Phase 2 - microphone and messages

15. **The microphone foreground service runs all day** (Play blocker). `MainActivity.kt:155,
    215, 337` (`ensureMicServiceRunning` on every resume, even with the accessibility service
    off), `MicForegroundService.kt:56` START_STICKY, `stop()` is never called, the channel is
    IMPORTANCE_MIN. Play: an FGS must be started by the user, perceptible, stoppable, and run
    only as long as the task. Fix:
    - Route (`MicrophoneRouter.kt:53-66`): on API 30+ with the accessibility service connected,
      record directly in the service's process (RECORD_NOW). If the first ~300 ms come back
      silenced (`isClientSilenced`) or all zeros, or AudioRecord refuses to start, fall back to
      the proxy activity - keep the words already captured if possible. API 28/29: as today.
    - The FGS: start it at the start of a recording (best effort - if Android refuses the start
      from the background, the recording goes on directly), stop it when the recording ends
      (`stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf`), START_NOT_STICKY. Its
      notification on a channel of low importance: "Recording…" / "Aufnahme läuft…" with a
      Stop action that stops the dictation (immutable PendingIntent to a receiver or the
      service; not exported).
    - Never start it from `MainActivity` any more; remove `ensureMicServiceRunning` and the
      "ready" notification and its channel (delete the old channels on upgrade). Check every
      text that talks about the ready notification (Reliability row, setup, strings_home.xml:32
      "Only for the silent 'Embersay is ready' notification") and reword them: notifications
      now show while recording and carry the messages of item 17.
    - Tests in `:core` for the routing decision (API level, service connected, silenced/zero
      probe -> proxy).
16. **MicProxyActivity** stays only as that fallback (item 15). Make sure nothing else routes
    to it first. Document in the code why it exists.
17. **Toasts vanish when notifications are off.** AOSP drops a non-system toast from a
    background app whose notifications are disabled (default on Android 13+). Affected:
    `DictationController.kt:2025-2033` (`toast`, `notice`), `:2080-2085` (`fail`),
    `TextInserter.kt:106-111`, `AmethystAccessibilityService.kt:495`, `QuickActionRunner`.
    Fix: one `Messages`/`Say` path that shows a short message as a small
    `TYPE_ACCESSIBILITY_OVERLAY` pill near the bubble (or near the bottom when there is no
    bubble) while the accessibility service is connected - styled like the app, max two lines,
    ~3.5 s (longer for longer text), not focusable or touchable, respects "remove animations",
    announced for accessibility (`announceForAccessibility` or a live region). Fall back to a
    Toast only when the service is not connected. `ScreenAwake` shows how the service adds a
    window.

## Phase 3 - honest store texts and privacy

18. **No privacy policy in the app** (Play blocker). Add a "Privacy policy" row in About
    (`AboutScreen.kt`) that opens a page with the policy text in English and German (string
    resources or a raw text resource per locale, from `docs/PRIVACY.md`, kept in sync), plus a
    `PRIVACY_URL` constant (empty for now; when set, the row also offers "Open on the web").
19. **Untrue listing claims** (Play blocker). `fastlane/metadata/android/{en-US,de-DE}/
    full_description.txt`: "Nothing leaves the phone" also covers Android's speech recognition
    (which may send audio to its maker - `EngineTrust.kt:16`, PRIVACY.md); "No keyboard switch,
    no clipboard" (the clipboard is a fallback: `InsertionEngine.kt:314-318`). Rewrite truthfully
    in both languages; check the short descriptions and changelogs too.
20. **The accessibility disclosure mixes other data.** `strings_setup.xml:21`
    (`disclosure_nothing_stored`, and its German) also covers the recording, the cleanup, the
    24-hour history. Play: the accessibility disclosure "cannot be included with other
    disclosures pertaining to personal or sensitive data collection". Keep that screen to what
    the service reads and does (sees focused fields and the keyboard's position, inserts text,
    reads a field's text only at the moment of insertion, never password fields, nothing it
    reads leaves the phone, the app name stays on the phone for statistics/history); move the
    recording and cleanup sentences to the microphone step or the welcome.
21. **PRIVACY.md does not match the code.** "It does nothing else in other apps" (it starts
    activities: `AmethystAccessibilityService.kt:311-318`, `QuickActionRunner.kt:150`,
    `QuickStyleActivity`; and pastes through the clipboard); the Tinfoil verifier's Sigstore and
    GitHub fetches are missing under other connections; scope "as published at GitHub" -> also
    Google Play and the website; Diagnostics naming apps (item 22); a placeholder line for the
    controller and contact e-mail that Fabi fills in (mark it clearly, not a fake address).
22. **A shared Diagnostics report names every app dictated into.**
    `core/.../dictation/DictationLog.kt:219` (` · <targetPackage>`), `Anonymize.kt:9-11`,
    `TextInserter.kt:80,93` notes. Fix: in the copied/shared report replace package names with
    "an app" consistently (keep a stable per-report alias like "app 1", "app 2" so the report
    still shows "same app" patterns); the on-screen list in the app may keep names. Tests.
23. **Quick setup may send the voice to Google without saying so** (A20e, OPEN.md). Where
    `EngineAdvice.recommend` falls back to ANDROID and `PhoneFacts.androidOnDevice` is false,
    the welcome must not promise "stays on this phone", and the done step / Home must say that
    Android's recognition may send the voice to its maker, with a way to choose another. Also
    the Settings list's "on this phone" badge for Android's preset must follow
    `androidOnDevice`, not the static trust level. (Measuring Parakeet on 3 GB stays open.)
24. **The clipboard keeps a pasted dictation.** `InsertionEngine.kt:179-183`: after a verified
    PASTE, clear the clip if it is still ours (`OwnClipboard`) - `clearPrimaryClip()` on API 28+.
    Leave a clip that is the user-facing fallback ("copied, paste it yourself") alone.
25. **Key-to-host binding gaps.** `Servers.kt:146` (`KeyBinding.allows` true when unbound):
    bind on first send; bind scheme+host (an https key does not go over http to the same host).
    Tests.
26. **Cleartext allowed app-wide.** `res/xml/network_security_config.xml`: add domain-configs
    with `cleartextTrafficPermitted="false"` for the known service hosts (api.groq.com,
    api.openai.com, Tinfoil hosts, api.anthropic.com, github.com, api.github.com,
    huggingface.co and the model CDN hosts actually used - check the code). The base stays
    cleartext-permitted for the user's own server on the LAN (CleartextGuard keeps it local).
27. **Importing a Whisper model.** `WhisperModelStore.import` (`WhisperModelStore.kt:111-122`):
    check the ggml/gguf magic and the header fields whisper.cpp reads (vocab size, dims, layer
    counts within sane bounds), a total size cap, refuse a truncated file; a note next to the
    import action "Only import models from sources you trust" (EN/DE). Tests for the header
    check in `:core`.
28. **Old Amber keys left in a stored address.** `AmberAddress.movePathKeys`
    (`AmberAddress.kt:69`) skips a server without a stored catalog: split on the key shape alone.
29. **The update check breaks silently on a repo rename.** `GithubReleases` with
    `followRedirects(false)`: follow a 301 from api.github.com to api.github.com only (same
    host, https), or log the failure plainly. Keep the release URL check.

## Phase 4 - polish and less frustration

30. **One tap destroys things.** Model delete (`EngineDetailScreen.kt:647,701` ->
    `MainActivity.kt:999,1005`), History "Delete all" (`HistoryScreen.kt:113`), "Clear
    diagnostics" (`TestAndDiagnostics.kt:246`), "Remove <app>" (`AppStyle.kt:203`). Fix: a
    confirm dialog (the existing AlertDialog pattern) for model delete and history delete; for
    the model in use say that Embersay can't dictate with it until it is downloaded again. A
    Snackbar with Undo for removing an app style and clearing diagnostics.
31. **Rotation, split screen, font size or process death throw you back to Home.**
    `MainActivity.kt:476` back stack is `remember`; scroll positions lost (`SettingsScreen.kt:551`
    `rememberScrollState` inside `AnimatedContent` without a `SaveableStateHolder`). Fix: a
    `Saver` for the screen stack (`rememberSaveable`), `rememberSaveableStateHolder()` around
    each entry, the stack/deep-link rule (~483-492) as a small tested `:core` `Navigation`.
32. **The keyboard can cover fields; content under the cutout in landscape.**
    `MainActivity.kt:638-643` pads only status and navigation bars. Fix: the root uses
    `WindowInsets.safeDrawing` (incl. IME and cutout); check pages that already add
    `imePadding` (PracticeStep, QuickStyleActivity) don't double-pad.
33. **Home's failure reason disappears after 3.5 s** (`FAILED_DISPLAY_MS`). Keep the last
    failure line on Home under "Try again" until the next tap or leaving Home (the bubble may
    keep its own timing).
34. **Dragging the bubble can trigger Back.** No `systemGestureExclusionRects` in `bubble/`.
    Fix: set the bubble's rect (API 29+) after each layout/position change.
35. **SET_TEXT flattens rich text and splices onto placeholders.** `InsertionEngine.kt:159,
    291-297`, `TextInserter.kt:311-331,368-371`. Fix: when the node's text is a `Spanned` with
    spans, or the class is not an EditText, prefer PASTE over SET_TEXT (when the clipboard is
    allowed); treat text equal to `node.hintText` as empty. Decision logic in `:core`, tested.
36. **In-app navigation only cross-fades; no predictive back.** Forward = slide ~30 dp + fade,
    Back = the reverse; `PredictiveBackHandler` drives a scale/translate of the current page with
    the gesture, then commits or settles back. Respect "remove animations".
37. **Orb press feedback, progress line.** `DictateOrb.kt:97-105` (`indication = null`): scale
    to ~0.94 on press (spring) + a haptic CONFIRM on start; `Role.Button`. `ProgressLine(null)`
    draws a static 35 % bar and Home passes 0f for unknown size: an animated indeterminate line
    and `progressSemantics()`.
38. **Touch targets under 48 dp.** `IconAction` 44 dp (Back, gear), the introduction's
    `MoreBelow` 32 dp, `ChoiceChip` ~36 dp: 48 dp boxes, glyph size unchanged.
39. **Texts.** `home_paused` "Paused. Tap to send." / `quick_done_paused` -> "insert" (EN/DE);
    `problem_key_rejected` names the service and the fix; German one-fragment strings end
    without a full stop where English has none (`values-de/strings_setup.xml` :30 :39 :57 :71
    :138 :139 :166, `strings_home.xml` `setup_subtitle`, `setup_microphone_note`), "Hört zu." /
    "Schreibt …" get a subject per the glossary; `check_strings.py` flags a trailing "." on a
    one-fragment DE string whose EN has none. Top bar title (`MainActivity.kt:1036`):
    `maxLines = 2`, ellipsis, `heading()` semantics.
40. **The bubble and quick menu ignore display-size / font-size changes until reconnect.**
    `QuickMenuView.kt:62,79`, `BubbleController.kt:359-366`: rebuild sizes in `screenChanged()`.
    Android 9/10 landscape: `BubbleController.kt:957-961` fallback safe area - read
    `rootWindowInsets` for the side navigation bar and cutout.
41. **Cheap accessibility semantics** (TalkBack itself is not a goal): a polite live region for
    Home's status ("Listening", "Inserted", failures), a content description for the bubble,
    roles for `ChoiceChip` (selected) and `IconAction`.
42. **Still open from OPEN.md:** grey quick-menu buttons only buzz -> a short reason per slot
    from `QuickActions` ("Only in another app", "No dictation yet", "Tap into a text field
    first", "No cleanup set up"), shown with item 17's message; "All set" while the model still
    downloads -> say it downloads; Groq's page "Settings complete." beside a Save button and
    autosave -> one way (autosave, no Save button) like the other pages; a tap on a recognition
    row switches at once -> keep the switch, add a Snackbar with Undo; bare HTTP codes in
    toasts -> the plain reason first (the code may follow in brackets, as already done for some);
    one icon with three meanings (History, introduction, Try again) -> distinct icons;
    "Tinfoil (Enclave)" on Home above the "Verified enclave" badge -> "Tinfoil"; model suffixes
    ("Whisper base", "Voxtral Small 24B") per the glossary; "Per app" header over a single row;
    the German Reliability banner wraps.

## Phase 5 - optimization

43. **The model is never freed under memory pressure on Android 14+.** `AmethystApp.kt:89-95`
    reacts only to RUNNING_CRITICAL / COMPLETE, which API 34+ no longer delivers. Fix: after
    each dictation check `ActivityManager.MemoryInfo` (`lowMemory`, or `availMem` against the
    loaded model's size with a margin) or `isLowRamDevice()`; release at once or after ~30 s
    when tight; keep the trim handler for <= 13 and also react to `TRIM_MEMORY_UI_HIDDEN` /
    `BACKGROUND` where it makes sense. The decision in `:core`, tested.
44. **The secret store can be built on the main thread.** `SecretStore.kt:22,39`; callers
    `MainActivity.kt:595, 777, 946-948`, `ConnectionTester.kt:114`, `QuickStyleActivity.kt:87`.
    Fix: warm it on IO in `MainActivity.onCreate` / `QuickStyleActivity.onCreate`; reads that
    can still race it go through a suspend accessor.
45. **No baseline profile for the app's code.** A hand-written `app/src/main/baseline-prof.txt`
    covering `app/amethyst/**` startup paths (MainActivity, Compose screens, service connect,
    bubble). profileinstaller is already in.
46. **Play bundle packaging.** `app/build.gradle.kts:165-166` `useLegacyPackaging = true`:
    keep it for the APKs (reproducible F-Droid/GitHub builds), set it false for the release
    bundle (a Gradle property or the `bundle` path). `ndk { debugSymbolLevel = "SYMBOL_TABLE" }`
    for the bundle.
47. **Small costs.** Settings decoded twice per change (`AmethystApp.kt:128,137`, `.first()` at
    `:217,:414`): one `loadedSettings` StateFlow, derive the rest. Statistics parsed fully at
    start and summarized in composition (`StatsStore.kt`, `MainActivity.kt:513-514`): summarize
    on Default in a flow. Polling loops (`MainActivity.kt:555-570`) only while STARTED.
    `AndroidAppSource.label` uncached in composition (`AppStyles.kt:139`, `SettingsScreen.kt:442`):
    an LruCache. `refreshSetup()` binder calls on resume: off main. `BubbleView.settleAlpha`
    restarts its animator at 50 Hz: skip when the target is unchanged. Download clients derive
    from the shared OkHttp client (`newBuilder()`).
48. **Whisper downloads start over after a break.** `ModelDownloads.resumes` is Parakeet-only;
    `FileDownload` already resumes by Range - let Whisper downloads use it.

## Phase 6 - code health and CI

49. **CI skips checks.** `.github/workflows/android.yml`: add `python3 -I tools/check_strings.py`
    and `./gradlew :app:testDebugUnitTest`; lint blocking with a baseline file
    (`abortOnError = true`, `baseline = file("lint-baseline.xml")`). Fix the cheap lint
    warnings first (InlinedApi, DataExtractionRules, ApplySharedPref, UsableSpace,
    SwitchIntDef, ObsoleteSdkInt, UnusedResources).
50. **Untested `:app` decisions that broke in rc2/rc3.** Move to `:core` with tests: the
    app-in-front rule (`AmethystAccessibilityService.kt:219-233`: own overlay, SystemUI,
    keyboard, launcher) as `AppInFront`; the fallback notice choice (`tookOverFromServer`
    ~1424-1431, `recognizeElsewhere` ~1561-1575) as a `FallbackNotice` value worded by
    `Texts.kt`.
51. **Dead code.** `StreamingSttEngine` (`core/.../engine/SttEngine.kt:31`),
    `TrustLevel.badgeExplanation()` (`ui/theme/AmberTheme.kt:172`) and its `trust_went_*`
    strings, the plurals `summary_apps`. `!!` on `chunked`/`ready` (`DictationController.kt`
    ~1157-1226) -> smart-cast locals.
52. **Repo hygiene.** Remove the stale agent worktrees under `.claude/worktrees/` and their
    `worktree-agent-*` branches (all patch-identical to `amber` or already merged in shape);
    move `reports/` and `research_notes/` into `.gitignore`.

## Phase 7 - store material (partly Fabi's)

53. Screenshots: re-shoot on the Pixel from this branch - the bubble over a messaging app with
    text going in, the quick menu, the welcome, Home after a dictation (text in the field), the
    speech recognition list, Privacy/History; captions; RGB, at most 2:1. A feature graphic
    1024x500; the 512 icon as a full square.
54. Console texts: accessibility declaration, foreground service declaration, Data safety
    answers ("encrypted in transit": No while http to an own server is allowed; voice and
    cleanup text "collected" for app functionality, not shared, not ephemeral), reviewer
    access notes, video scripts. Into `docs/PLAY-CONSOLE.md`.
55. Fabi: the closed test (12 testers, 14 days) if the account is from after 2023-11-13; the
    domain and imprint; the controller's name and e-mail for the privacy policy.

## After the release

Split `DictationController` (RunContext, recognition pipeline, delivery with a `:core`
`DeliveryPlan`, cleanup step, `CommandRunner` with a no-op while voice commands are hidden);
split `AmethystScreens`; a smaller onnxruntime build; Tinfoil as an on-demand module for Play;
Compose BOM update and Material 3 Expressive springs; native inference in an isolated process;
replace `security-crypto`; live text at the bubble; a "bubble gone?" self-check for OEMs that
disconnect accessibility services; multi-display support.

## Phase 8 - found on the emulator (Android 16, x86_64 with arm64 translation, 2026-11-23)

56. **Quick setup picks Android's recognition without its language pack** (high). Where no
    local model fits (the emulator: 4 GB, no dot product), `FirstRunSetup` plans ANDROID
    ("first run: ANDROID now, nothing later, nothing to fetch"), but the on-device recognizer
    has no offline pack for the phone's language: every dictation fails with "This language
    isn't installed for offline use", the done step still says "All set", and the practice
    step and the bubble's message offer no way out (only Home has "Get language pack", which
    works: Android shows its own 62 MB download dialog). Fix: on API 33+ ask the recognizer
    (`checkRecognitionSupport`) whether the language is installed when Quick setup picks
    ANDROID; if not, trigger the download right then (`triggerModelDownload`, as Home's
    button does) and let the done step say the language pack is still needed / downloading
    instead of "All set"; the message in the practice step and over other apps names the way
    out ("... Open Embersay to get the language pack." EN/DE, within the 70-character rule).
    Decision logic in `:core` with tests (FirstRun/EngineAdvice).
57. **The privacy policy names old paths.** "Settings → Statistics" and "Settings → History"
    are now "Settings → Privacy → Statistics / History" - in `docs/PRIVACY.md` and both raw
    texts (EN, DE). Check every other path the policy names against the app.
58. **Reliability shows twice in Settings** - the banner on top ("Reliability: 1 step left")
    and the row in the Embersay group. Show the banner only, or the row's status only - one
    place (the earlier rule was "the row on top only where the Reliability row is hidden").
59. **Long text pages in landscape** - the privacy policy (and other text pages: licenses,
    diagnostics) run across the full 2400 px width; give the content a readable max width
    (~640 dp, centered) on wide windows. The policy page loses its scroll position on
    rotation: keep it (rememberSaveable / the stack's state holder).
60. **Diagnostics: "Text zeigen" wraps to two lines** in German beside the time and badge;
    let the row wrap the action below or shorten the label (EN/DE).
