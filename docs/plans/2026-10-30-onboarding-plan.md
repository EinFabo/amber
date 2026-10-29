# Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A fresh install walks from "what Amber does" through permissions, an engine with its download, and the settings that matter, to a first successful dictation - without opening Settings.

**Architecture:** The rules (which steps, their order, the engine advice, the download wording, the done flag) live in `:core` and are unit-tested. `:app` adds a `Screen.Onboarding` at the root of `MainActivity`'s back stack and one small composable per step, built from the existing components (`RadioRow`, `StatusRow`, `ReliabilityRows`, the bubble preview). A dictation started while the active engine's download runs is refused with a notice instead of a failure.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), kotlinx.serialization (settings JSON in DataStore), JUnit via `kotlin.test` in `:core`, Robolectric `StoreShots` for screenshots, JNI (whisper.cpp).

**Spec:** [`docs/plans/2026-10-29-onboarding-design.md`](2026-10-29-onboarding-design.md) - read it first; this plan does not repeat the step texts.

## Global Constraints

- Every user-visible string is English (CLAUDE.md); the chat with Fabi is German.
- Logic that can go wrong goes in `:core` (pure JVM, tested); `:app` only what needs Android.
- No Claude attribution in commits (no `Co-Authored-By`, no `Claude-Session`).
- The word "model" does not appear in the engine step; it may appear in the download notice ("Model downloading - 45 %"), as the spec fixes that text.
- Advice thresholds (spec): Parakeet at RAM ≥ 6 GB; FUTO 244 at RAM ≥ 3 GB with a Whisper-capable CPU; storage margin 300 MB above the download size.
- Fresh installs only: `onboardingDone` defaults to false, the migration from schema < 8 sets it true.
- Android's recognizer never stands in for a downloading engine.
- Commits: the repo commits once per version (`Amber 0.18.0: ...`). Commit only after Fabi has seen the result and says so.

## Review Focus

1. **Settings not loaded yet.** `Graph.settings` starts as `AmethystSettings.defaults()` (onboardingDone = false) until DataStore answers - without a guard every cold start flashes the flow. Expect: nothing but the background until the first real settings arrive. → Task 6 adds `Graph.settingsLoaded` and a StoreShots-free check on the device (Task 10).
2. **Coming back from system settings.** Steps 2, 3 and 9 send the user to Android's screens; the activity may be recreated. Expect: the same step, refreshed ticks. → Task 6 keeps the step in `rememberSaveable`; Task 10 rotates and returns on the device.
3. **Cleanup turned off again on the replay.** With cleanup on, APP_STYLES is in the list; the user goes back to CLEANUP and picks Off. Expect: the step after CLEANUP is BUBBLE and the count shrinks. → `OnboardingTest` (Task 2) pins it.
4. **A download that fails or lacks storage on the engine step.** Expect: the store's message, "Try again", and the next smaller option; a dictation afterwards gets today's "Download model" remedy, not the downloading notice. → `DownloadNoticeTest` (Task 4) pins "failed is not downloading"; Task 8's step shows the retry.
5. **Mobile data.** Metered network: nothing downloads without consent; "Wait for Wi-Fi" starts by itself on an unmetered network. → `DownloadGateTest` (Task 4) pins the decision; Task 10 checks it on the device.

---

### Task 1: Settings schema 8 - `onboardingDone`

**Files:**
- Modify: `core/src/main/kotlin/app/amethyst/core/settings/AmethystSettings.kt` (field, `SCHEMA_VERSION`, the schema list in the KDoc, `SettingsCodec.migrate`)
- Test: `core/src/test/kotlin/app/amethyst/core/SettingsTest.kt`

**Interfaces:**
- Produces: `AmethystSettings.onboardingDone: Boolean = false`; `AmethystSettings.SCHEMA_VERSION = 8`.

- [ ] **Step 1: Write the failing tests** in `SettingsTest`:

