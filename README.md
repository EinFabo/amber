# Amber

Voice dictation for Android that writes into **any** app - local-first, with swappable speech
recognition engines and a visible trust level on every dictation.

In the spirit of Wispr Flow, but with a different stance: not a black box in the cloud - you
decide per dictation where your voice goes, and the app tells you.

## How it works

A **bubble** appears as soon as you tap into a text field and disappears afterwards. Tap, speak,
done: the text is typed straight into the field at the cursor. No keyboard switch, no
clipboard.

The bubble is a `TYPE_ACCESSIBILITY_OVERLAY` of the accessibility service - which is why Amber
needs **no** "display over other apps" and **no** `SYSTEM_ALERT_WINDOW`.

Controls:

- **Tap** starts a dictation; tap again to stop and send it.
- **Hold** dictates while your finger stays down; lifting it sends.
- **Drag** moves the bubble at any time, even mid-dictation. It stays where you drop it and
  steps above an open keyboard.

Look: a flat amber disc with one dark glyph - a microphone, three level bars while listening,
a small arc while writing, a tick when the text is in.

## Engines

| Engine | Trust | Status |
|---|---|---|
| Android (on device) | `ON_DEVICE` | ✅ stage 1 |
| Any OpenAI-compatible endpoint - own server (Speaches, whisper.cpp), Groq, Fireworks, OpenAI | `CLOUD` | ✅ stage 2 |
| Tinfoil via `inference.tinfoil.sh`, attestation verified, connection bound to the verified key | `ENCLAVE_VERIFIED` | ✅ 0.7 |
| Whisper locally through whisper.cpp with the FUTO models (Multilingual-74, 82 MB, and more) | `ON_DEVICE` | ✅ 0.2 |
| Local models: Parakeet TDT, Moonshine (sherpa-onnx) | `ON_DEVICE` | ⏳ later |
| ElevenLabs Scribe v2 Realtime (streaming) | `CLOUD` | ⏳ stage 4 |

**Whisper and languages.** Whisper gets a list of languages (default: German and English) and
detects per dictation which of them was spoken. A single fixed language forces Whisper to
*translate* everything else into it - English speech used to come out German. Several
languages inside one dictation are beyond Whisper; it picks the dominant one.

One driver serves every OpenAI-compatible service: base URL, model name and key are settings,
not code. Adding a provider means filling in three fields.

**Amber never claims more trust than it can prove.** An enclave engine only shows
`ENCLAVE_VERIFIED` once its attestation has actually been verified. There is never a silent
fallback to another engine: if the chosen engine cannot do it, you get an error message, not a
detour for your voice.

### Tinfoil: what "Enclave verified" means

Tinfoil runs its models in confidential VMs (Intel TDX, AMD SEV-SNP) behind a router that is
itself an attested enclave. Before sending, Amber verifies the router with Tinfoil's own
verifier (`tinfoil-go/mobile`, bound with gomobile as `app/libs/tinfoil-mobile.aar`): the
hardware quote up to the chip vendor's root, the signed release of
`tinfoilsh/confidential-model-router` in the Sigstore transparency log, matching
measurements, a fresh attestation. The recording then only travels over a connection bound to
exactly the TLS key from the attestation (certificate pinning). If verification fails,
**nothing** is sent - no silent fallback to plain HTTPS. The engine screen shows under
"Enclave verification" exactly what was proven.

## Layout

```
core/   pure Kotlin/JVM - engine layer, text splicing, insertion chain, VAD, microphone
        routing, keyboard policy, settings schema, diagnostics log   → 173 tests
app/    Android - bubble, accessibility adapter, capture, whisper.cpp JNI, Compose UI
```

The split is not cosmetic but the project's ground rule: **everything that can go wrong
belongs in `core`.** There it runs without an Android SDK or a device, so it is testable.
`app` keeps only what strictly needs a platform API - finding the focused node, `AudioRecord`,
the overlay window.

Two real bugs surfaced that way before the first build: the cursor was set before checking
whether the write had landed at all, and an empty text field (`text == null`) counted as
unreadable - dictating into an empty field would never have worked.

### Inserting into other apps

`ACTION_SET_TEXT` gives the best result but is ignored by WebViews, games and some
cross-platform toolkits - sometimes while reporting success. Hence:

