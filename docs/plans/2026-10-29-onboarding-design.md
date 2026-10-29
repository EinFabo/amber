# Onboarding: from install to the first dictation

Design, 2026-10-29. Item 5 of [the terminal-mode plan](2026-10-29-terminal-mode-and-everyday-fixes.md),
widened by Fabi: the introduction also walks through the settings that matter.

## Why

Fabi on the first install: "For normal people the UI must be much more relaxed - how it guides
you on the first install. I didn't even realise I first had to download a model; I thought it did
that in the background." Today a new install lands on Home with a setup card (microphone,
accessibility) and Android's recognizer as the engine; the good local models, the cleanup, the
bubble and the reliability switches are found only by someone who goes looking.

**Done when** a fresh install leads to a first successful dictation without opening Settings, and
nobody has to know what a "model" is.

## Decisions (with Fabi)

- **Who sees it:** fresh installs only. Existing installs are marked done by the settings
  migration. Settings → Amber → "Show the introduction again" replays it at any time.
- **Engine step:** one recommendation for this phone on a large card, "Other options" below.
  The download starts there and keeps going while the remaining steps are done.
- **Settings steps:** the decisions everyone should make once, one per screen, each with a
  sensible default: cleanup (+ app styles when cleanup is on), bubble, ending a recording,
  reliability. Everything else is pointed at on the last screen.
- **While the model downloads**, the chosen engine is already active. Tapping the flame or the
  bubble says "Model downloading - 45 %" instead of failing. Android's recognizer does not stand
  in silently: on some phones it sends the voice to Google.
- **Approach A:** an own flow of lean step screens in `:app`, built from the existing components;
  the logic that can go wrong (which steps, the order, the engine advice, the done flag) in
  `:core`, unit-tested. Rejected: the real settings pages as steps (written for experts - every
  model, every server) and a separate Activity (a second navigation; permissions, the mic service
  and the return from system settings wired twice).

## The flow

