# What is still missing (2026-11-06, at 0.26.3)

Answer to Fabi's "which features are still missing?". A snapshot, not a task prompt: the
open list stays `docs/OPEN.md`. Sizes: **S** = a day or less, **M** = a few days, **L** = a
week or more / mostly outside the code.

Each item says where it comes from:
- **[code]** checked in the source on 2026-11-06 (file named),
- **[docs]** taken from `docs/OPEN.md` or a plan, not re-checked in code,
- **[session]** decided or asked for in this session, nothing in the repo yet.

Already done and not missing any more (checked): Amber PC / Amber Server as own servers
(`docs/AMBER-PC.md`, 0.22.0) and languages + the phone's own cleanup + lists for them
(`docs/AMBER-PC-2.md`, 0.25.0 - `OpenAiCompatibleEngine.AMBER_CLEANUP`, `AmberServerRequestTest`);
the ready local fallback (`docs/READY-FALLBACK.md`, 0.26.0, device test passed); release signing
with an own key (README fingerprint). OPEN.md still lists "Local models Parakeet TDT" and
"Release signing with an own keystore" as open under Later stages / Housekeeping - both stale.

Planned elsewhere, only referenced here: the **quick menu on a long-press of the bubble** and the
**new bubble behaviour** (separate plans by other sessions).

## 1. Must - before a public Play Store / Tinfoil release

1. **A license** - S (decision). No `LICENSE` file [code: repo root]. Blocks F-Droid, IzzyOnDroid,
   the Tinfoil outreach and any "open source" claim on the website. whisper.cpp (MIT),
   sherpa-onnx (Apache-2.0), tinfoil-go (check) must fit. OPEN.md "F-Droid release".
2. **Rename to "Amber Voice" in visible places** - S/M. `app_name` is "Amber"
   [code: `res/values/strings.xml:3`], README title "# Amber" [code]; also the accessibility
   label, notification channels, introduction and toasts that say "Amber". Package `app.amber`
   and namespace `app.amethyst` stay [session].
3. **Privacy policy page + website on ambervoice.org** - M. No privacy policy anywhere in the repo
   or README [code]. Play's data safety form and the AccessibilityService declaration need a
   public policy URL; the site is also where Tinfoil and users land [session].
4. **Languages follow the phone** - S. New engines default to `"de-DE"` and the first-run engine
   is Android on-device with `"de-DE"` [code: `AmethystSettings.kt:55,256`]; Whisper defaults to
   `"de,en"` (`:248`). An English phone dictates German out of the box. OPEN.md "Best UX".
5. **Play readiness** - L (mostly outside the code). OPEN.md "Google Play - would it help?":
   developer account (also settles Google's developer verification for sideloads, 2026/27),
   closed test 12 testers x 14 days, AccessibilityService declaration with a video of the
   disclosure (M6 exists), microphone foreground-service declaration
   [code: manifest `foregroundServiceType="microphone"`], data safety form. `targetSdk = 35`
   [code: `app/build.gradle.kts:21`] - check Play's current minimum (likely 36 since 2026-08).
6. **Security leftovers a reviewer will find** - M. [code] Kotlin still 2.1.20
   (`gradle/libs.versions.toml:3`, CVE per OPEN M3), AGP 8.9.2, Compose BOM 2024.09;
   `security-crypto 1.1.0-alpha06` (deprecated) still backs `data/SecretStore.kt`; package names
   in release logcat (`DictationController.kt:1077`, no `BuildConfig.DEBUG` guard anywhere = L17).
   [docs] dependency verification metadata (M3), M4b, L8 run token, L11 isolatedProcess.
7. **GitHub releases built by CI** - M. Releases with the GitHub key exist (v0.26.2 and v0.26.3,
   built and signed by hand with `tools/sign-release.sh`; OPEN.md still says the first one is
   open). CI only builds and tests, it publishes nothing [code: `.github/workflows/android.yml`];
   M1 (build from tag, provenance) is open [docs].
8. *(Dropped - Fabi, 2026-11-06: not wanted; built once and taken out again.)* **The bubble is silent for TalkBack** - S/M. No contentDescription, state or actions in
   `app/.../bubble/` [code: no hit]. A dictation app for low vision should not need sight; Play's
   accessibility review may ask too. OPEN.md "Devices that are still in use".
9. **Tinfoil outreach package** - S (non-code) [session]. Needs 1-3 first: license, public repo,
   site; plus a short page on how the enclave path works (attestation, pinned TLS key) from the
   README.

## 2. Should - the everyday safety net and known bugs

- **The cleanup keeps spoken self-corrections** ("nein, obwohl, stopp, mach mit ...") - S/M.
  `ChatCleanup.BASE_PROMPT` only says "false starts" [code: `core/.../text/ChatCleanup.kt:133`];
  add a worked example and a test case; engine of the reported case unknown. OPEN.md 2026-11-02.
- **History of the last dictations** (copy, insert again, auto-delete) - M. Nothing stored past
  the in-memory diagnostics [code: no history in `AmethystSettings`/`:core`]. OPEN.md "Quality of life".
- **Keep a failed recording + "Recognize again" / "Use the local model" in the toast** - M.
  No such action [code: no hit]. OPEN.md "Quality of life", "Best UX".
- **Undo the last insertion** - M. Not built [code: no hit]. OPEN.md "Quality of life".
- **Downloads that survive the process** (user-initiated data transfer job, Android 14+) - M.
  No job/WorkManager [code: no hit]. OPEN.md "Needs".
- **Two Ambers at once** (debug + release both on) - S. `Setup.isAccessibilityEnabled` checks only
  the own component [code: `ui/Setup.kt:99`]; warn on Home/Diagnostics. OPEN.md 2026-11-01.
- **Per-app own instruction skips the word test** (`ChatCleanup.plausible`) - S. [docs] OPEN.md.
- **Bubble starts after the keyboard and jumps** instead of gliding - M. [docs] OPEN.md top
  (needs live logs; wireless debugging still not set up).
- **Cleartext belt and braces**: base-config allows http app-wide [code:
  `res/xml/network_security_config.xml`]; an OkHttp interceptor refusing http to public hosts - S.
- **Battery leftovers**: preload on focus up to 874 MB, `isKeyboard()` per window change - S each.
  [docs] OPEN.md "Battery".
- **Own words** (names/terms) - M. `vocabularyHint` is plumbed in `:core` but nothing in `:app`
  sets it [code: only `SttEngine.kt:74`, `OpenAiCompatibleEngine.kt:86`]; no UI.
- **Spoken punctuation in dictation** ("Komma", "neuer Absatz") - M. Only "neue Zeile" as a voice
  *command* exists [code: `VoiceCommands.kt:913`], not as a dictation rule. OPEN.md "Quality of life".
- **Optional start/stop sound** - S. None [code: no ToneGenerator/SoundPool]. OPEN.md "Best UX".

## 3. Could - new features and later stages

- **Venice.ai as a plain cloud provider** - S [session]. Not in `EnginePresets`/`ServerProviders`
  [code: no hit]. OpenAI-compatible `/audio/transcriptions`; trust CLOUD (no TEE for speech).
- **QR-code scan of an own server's address** (Amber PC's Phone page) - M [docs + code: no
  camera/barcode dependency]. Pick ML Kit code scanner (Play services, not F-Droid-clean) or a
  ZXing-based one. OPEN.md 2026-11-01.