```kotlin
@Test
fun `a fresh install has not seen the introduction`() {
    assertFalse(AmethystSettings.defaults().onboardingDone)
    assertFalse(SettingsCodec.decode("").onboardingDone)
}

@Test
fun `settings from before version 8 count as introduced`() {
    val old = """{"activeEngineId":"android-on-device","engines":[],"schemaVersion":7}"""
    val migrated = SettingsCodec.decode(old)
    assertTrue(migrated.onboardingDone)
    assertEquals(8, migrated.schemaVersion)
}

@Test
fun `version 8 settings keep their flag`() {
    val pending = SettingsCodec.encode(AmethystSettings.defaults())
    assertFalse(SettingsCodec.decode(pending).onboardingDone)
}
```

- [ ] **Step 2:** Run `./gradlew :core:test --tests 'app.amethyst.core.SettingsTest'` - expect compile failure (`onboardingDone` unknown).
- [ ] **Step 3:** Add the field after `fallback` with a KDoc ("Whether the introduction ran; a fresh install starts without, see SettingsCodec.migrate"), bump `SCHEMA_VERSION` to 8, add "8: the introduction - settings from before it count as introduced." to the schema list, and in `migrate` set `onboardingDone = settings.onboardingDone || settings.schemaVersion < 8` in the returned copy.
- [ ] **Step 4:** Run `./gradlew :core:test` - all pass (existing migration tests included).

### Task 2: `Onboarding` - steps, order, position

**Files:**
- Create: `core/src/main/kotlin/app/amethyst/core/onboarding/Onboarding.kt`
- Test: `core/src/test/kotlin/app/amethyst/core/OnboardingTest.kt`

**Interfaces:**
- Consumes: `AmethystSettings.cleanup` (null = off).
- Produces:
  ```kotlin
  enum class OnboardingStep { WELCOME, ACCESSIBILITY, MICROPHONE, ENGINE, CLEANUP, APP_STYLES, BUBBLE, RECORDING, RELIABILITY, DONE }
  object Onboarding {
      fun steps(settings: AmethystSettings): List<OnboardingStep>
      fun next(current: OnboardingStep, settings: AmethystSettings): OnboardingStep?
      fun previous(current: OnboardingStep, settings: AmethystSettings): OnboardingStep?
      /** 1-based position and total, for "Step 4 of 9". */
      fun position(current: OnboardingStep, settings: AmethystSettings): Pair<Int, Int>
      /** "Skip" from CLEANUP on. */
      fun skippable(step: OnboardingStep): Boolean
  }
  ```

- [ ] **Step 1: Write the failing tests:**
  - `without cleanup the styles step is left out` - `steps(defaults())` equals the enum minus `APP_STYLES` (9 entries); `position(ENGINE, defaults()) == 4 to 9`.
  - `with cleanup the styles step follows it` - settings with `cleanup = CleanupChoice(EnginePresets.TINFOIL.id, "deepseek-v4-1-flash")`: `next(CLEANUP, s) == APP_STYLES`, `position(DONE, s) == 10 to 10`.
  - `turning cleanup off again skips the styles step` - `next(CLEANUP, defaults()) == BUBBLE`, `previous(BUBBLE, defaults()) == CLEANUP`.
  - `the ends have no neighbour` - `previous(WELCOME, defaults()) == null`, `next(DONE, defaults()) == null`.
  - `skip is offered from the cleanup on` - `skippable(MICROPHONE)` false, `skippable(CLEANUP)` and `skippable(RELIABILITY)` true, `skippable(DONE)` false.
- [ ] **Step 2:** Run `./gradlew :core:test --tests 'app.amethyst.core.OnboardingTest'` - fails (unresolved).
- [ ] **Step 3:** Implement in `Onboarding.kt`: `steps` filters `APP_STYLES` when `settings.cleanup == null`; `next`/`previous`/`position` index into `steps(settings)`. If `current` is not in the list (APP_STYLES after cleanup was turned off), `next` returns the first step after it in enum order that is in the list, `previous` the last one before it.
- [ ] **Step 4:** Run the test - passes.

### Task 3: `EngineAdvice` - the recommendation for this phone

**Files:**
- Create: `core/src/main/kotlin/app/amethyst/core/engine/EngineAdvice.kt`
- Test: `core/src/test/kotlin/app/amethyst/core/EngineAdviceTest.kt`

