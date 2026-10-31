# Amber

Voice dictation for Android that writes into **any** app - local-first, with swappable speech
recognition engines and a visible trust level on every dictation.

In the spirit of Wispr Flow, but with a different stance: not a black box in the cloud - you
decide per dictation where your voice goes, and the app tells you.

## Install

1. Download `amber-<version>.apk` from the newest entry under
   [Releases](../../releases/latest). Android 9 or newer.
2. Open it on the phone and allow installing from that source once.
3. Open Amber and follow the setup: allow the microphone, turn on the accessibility service.
4. Tap into any text field - the amber bubble appears. Tap it, speak, tap again.

Out of the box Amber recognizes speech **on the phone** (Android's own recognizer). Better
local models (Whisper, Parakeet) can be downloaded inside the app; server engines (Groq,
OpenAI, Tinfoil, your own server) need an API key you enter under *Engine*.

## Where to look

| You want to … | Look at |
|---|---|
| understand what the app does | the next sections of this README |
| know what is planned or broken | [`docs/OPEN.md`](docs/OPEN.md) |
| change logic (engines, text insertion, settings) | `core/` - plain Kotlin, unit-tested |
| change UI, bubble, accessibility, audio | `app/` - the Android side |
| build, test, sign | [Building](#building) and `tools/` |
| see what changed per version | [Releases](../../releases) and `git log` |

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
- **Double-tap** gives a voice command instead - once switched on under Settings → Voice
  commands: "zurück", "öffne WhatsApp", "zehnmal runter", "ganz nach unten", "lauter",
  "Taschenlampe an", "WLAN-Einstellungen", "lösche das letzte Wort", "go home", "swipe left".
  Without a text field one tap is enough, and the bubble then shows in every app. Understood by
  fixed rules on the phone; Amber reads which parts of the screen can scroll and, in the one it
  scrolls, where the items sit - to tell whether it moved, never what they show - and for a text
  command the field you started it in, never a password field. A tap on the bubble stops a
  command that is still running. Settings pages only open; it never switches Wi-Fi or
  Bluetooth, never taps buttons or confirms dialogs - but a swipe does what your own swipe would
  do in that app (in a mail list it may archive a mail). Switched off, Amber never acts for
  you. Android 17's
  Advanced Protection takes the accessibility service - bubble and commands - away from every
  app that is not an accessibility tool, Amber included.

Look: fire, kept minimal. A flat amber disc with one dark glyph - a flame at rest, three level
bars while listening, with a few sparks rising off the disc as loud as you speak ("Ember").
While the text is being written a spark runs around the rim; when it is in, a tick and one
short burst of sparks ("Fuse"). Failure turns the disc red and puts the sparks out. Nothing
moves at rest. The sparks fly in a second overlay window that takes no touches.

The app itself ("Hearth"): the engine and where it sends your voice, the flame, a row with
today's numbers that opens the statistics, and a test field. Settings are a short grouped list, each row an icon and its
state in a few words: recognition, cleanup, bubble and voice commands first, then the app
styles, the rarer pages folded under Advanced (when the server fails, recording, inserting,
reliability), and statistics and diagnostics. Each page starts with what matters; longer
explanations wait behind "Learn more".

**Statistics** are counted on the phone only - words, dictations per app, where the voice
went, the wait after stopping per engine, days in a row, an estimate of the server bill - from
counts and timings, never the text or audio (`noBackupFilesDir/stats/dictations.jsonl`). Time
saved compares typing at 40 words a minute (adjustable) with speaking plus waiting.

## Engines

| Engine | Trust | Status |
|---|---|---|
| Android (on device) | `ON_DEVICE` | ✅ stage 1 |
| Groq, OpenAI, or your own server (Speaches, LocalAI, whisper.cpp) - one shared server backend | `CLOUD` | ✅ stage 2 |
| Tinfoil via `inference.tinfoil.sh`, attestation verified, connection bound to the verified key | `ENCLAVE_VERIFIED` | ✅ 0.7 |
| Whisper locally through whisper.cpp with the FUTO models (Multilingual-74, 82 MB, and more) | `ON_DEVICE` | ✅ 0.2 |
| Whisper large-v3-turbo locally, ACFT fine-tune: the encoder stops at the end of the speech - as accurate as the stock model, about three times faster on short dictations | `ON_DEVICE` | ✅ 0.16 |
| Parakeet TDT 0.6B v3 locally through sherpa-onnx: near Whisper large-v3 accuracy at small-model speed, 25 European languages | `ON_DEVICE` | ✅ 0.10 |

**Recognized while you speak.** Longer dictations are cut at natural pauses, and every
finished part is recognized in the background while you keep talking - each with the text so
far as context, in the language the first part was spoken in. When you stop, only the last
part is left, so the wait no longer grows with the length of the dictation.

**Whisper and languages.** Whisper gets a list of languages (default: German and English) and
detects per dictation which of them was spoken. A single fixed language forces Whisper to
*translate* everything else into it - English speech used to come out German. Several
languages inside one dictation are beyond Whisper; it picks the dominant one.

**One server backend.** Tinfoil, Groq, OpenAI and your own server all go through the same
code: the OpenAI audio endpoint for recognition, the chat endpoint for the cleanup and the
writing style per app. What differs lives in a provider catalog (`ServerProviders`): the
models each one offers, their prices for the cost estimate, and whether the address is fixed.
Known providers get pickers; an own server gets free fields, and `http://` works there for
addresses in your own network only.

**Amber never claims more trust than it can prove.** An enclave engine only shows
`ENCLAVE_VERIFIED` once its attestation has actually been verified. Your voice is never
detoured to a less private place. The one fallback goes the other way: if a server cannot be
reached, a downloaded local model recognizes the recording instead of it being lost - shown
as it happens, and switchable under Engine.

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
        routing, keyboard policy, settings schema, diagnostics log   → unit tests
app/    Android - bubble, accessibility adapter, capture, whisper.cpp JNI, Compose UI
docs/   open items (OPEN.md), the 512 px app icon, and the historical plan, audit and design notes
tools/  build helpers: native AARs, reference and type checks, release signing, git hooks
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
tools/fetch-sherpa-aar.sh       # once: sherpa-onnx (Parakeet runtime), pinned and checksummed
./gradlew :app:assembleDebug    # needs an Android SDK
./gradlew :app:testDebugUnitTest --tests app.amethyst.ui.StoreShots
                                # renders the main screens and the icon PNGs (Robolectric)
                                # into app/build/store-shots - screenshots, layout check

tools/setup-android-sdk.sh      # fetch an SDK and build, if there is none
tools/sign-release.sh           # release APK, signed with the release key
```

`settings.gradle.kts` only includes `:app` when an SDK is found (`ANDROID_HOME`,
`ANDROID_SDK_ROOT` or `sdk.dir` in `local.properties`), or with `-Pamethyst.withApp=true`.
Without an SDK `:core` stays fully buildable instead of breaking the whole build.

Every push builds a debug APK in GitHub Actions and stores it as the `amber-debug-apk`
artifact.

After cloning, enable the repository's git hooks once:

```bash
git config core.hooksPath tools/hooks
```

## Releases

The version lives in `app/build.gradle.kts` (`versionName`, `versionCode`). A release is:

1. Bump both numbers, commit as `Amber <version>: <what changed, in a few words>`.
2. `tools/sign-release.sh` - builds the release APK and has it signed with the release key.
3. Tag `v<version>` and publish a GitHub release with the APK attached as
   `amber-<version>.apk`; the notes say what changed in plain words, and give the APK's
   SHA-256 and the signer's fingerprint (see below), so a download can be checked.

## Verifying a release APK

Release APKs are signed with one key. Its certificate's SHA-256 is:

```
93c826f0c11a444f1c051a82f5cb2b4ec3081e0b43047926afae8c35cdb1bbde
```

Check an APK before installing it, with `apksigner` from the Android build-tools:

```bash
apksigner verify --print-certs amber.apk | grep "certificate SHA-256"
```

It must print exactly this one signer. Android also refuses an update signed with any other
key. A build from this source signed with your own key works the same, but installs as a
separate app.

## Contributing

Commits and pull requests carry the human author only - no AI co-author or session
trailers. The `commit-msg` hook in `tools/hooks` enforces that. Project rules are in
[`CLAUDE.md`](CLAUDE.md): English everywhere, logic that can go wrong belongs in `core`.

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

Command mode on selected text · dictionary and snippets · encrypted history. Open items and their state: [`docs/OPEN.md`](docs/OPEN.md).