One decision per screen. A top line "Step 4 of 9" and, from step 5 on, "Skip" top right (keeps
the step's default). Back goes one step back; the system back gesture does the same, and on the
first step leaves the app as usual. Steps whose condition is not met are left out of the count.

| # | Step | Shown when | Default |
|---|------|-----------|---------|
| 1 | Welcome: what Amber does | always | - |
| 2 | Accessibility service | always | - |
| 3 | Microphone | always | - |
| 4 | How Amber hears you (engine + download) | always | the recommendation |
| 5 | Tidy the text? (cleanup) | always | Off |
| 6 | Style per app | cleanup on | the stock profiles |
| 7 | The bubble | always | only with a text field, 56 dp |
| 8 | Ending a recording | always | stop on silence |
| 9 | Keep Amber ready (reliability) | always | - |
| 10 | All set | always | - |

### 1 Welcome

"Amber turns your voice into text in any app: tap the flame, speak, and the text appears where
your cursor is." Three short lines: works in every app (the bubble), your voice stays on this
phone unless you choose a server, it only listens while you dictate. Button "Set up".

### 2 Accessibility service

What it does in plain words, then the disclosure from the security review (M6:
`R.string.accessibility_description`, the same text as today's dialog) shown on the page, not
in a dialog. Button "I agree - open settings" opens the system screen. Below it the existing
"Greyed out or Restricted setting?" help (`RestrictedSettingsHint`, moved out of `HomeScreen`).
When the user comes back with the service on (`onResume` → `Setup.snapshot`), the step shows a
tick and "Continue". "Not now" moves on; Home then shows today's setup card until it is done.

### 3 Microphone

"Amber records only while you dictate." Button "Allow" → the system prompt; on a grant the mic
foreground service starts (as today in `MainActivity`). Denied: one line on how to allow it in
the app settings, "Open app settings", and "Continue" stays possible.

### 4 How Amber hears you

A large card with the recommendation (see *Engine advice*), e.g.:

> **Recommended for this phone** · Parakeet · ● Never leaves the phone ·
> 670 MB download · text about 1 s after you stop · **[ Download ]**

The word "model" does not appear; the card names the engine and what it costs (size, wait).
On an unmetered network the button reads "Download" and starts at once. On a metered one it
reads "Download now - 670 MB of mobile data", with "Wait for Wi-Fi" beside it, which starts
the download as soon as an unmetered network appears (while Amber's process lives - the mic
service keeps it alive).

Once started, the card shows the progress line and "You can go on - it keeps downloading." and
"Continue" appears. Not enough storage or a failed download: the store's own message, "Try
again", and the next smaller option offered on the card.

"Other options" opens a short list below, each one a row with the same three facts:
- *Most accurate* - Whisper large-v3-turbo (ACFT), 874 MB, a few seconds' wait (only where the
  CPU runs whisper.cpp and storage allows).
- *Smaller* - FUTO 244 (264 MB) or FUTO 74 (82 MB), whichever is not the recommendation.
- *Android's recognizer* - no download; labelled with its real trust (on device, or "sent to
  Google" where the phone has no on-device recognizer).
- *A server with your own key* - opens Settings → Recognition (the expert path) and returns to
  the flow on back.

Picking an option adds the engine if needed (`EngineConfig.fromPreset`, with the chosen
`modelId` for Whisper), makes it active and starts its download.

### 5 Tidy the text?

"A chat model can fix punctuation, filler words and mis-hearings before the text is typed."
Two radio rows: **Off** - text as recognized (default); **Tinfoil** - a private enclave, the
text is checked there and never stored, needs a key. Picking Tinfoil shows the key field
(masked, stored encrypted like today) and a link "Get a key at tinfoil.sh". With a key, the
Tinfoil engine is added if it is missing (`EngineConfig.fromPreset(TINFOIL)`; recognition stays
what step 4 chose) and the recommended cleanup model (DeepSeek V4.1 Flash) is selected. "Continue" with Tinfoil picked but no key
keeps cleanup off and says so. Groq, OpenAI and own servers stay in Settings → Cleanup.

If recognition is local and cleanup is Tinfoil, the existing hint says that the text - not the
recording - leaves the phone.

### 6 Style per app (only with cleanup on)

"Messages casual, mail formal, notes short." The stock profiles (`AppProfiles.DEFAULTS`) as
rows with the app's icon and style, only those installed on this phone; each can be switched
off. "Add more apps any time in Settings → App styles."

### 7 The bubble

The bubble preview (`BubblePreview`, size slider as on the Bubble page) and two radio rows:
**Only when you type** (default, `onlyWithTextField`) / **Always**. One line: "Drag it anywhere;
drop it on the X to hide it."

### 8 Ending a recording

**Stop when I pause** (default, `autoStopOnSilence`) - "about a second of silence ends it" /
**Run until I tap again**.

### 9 Keep Amber ready

The three `ReliabilityRows` (mic service, notifications, unrestricted battery) with their
buttons, under "Optional. Some phones cut the microphone off in the background otherwise."

### 10 All set

What was chosen, each line with where to change it: "Parakeet - Settings → Recognition",
"Cleanup off - Settings → Cleanup", "Bubble only when you type - Settings → Bubble", and one
line "Everything else - Settings, top right on the home screen". If the download is still
running: its progress. Button "Try it" → Home, with the test field focused and the status line
"Tap the flame and say something."

## While the model downloads

The engine the user chose is active from step 4 on. Until its files are complete:

- **Home:** the status line under the flame reads "Model downloading - 45 %" (progress from the
  store's state) instead of "Tap and speak"; the flame stays tappable.
- **Starting a dictation** (flame or bubble) checks first: if the active engine is a local one
  whose download is running (or waiting for Wi-Fi), nothing is recorded. A toast says "Model
  downloading - 45 %. Amber is ready when it is done." (or "Waiting for Wi-Fi to download"); the
  bubble does not turn red and no failed dictation is logged.
- **Not downloading and not there** (cancelled, failed, deleted): today's failure with the
  "Download model" remedy stays.

## Engine advice (`:core`)

A pure function, unit-tested:

```kotlin
EngineAdvice.recommend(phone: PhoneFacts): Advice
data class PhoneFacts(totalRamMb: Long, freeStorageMb: Long, whisperCpu: Boolean, androidOnDevice: Boolean)
data class Advice(recommended: Option, others: List<Option>)
```

Rules, first match wins (margins on top of the download size: 300 MB of storage):
1. RAM ≥ 6 GB and room for Parakeet → **Parakeet** (~700 MB in memory while dictating).
2. Whisper-capable CPU, RAM ≥ 3 GB, room → **FUTO 244**.
3. Whisper-capable CPU, room → **FUTO 74**.
4. Otherwise → **Android's recognizer** (with its true trust).

`others` lists the rest that fit (ACFT large needs RAM ≥ 6 GB, a Whisper CPU and room), then
Android's recognizer, then the server option. Each option carries id, size, a wait text and its
trust. The thresholds are a first guess and live in one place to be tuned on devices.

`PhoneFacts` comes from `:app`: `ActivityManager.MemoryInfo.totalMem`, `usableSpace` of the
model directory, `cpu_supported()` from `whisper_jni.cpp` exposed through JNI, and
`AndroidOnDeviceEngine.onDeviceSpeech`.

## The flow's logic (`:core`)

```kotlin
enum class OnboardingStep { WELCOME, ACCESSIBILITY, MICROPHONE, ENGINE, CLEANUP, APP_STYLES, BUBBLE, RECORDING, RELIABILITY, DONE }
object Onboarding {
    fun steps(settings: AmethystSettings): List<OnboardingStep>  // APP_STYLES only with cleanup
    fun next(current: OnboardingStep, settings: AmethystSettings): OnboardingStep?
    fun previous(current: OnboardingStep, settings: AmethystSettings): OnboardingStep?
    fun position(current: OnboardingStep, settings: AmethystSettings): Pair<Int, Int> // "Step 4 of 9"
}
```

Settings schema 8: `onboardingDone: Boolean = false` on `AmethystSettings`. `defaults()` (a fresh
install) leaves it false; the migration from schema < 8 sets it true, so nobody who already set
Amber up sees the flow. A corrupt settings file falls back to defaults and shows the flow again
- acceptable. "Show the introduction again" sets it false and opens step 1 with the current
settings preselected; "Skip" on the replay never resets a setting.

The current step is kept in a `rememberSaveable` in `:app`, so a trip to the system settings
(steps 2, 3, 9) or a rotation does not lose it. The flow ends by writing `onboardingDone = true`.

## Where the code goes

- `:core` - `onboarding/Onboarding.kt`, `engine/EngineAdvice.kt`, schema 8 in
  `AmethystSettings` + `SettingsCodec.migrate`; tests `OnboardingTest`, `EngineAdviceTest`,
  `SettingsTest` (migration both ways).
- `:app` - `ui/onboarding/OnboardingScreen.kt` (the frame: step line, Skip, Back, Continue) and
  one small composable per step in `ui/onboarding/Steps.kt`; in `MainActivity` the back stack
  starts with a new `Screen.Onboarding` instead of `Screen.Home` while `!settings.onboardingDone`,
  so "A server with your own key" can push Settings → Recognition on top and come back; `PhoneFacts` reading in `ui/Setup.kt`; the
  download gate (metered check, wait for Wi-Fi) in `engine/DownloadGate.kt`; the
  "downloading" check before a dictation in `DictationController.start`; Home's status line;
  "Show the introduction again" in the settings list.
- Shared pieces move rather than copy: the accessibility disclosure and `RestrictedSettingsHint`
  out of `HomeScreen`, `BubbleSizeRow`/`BubblePreview` out of `SettingsScreen` (internal).

## Testing

- `:core` unit tests: steps with and without cleanup, next/previous at the ends, positions;
  engine advice at the thresholds (RAM 5.9/6 GB, storage just short of a model, no Whisper CPU,
  no on-device Android); migration 7 → 8 sets the flag, a fresh `defaults()` does not.
- `StoreShots`: each step rendered (`x-onboarding-<n>`), looked at like the settings pages.
- `check_refs`, `lintDebug`, `assembleDebug`, `typecheck-android.sh` for the non-Compose parts
  (`DownloadGate`, `Setup`).
- On the Pixel 9a: uninstall, install, go through the flow with the Parakeet download on Wi-Fi,
  dictate in the test field before and after the download finishes; replay from Settings.

## Out of scope

- Server engines other than Tinfoil in the flow (Groq, OpenAI, own server stay in Settings).
- A download that survives the process being killed (WorkManager); Parakeet resumes file by file
  on the next start, Whisper starts over - as today.
- Language choice: Parakeet detects the language itself, Whisper keeps "de,en".
- Translating the app; it stays English.