**Interfaces:**
- Consumes: `ParakeetModel.TOTAL_BYTES`, `WhisperModels.FUTO_MULTILINGUAL_244` / `FUTO_MULTILINGUAL_74` / `ACFT_LARGE_V3_TURBO_Q8` (`sizeBytes`), `EnginePresets.LOCAL_PARAKEET` / `LOCAL_WHISPER` / `ANDROID_ON_DEVICE`.
- Produces:
  ```kotlin
  data class PhoneFacts(val totalRamMb: Long, val freeStorageMb: Long, val whisperCpu: Boolean, val androidOnDevice: Boolean)
  enum class EngineOption(val presetId: String, val whisperModelId: String?) {
      PARAKEET, ACFT_LARGE, FUTO_244, FUTO_74, ANDROID, SERVER;
      val downloadBytes: Long   // 0 for ANDROID and SERVER
  }
  data class Advice(val recommended: EngineOption, val others: List<EngineOption>)
  object EngineAdvice {
      const val PARAKEET_MIN_RAM_MB = 6_000L
      const val WHISPER_MIN_RAM_MB = 3_000L
      const val STORAGE_MARGIN_MB = 300L
      fun recommend(phone: PhoneFacts): Advice
      fun fits(option: EngineOption, phone: PhoneFacts): Boolean
  }
  ```
  `presetId`: PARAKEET → `local-parakeet`, ACFT_LARGE/FUTO_244/FUTO_74 → `local-whisper` with the spec's model id, ANDROID → `android-on-device`, SERVER → `""` (opens Settings).

- [ ] **Step 1: Write the failing tests** (a `phone(ram, free, whisperCpu = true, onDevice = true)` helper):
  - `a phone with room gets parakeet` - `recommend(phone(8_000, 4_000)).recommended == PARAKEET`.
  - `just under six gigabytes gets futo 244` - `phone(5_900, 4_000)` → `FUTO_244`.
  - `parakeet needs its download plus the margin` - free = 670 + 299 MB → not `PARAKEET`; 670 + 300 → `PARAKEET` (use `ParakeetModel.TOTAL_BYTES / 1_000_000`).
  - `no whisper cpu means no whisper option` - `phone(4_000, 4_000, whisperCpu = false)` → `ANDROID`; `others` contains no `FUTO_*`/`ACFT_LARGE`.
  - `a small phone with little room gets futo 74` - `phone(2_000, 500)` → `FUTO_74`.
  - `nothing fits` - `phone(2_000, 50)` → `ANDROID`.
  - `the others list what fits, android and the server last` - for `phone(8_000, 4_000)`: `others == listOf(ACFT_LARGE, FUTO_244, FUTO_74, ANDROID, SERVER)`; the recommendation never appears in `others`.
- [ ] **Step 2:** Run `./gradlew :core:test --tests 'app.amethyst.core.EngineAdviceTest'` - fails.
- [ ] **Step 3:** Implement: `fits` checks storage (`downloadBytes / 1_000_000 + STORAGE_MARGIN_MB <= freeStorageMb`), RAM per option (PARAKEET and ACFT_LARGE ≥ 6 GB, FUTO_244 ≥ 3 GB, FUTO_74 none) and `whisperCpu` for the three Whisper options; ANDROID and SERVER always fit. `recommend` takes the first of PARAKEET, FUTO_244, FUTO_74, ANDROID that fits; `others` = [ACFT_LARGE, PARAKEET, FUTO_244, FUTO_74] that fit, minus the recommendation, then ANDROID (unless recommended), then SERVER.
- [ ] **Step 4:** Run the test - passes.

### Task 4: Download notice and the Wi-Fi decision