- **Custom voice commands** - M/L, scope unknown: ask Fabi first [code: none; OPEN.md says so].
- **The bubble only after the keyboard's mic key (Gboard)** - S spike first, then M. Nothing built
  [code: no mic-key hook]. OPEN.md 2026-11-01; ties in with the new bubble behaviour plan.
- **Quick Settings tile / notification action** to dictate without the bubble - S/M [code: none].
- **Style rules per chat** (WhatsApp -> Mum) - L. Not built [code: no chat-title reader]. OPEN.md item 6.
- **Terminal mode** (speech -> shell command, never run) - M. Not built [code]. OPEN.md item 7.
- **Cleanup on the phone** (fully local pipeline; steps 1-3, Gemini Nano) - L. [docs] OPEN.md.
- **Live streaming** (Tinfoil voxtral-mini-4b-realtime; ElevenLabs preset is dead code,
  `EngineFactory.kt:144`) - L. **Voxtral translations** (dictate German, get English) - M.
- **Opus uploads for Groq/OpenAI/Tinfoil** - S/M. [docs] `docs/AMBER-PC.md` "later step".
- **Whisper encodes twice with a language list** - M (whisper.cpp patch). [docs] OPEN.md perf.
- **German UI (`values-de`)** - M. Only `res/values` exists [code].
- **Devices**: armeabi-v7a (`abiFilters += "arm64-v8a"` [code]), Whisper without dot-product,
  tablets/landscape width, per-maker battery steps - M each. [docs]
- **F-Droid**: sherpa/onnxruntime from source or a flavor without Parakeet, gomobile in the
  recipe, fastlane metadata (no `fastlane/` [code]), anti-features, reproducible build - L.
  IzzyOnDroid from a GitHub release is the quicker first step [docs].
- **A way without the accessibility service** (voice IME / RecognitionService) - L, only if
  Android 17 Advanced Protection or sideload hurdles cost users [docs].
- Voice commands in more languages; "tap <label>" like Voice Access - L. Review leftovers of
  0.19-0.21 (many small items) [docs].

## 4. Device tests still open (all [docs], from `docs/OPEN.md`)

Ordered by what a public release leans on most:

1. **Onboarding** on a fresh install (Wi-Fi + Parakeet, rotation, cold starts, replay, "Wait for
   Wi-Fi") - item 5.
2. **Introduction return paths** on the Pixel and one OEM phone, restricted-settings path on a
   sideloaded install.
3. **Update check** with a GitHub-signed build (needs Must 7).
4. **Pasting in WhatsApp** after the 0.16.2 `commitText` fix - item 4.
5. **Voice commands** v1/v2, German commands heard as German, combos ("alles markieren und
   kopieren", "zweimal zurück und dann öffne WhatsApp").
6. **Amber PC / Server pickers** offline and after a cold start, "upload Ogg Opus N KB" against a
   PC that lists opus.
7. **Per-app cleanup** (`docs/plans/2026-10-30-per-app-cleanup-design.md` "Device test") and per-app
   style (casual WhatsApp vs. formal Gmail).
8. **Tinfoil**: "Verify now", enclave badge, Voxtral Small 24B, cleanup injection test.
9. **Parakeet on the device** (download, short and 30 s dictation) and the memory-based advice.
10. **Bubble / fire design checklist 0.9.1** (sparks, taps above the bubble, battery saver, icons),
    bubble field avoidance confirmed by screen recording, a normal day's battery share.