1. **COMMIT_TEXT** - from Android 13 the service types through its own input connection, like
   a keyboard. At the cursor, with undo, in WebViews too.
2. **SET_TEXT** - read the field, splice at the cursor, write back, **read again**.
3. **PASTE** - clipboard + `ACTION_PASTE`, also verified.
4. **CLIPBOARD_ONLY** - copy and tell you.

Steps 3 and 4 only run with the "Clipboard as a fallback" setting (default: off). Otherwise
the clipboard stays untouched, and a dictation no field accepts is kept word for word in the
diagnostics.

What worked in which app is remembered. You see one stumble per app, never a recurring one.

### Whisper through the bubble as fast as in the app

Android schedules a keyboard app like the foreground app while it computes
(`BIND_SCHEDULE_LIKE_TOP_APP`), but not an accessibility service. Through the bubble Whisper
therefore ran on small cores at low clock, and recognition took several times longer. Three
countermeasures:

1. **Persistent compute threads.** whisper.cpp otherwise creates new threads for every compute
   step, several hundred per dictation. A new thread has no load history and lands on a small
   core. `app/src/main/cpp/patch-whisper.cmake` gives whisper.cpp a persistent thread pool that
   sleeps between dictations.
2. **High thread priority** for the Whisper thread; the pool inherits it.
3. **Performance hint session (ADPF, Android 12+)** across all compute threads while a
   recognition runs: Android raises clocks and prefers big cores.

The diagnostics show per dictation the CPU group, priority, pool size and whether the boost
ran.

### Microphone in the background

Android 14/15 forbid starting a microphone foreground service from the background - yet the
bubble is tapped while another app is in front. Amber takes two routes:

1. `MicForegroundService` (type `microphone`) is started **in the foreground**, during
   onboarding, and stays alive. A later tap is then not a background start.
2. If a vendor ROM refuses anyway, `MicProxyActivity` steps in - a 1×1-pixel transparent
   window that is in the foreground by definition.

Route 2 kicks in automatically when route 1 fails.

## Building

```bash
./gradlew :core:test            # runs anywhere, no Android SDK
python3 -I tools/check_refs.py  # imports, R.* ids, manifest classes
tools/build-tinfoil-aar.sh      # once: Tinfoil's verifier as an AAR (needs Go + NDK)
./gradlew :app:assembleDebug    # needs an Android SDK

tools/setup-android-sdk.sh      # fetch an SDK and build, if there is none
```

`settings.gradle.kts` only includes `:app` when an SDK is found (`ANDROID_HOME`,
`ANDROID_SDK_ROOT` or `sdk.dir` in `local.properties`), or with `-Pamethyst.withApp=true`.
Without an SDK `:core` stays fully buildable instead of breaking the whole build.

Every push builds a debug APK in GitHub Actions and stores it as the `amber-debug-apk`
artifact.

## Checking on a device

```bash
adb install -r app-debug.apk
adb logcat -s Amber:V     # states, chosen insertion strategy, latencies, keyboard, Tinfoil
```

The order is deliberate - it isolates failures instead of mixing them:

1. Open the app, allow the microphone.
2. **Test dictation** in the app's test field. Amber is in the foreground here, where
   microphone access is never refused, and the target is an ordinary input field. If this
   works, microphone, engine, normalization and insertion are fine.
3. Turn on the accessibility service → the bubble appears and can be dragged.
4. Bubble in WhatsApp, Chrome and Gmail. The **diagnostics** show per dictation the engine,
   latency, insertion method and target app.
5. Add your own engine → **"Test connection"** shows the latency without you having to speak.
6. Reboot without opening the app, tap the bubble → the proxy activity has to step in. This
   is where vendor ROMs are most likely to get in the way.

If step 2 works and step 4 does not, the problem is insertion, not recognition. If step 2
already fails, it is the microphone or the engine - and the diagnostics say which.

## When something is in the way

The bubble can be switched off in the settings without touching the accessibility service -
the test field keeps working. A dictation also has a hard upper limit, so a hanging
recognizer or a silent server cannot keep the bubble spinning forever.

## Not in yet

LLM cleanup · command mode on selected text · dictionary and snippets · encrypted history ·
app profiles. Open items and their state: [`docs/OPEN.md`](docs/OPEN.md).