**Files:**
- Create: `core/src/main/kotlin/app/amethyst/core/engine/ModelDownload.kt`
- Test: `core/src/test/kotlin/app/amethyst/core/DownloadNoticeTest.kt`, `core/src/test/kotlin/app/amethyst/core/DownloadGateTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  /** A download in progress for the active engine; failed or absent downloads are not one. */
  sealed interface ModelDownload {
      data class Running(val received: Long, val total: Long) : ModelDownload
      data object WaitingForWifi : ModelDownload
  }
  object DownloadNotice {
      fun short(download: ModelDownload): String     // "Model downloading - 45 %" / "Waiting for Wi-Fi"
      fun atStart(download: ModelDownload): String   // short + ". Amber is ready when it is done."
  }
  enum class GateDecision { START, ASK, WAIT }
  object DownloadGate {
      fun decide(metered: Boolean, mobileDataAllowed: Boolean, waitingForWifi: Boolean): GateDecision
  }
  ```

- [ ] **Step 1: Write the failing tests:**
  - `the percentage is floored and never 100 while running` - `Running(45, 100)` → `"Model downloading - 45 %"`; `Running(999, 1000)` → `"Model downloading - 99 %"`; `Running(0, 0)` → `"Model downloading - 0 %"`.
  - `waiting for wifi says so` - `short(WaitingForWifi) == "Waiting for Wi-Fi"`; `atStart(WaitingForWifi) == "Waiting for Wi-Fi. Amber is ready when it is done."`.
  - Gate: unmetered → `START` (whatever the flags); metered + `mobileDataAllowed` → `START`; metered + `waitingForWifi` → `WAIT`; metered and neither → `ASK`.
- [ ] **Step 2:** Run both tests - fail.
- [ ] **Step 3:** Implement; percentage = `(received * 100 / total).coerceIn(0, 99)` with `total <= 0` → 0.
- [ ] **Step 4:** Run `./gradlew :core:test` - all pass.

### Task 5: Phone facts, the Wi-Fi gate and the "downloading" check in `:app`

**Files:**
- Modify: `app/src/main/cpp/whisper_jni.cpp` (export `cpuSupported`), `app/src/main/kotlin/app/amethyst/engine/WhisperNative.kt`
- Create: `app/src/main/kotlin/app/amethyst/engine/ModelDownloads.kt`
- Modify: `app/src/main/kotlin/app/amethyst/ui/Setup.kt` (`phoneFacts`), `app/src/main/kotlin/app/amethyst/AmethystApp.kt` (Graph), `app/src/main/kotlin/app/amethyst/dictation/DictationController.kt` (`start`)

**Interfaces:**
- Consumes: Task 3 `PhoneFacts`, `EngineOption`; Task 4 `ModelDownload`, `DownloadNotice`, `DownloadGate`.
- Produces:
  - `WhisperNative.cpuSupported(): Boolean` (false when `loadError != null`), JNI `Java_app_amethyst_engine_WhisperNative_cpuSupported` returning `cpu_supported()`.
  - `Setup.phoneFacts(context: Context): PhoneFacts` (`ActivityManager.MemoryInfo.totalMem`, `File(context.noBackupFilesDir, "models").apply { mkdirs() }.usableSpace`, `WhisperNative.cpuSupported()`, `AndroidOnDeviceEngine.onDeviceSpeech`).
  - `class ModelDownloads(context, parakeet: ParakeetModelStore, whisper: WhisperModelStore)` in Graph as `graph.modelDownloads`, with:
    - `fun start(option: EngineOption, mobileDataAllowed: Boolean)` - asks `DownloadGate.decide` with the current network (`ConnectivityManager.getNetworkCapabilities(activeNetwork)`, `NET_CAPABILITY_NOT_METERED`); `START` → `parakeet.download()` / `whisper.download(spec)`; `WAIT` → remembers the option and registers a default-network callback that starts it once the network is unmetered (unregisters after); `ASK` is answered by the UI, which calls again with `mobileDataAllowed = true` or `waitForWifi()`.
    - `fun waitForWifi(option: EngineOption)`
    - `fun forEngine(config: EngineConfig): ModelDownload?` - `LOCAL_SHERPA` + `ParakeetState.Busy` → `Running`; `WHISPER_CPP` + `SlotState.Busy` for `config.modelId ?: WhisperModels.default.id` → `Running(received, total ?: 0)`; the waiting option for that engine → `WaitingForWifi`; anything else → null.
    - `val waiting: StateFlow<EngineOption?>`
  - `DictationController.start`: first thing after `if (isRunning) return` - `graph.settings.value.activeEngine?.let(graph.modelDownloads::forEngine)?.let { notice(DownloadNotice.atStart(it)); return }` where `notice(text)` posts a toast like `fail` does but leaves the state `Idle`, logs nothing to `dictationLog`, and gives a short haptic-free return.

- [ ] **Step 1:** Add the JNI export and `external fun cpuSupported(): Boolean`; wrap it as `fun cpuSupported(): Boolean = loadError == null && runCatching { cpuSupportedNative() }.getOrDefault(false)` (name the external `cpuSupportedNative`, JNI symbol `..._cpuSupportedNative`).
- [ ] **Step 2:** Add `Setup.phoneFacts`, `ModelDownloads`, the Graph field (`by lazy`, after `parakeetModels`), and the check in `DictationController.start`.
- [ ] **Step 3:** Run `tools/typecheck-android.sh` - passes for the non-Compose sources. Run `python3 -I tools/check_refs.py` - OK.
- [ ] **Step 4:** Run `./gradlew :app:assembleDebug` - builds (native included).

### Task 6: The frame - `Screen.Onboarding`, steps 1-3, no flash on start

**Files:**
- Create: `app/src/main/kotlin/app/amethyst/ui/onboarding/OnboardingScreen.kt` (frame), `app/src/main/kotlin/app/amethyst/ui/onboarding/Steps.kt` (step composables), `app/src/main/kotlin/app/amethyst/ui/SetupPieces.kt`
- Modify: `app/src/main/kotlin/app/amethyst/ui/MainActivity.kt`, `app/src/main/kotlin/app/amethyst/ui/screen/HomeScreen.kt` (use the moved pieces), `app/src/main/kotlin/app/amethyst/AmethystApp.kt` (`settingsLoaded`)
- Test: `app/src/test/kotlin/app/amethyst/ui/StoreShots.kt`

**Interfaces:**
- Consumes: Task 1 `onboardingDone`; Task 2 `Onboarding`, `OnboardingStep`; `SetupState`, `SetupActions`.
- Produces:
  - `SetupPieces.kt`: `AccessibilityDisclosure(onAgree: () -> Unit)` (the M6 text from `R.string.accessibility_description` on the page, button "I agree - open settings") and `RestrictedSettingsHint(onOpenAppInfo: () -> Unit)` - moved from `HomeScreen`, which keeps its dialog by calling them.
  - `OnboardingScreen(step: OnboardingStep, settings: AmethystSettings, setup: SetupState, actions: SetupActions, onStep: (OnboardingStep) -> Unit, onFinish: () -> Unit, content: OnboardingContent)` - draws "Step n of m" (from `Onboarding.position`), "Skip" when `Onboarding.skippable(step)`, the step body, and a bottom `PrimaryButton` ("Continue"; "Set up" on WELCOME; "Try it" on DONE). Back/Skip/Continue call `onStep(Onboarding.previous/next(...))`; `next == null` → `onFinish()`.
  - `OnboardingContent` - a class holding the callbacks steps 4-10 need (filled in Tasks 8-9; start it with what steps 1-3 need).
  - `Graph.settingsLoaded: StateFlow<Boolean>` - `settingsStore.settings.map { true }.stateIn(appScope, SharingStarted.Eagerly, false)`.
  - `MainActivity`: `Screen.Onboarding` as a new `Screen` (title: none - the frame draws its own line, `TopBar` hidden for it); the stack starts as `listOf(if (onboardingDone) Home else Onboarding)` once `settingsLoaded` is true - before that only `AmberBackground` is drawn. The current step is `rememberSaveable { mutableStateOf(OnboardingStep.WELCOME.name) }`. `onFinish` writes `onboardingDone = true` and replaces the stack with `[Home]`.

- [ ] **Step 1:** Move the disclosure and `RestrictedSettingsHint` into `SetupPieces.kt`; `HomeScreen` uses them unchanged in behaviour.
- [ ] **Step 2:** Write the frame and steps WELCOME, ACCESSIBILITY, MICROPHONE with the spec's texts (*The flow* → 1-3). ACCESSIBILITY shows a tick and "Continue" when `setup.accessibilityEnabled`, otherwise the disclosure plus "Not now"; MICROPHONE calls `actions.requestMicrophone`, and after a denial shows "Open app settings" (`actions.openAppInfo`).
- [ ] **Step 3:** Wire `MainActivity` (settingsLoaded guard, `Screen.Onboarding`, saved step, `BackHandler` going to `Onboarding.previous`, finishing).
- [ ] **Step 4:** Add StoreShots `x-onboarding-1-welcome`, `x-onboarding-2-accessibility`, `x-onboarding-3-microphone`; run `./gradlew :app:testDebugUnitTest --tests 'app.amethyst.ui.StoreShots'` and look at the three PNGs in `app/build/store-shots/`: one decision per screen, nothing cut off at 360 dp.

### Task 7: Home while downloading

**Files:**
- Modify: `app/src/main/kotlin/app/amethyst/ui/screen/HomeScreen.kt` (`download: ModelDownload?` parameter, `StatusLine` Idle text), `app/src/main/kotlin/app/amethyst/ui/MainActivity.kt` (pass `graph.modelDownloads.forEngine(active)` - collect the Parakeet and Whisper state flows so it updates)
- Test: `StoreShots` (`x-home-downloading`)

**Interfaces:**
- Consumes: Task 4 `ModelDownload`, `DownloadNotice.short`; Task 5 `ModelDownloads.forEngine`.

- [ ] **Step 1:** In `StatusLine`, `DictationState.Idle` with a non-null `download` shows `DownloadNotice.short(download)` above a `ProgressLine(fraction)` (null fraction for `WaitingForWifi`) instead of "Tap and speak ...".
- [ ] **Step 2:** Pass the download from `MainActivity`; update both existing `HomeScreen` calls in StoreShots (`download = null`) and add `x-home-downloading` with `Running(300_000_000, 670_000_000)`.
- [ ] **Step 3:** Run the StoreShots and look at `x-home-downloading.png`: "Model downloading - 44 %" with the line under the flame.

### Task 8: Step 4 - how Amber hears you

**Files:**
- Modify: `app/src/main/kotlin/app/amethyst/ui/onboarding/Steps.kt`, `OnboardingScreen.kt` (`OnboardingContent`), `MainActivity.kt`
- Test: `StoreShots` (`x-onboarding-4-engine`, `x-onboarding-4-engine-downloading`, `x-onboarding-4-engine-metered`)

**Interfaces:**
- Consumes: Task 3 `EngineAdvice`, `EngineOption`, `PhoneFacts`; Task 5 `Setup.phoneFacts`, `ModelDownloads.start/waitForWifi/waiting`; `ParakeetState`, `SlotState`.
- Produces: `OnboardingContent` gains `phone: PhoneFacts`, `metered: Boolean`, `onChooseEngine(option: EngineOption, mobileDataAllowed: Boolean)`, `onWaitForWifi(option: EngineOption)`, `onOpenRecognition()`, the Parakeet state and the Whisper states.
  `onChooseEngine` (in `MainActivity`): add the engine if missing (`EngineConfig.fromPreset(preset, EngineConfig.defaultLanguageTag(kind))`, `modelId = option.whisperModelId` for Whisper), set `activeEngineId`, call `graph.modelDownloads.start(option, mobileDataAllowed)`. `SERVER` → `onOpenRecognition()` pushes `Screen.Page(SettingsPage.RECOGNITION)` on top of the flow.

- [ ] **Step 1:** Write the step: the recommendation card (name, `TrustBadge`, size in MB, wait text: PARAKEET "text about 1 s after you stop", FUTO_244 "about 3 s", FUTO_74 "about 1 s, less exact", ACFT_LARGE "a few seconds", ANDROID "no download"), the button by network ("Download" / "Download now - 670 MB of mobile data" + "Wait for Wi-Fi"), progress and "You can go on - it keeps downloading." once running, the failure message with "Try again" and the next fitting smaller option on failure, and "Other options" expanding `advice.others` as `RadioRow`s with the same facts. "Continue" appears once a download runs, waits, is done, or ANDROID is chosen.
- [ ] **Step 2:** Wire it in `MainActivity` (phone facts read once per flow via `remember`, metered from `ConnectivityManager`).
- [ ] **Step 3:** Add the three StoreShots and look at them: no "model" on the card, the card fits at 360 dp, the metered variant has both buttons.

### Task 9: Steps 5-10 and "Show the introduction again"

**Files:**
- Modify: `Steps.kt`, `OnboardingScreen.kt`, `MainActivity.kt`, `app/src/main/kotlin/app/amethyst/ui/screen/SettingsScreen.kt` (make `BubbleSizeRow` and `BubblePreview` internal; the replay row)
- Test: `StoreShots` (`x-onboarding-5-cleanup`, `-6-styles`, `-7-bubble`, `-8-recording`, `-9-reliability`, `-10-done`, `x-settings` with the new row)

**Interfaces:**
- Consumes: `CleanupChoice`, `EnginePresets.TINFOIL`, `AppProfiles.DEFAULTS`, `AppSource`, `ReliabilityRows`, `BubbleSizeRow`, `RadioRow`, `cleanupSummary()`, `fallbackSummary()`.
- Produces: `OnboardingContent` gains `apps: AppSource`, `onUpdateSettings`, `onSaveKey(alias: String, key: String)`, `hasKeyFor(alias: String?)`; `SettingsScreen` gains `onReplayIntroduction: () -> Unit` (row "Show the introduction again" at the end of the AMBER group; sets `onboardingDone = false` and resets the stack to `[Onboarding]` at WELCOME).

- [ ] **Step 1: CLEANUP** - radio rows Off / Tinfoil; Tinfoil shows the masked key field and the "Get a key at tinfoil.sh" link; "Continue" with a key adds the Tinfoil engine if missing (key saved under its `apiKeyAlias`) and sets `cleanup = CleanupChoice(EnginePresets.TINFOIL.id, "deepseek-v4-1-flash")`; without a key it keeps cleanup off and says "Cleanup stays off until a key is entered." The leaving-the-phone hint as on the Cleanup page.
- [ ] **Step 2: APP_STYLES** - `AppProfiles.DEFAULTS` filtered to installed packages (`apps.all()`), a switch per row; off removes the profile from `settings.profiles`.
- [ ] **Step 3: BUBBLE, RECORDING, RELIABILITY** - per the spec; Skip keeps the current value and never resets one (the replay case).
- [ ] **Step 4: DONE** - the summary lines with their Settings paths (recognition name, `cleanupSummary() ?: "Cleanup off"`, the bubble choice, "Everything else - Settings, top right on the home screen"), the download progress when running, "Try it" → `onFinish()`, Home with the test field focused.
- [ ] **Step 5:** The replay row in `SettingsScreen` and its wiring.
- [ ] **Step 6:** Add the StoreShots and look at each: one decision per screen, defaults selected, Skip only from step 5.
- [ ] **Step 7:** Run the full check: `./gradlew :core:test`, `python3 -I tools/check_refs.py`, `tools/typecheck-android.sh`, `./gradlew :app:lintDebug :app:assembleDebug` - all pass.

### Task 10: On the phone, version, list

**Files:**
- Modify: `app/build.gradle.kts` (versionCode +1, versionName `0.18.0`), `docs/OPEN.md` (item 5 done with what was checked)

- [ ] **Step 1:** Pixel 9a: uninstall, install the debug build. Go through the flow on Wi-Fi with Parakeet; tap the flame while it downloads (toast, no red bubble, Home shows the percentage); dictate in the test field after it finishes. Rotate on step 2 and come back from the accessibility settings: same step, ticked. Cold-start the app five times after finishing: no flash of the flow. Replay from Settings; Skip keeps the settings. If a phone on mobile data is at hand: "Wait for Wi-Fi" starts by itself on Wi-Fi.
- [ ] **Step 2:** Bump the version, tick item 5 in `docs/OPEN.md` with the device results.
- [ ] **Step 3:** Show Fabi the StoreShots and the device results; commit only on his OK:

```bash
git add -A
git commit -m "Amber 0.18.0: an introduction from install to the first dictation"
```
