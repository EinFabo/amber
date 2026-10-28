# Amber - open items

As of 2026-10-28, after 0.9.1. Urgent things at the top, later stages at the bottom.

## In progress: bubble and keyboard

- [ ] **Set up wireless debugging**, so the keyboard measurements can be read live with
      `adb logcat -s Amber:V`. Pairing needs: pairing IP:port + 6-digit code, and the
      connection IP:port.
- [x] **Short dictations lost** (0.9.1): with push to talk the user already speaks while the
      VAD calibrates, so the noise floor became the voice and a short "yes" never crossed the
      threshold - discarded without a word. Floor is now the quietest calibration frame (capped),
      calibration-time speech counts, a manual stop accepts 120 ms, and discards are reported.
- [x] **Random drops** (0.6.2): Android briefly stopped reporting the keyboard window (device:
      "settled at 1242" after 66 ms, "closed" after 156 ms with the field still focused). Only
      250 ms without a report now count as "closed".
- [ ] **Bubble starts clearly after the keyboard, jumps instead of gliding** (feedback on
      0.6.1). Decide with live logs; candidates:
  - Follow the keyboard frame by frame instead of jumping at the end (the readings during the
    animation are its real, moving position).
  - Smoother motion: don't move the overlay window per frame with `updateViewLayout`; animate
    the bubble with `translationY` inside a briefly enlarged window instead.

## Tinfoil models (0.9.1)

- [x] **Try every Tinfoil model** (Fabi, 2026-10-28): the Tinfoil engine screen picks the speech
      model (whisper-large-v3-turbo, voxtral-small-24b) and an optional cleanup model
      (deepseek-v4-1-flash suggested; gpt-oss-120b, llama3-3-70b, gemma4-31b, glm-5-3, kimi-k3).
      Cleanup runs over the same verified, pinned channel (SecureChannel), treats the dictation
      as data, and falls back to the raw text on error, timeout (12 s) or an implausible reply.
- [ ] **Live streaming with voxtral-mini-4b-realtime**: a streaming engine over
      `wss://inference.tinfoil.sh/v1/realtime` (PCM16 16 kHz, push-to-talk), pinned like the rest.
- [ ] **Confirm on the device**: voxtral-small-24b accepts our requests (a prompt is dropped and
      retried without if refused); cleanup quality and added latency per model.

## Next: recognize while speaking

- [x] **Chunked recognition for longer dictations** (0.8.0, Fabi's idea). While the user is
      still speaking, every finished chunk is recognized in the background; after release only
      the last chunk is left. Built as planned:
  - Cut only at real pauses (VadPolicy already finds them), never mid-word.
  - Pass the previous chunk's text as context/prompt, so sentence flow and spellings hold
    across the cuts.
  - Detect the language once on the first chunk, then keep it.
  - Logic (cut points, joining, spaces at the seams) in `:core`, with tests.
  - Applies to local Whisper and cloud engines; Tinfoil can additionally do true streaming
    (`voxtral-mini-4b-realtime`).
  - Minimum chunk: 4 s for the FUTO models, 20 s for stock Whisper (large-v3-turbo encodes a
    full 30 s window per chunk, so short chunks would multiply its work), 6 s for cloud.
- [ ] **Confirm chunking on the device**: a ~20-30 s dictation through the bubble; diagnostics
      should say "recognition … after stop, N chunks" with a short wait.

## Confirm on the device

- [ ] **Speech processing time, bubble vs. app.** Starting point: ~5× as long through the
      bubble. Measured with 0.6.1, same sentence, ~5.8 s recording: bubble 1.5 s (model loaded,
      `/foreground`, boost on) - app 0.9 s *including* model load (`/top-app`). Better, but still
      over twice as long. The remaining gap is the CPU group. Next step if wanted: keep the
      invisible proxy activity in front during recognition, so Whisper runs as the top app
      (cost: the keyboard blinks briefly) - as a setting.
- [ ] **Multilingual**: English speech stays English with German+English selected.
- [ ] **Design 0.6**: does it read as modern and plain, or still "AI-ish"?
- [ ] **Tinfoil on the device**: "Verify now" on the engine screen, then a dictation with an API
      key; diagnostics should say "enclave verified" and name the enclave.

## Housekeeping

- [x] **Committed** (0.6.2) - before, everything since the rename lived only in the working
      tree; native code and Whisper files were not even tracked.
- [ ] **Release signing** with an own keystore instead of the debug key (more pressing now that
      Tinfoil promises the audio only goes to a verified enclave). Needs one uninstall; models
      have to be downloaded again.
- [ ] **Screenshots and icon**: a PNG icon for installers that cannot read the vector icon, and
      2-3 screenshots.
- [x] **CI**: `tools/typecheck-android.sh` fetches the compiler's runtime dependency
      (`kotlinx-coroutines-core-jvm` 1.8.0) and runs again.
- [x] **`docs/PLAN.md`** marked as historical.
- [x] **English everywhere** (0.7.1): app texts, docs, tool output; rule in `CLAUDE.md`.
- [x] **Fire design** (0.9.0): from the design lab Fabi picked Ember for rest, listening,
      failure and appear/hide, and Fuse for transcribing and done; the mic glyph became a flame.
      Bubble, home orb, launcher (incl. Android 13 themed) and notification icons.
- [ ] **Fire design on the device**: spark height and density, the fuse lap speed, the flame
      glyph at 44-96 dp; check that taps right above the bubble reach the app (spark window).
- [ ] Optional: change package/application id from `app.amethyst` to Amber (means a reinstall).

## Later stages

- [ ] Local models Parakeet TDT and Moonshine through sherpa-onnx
- [ ] ElevenLabs Scribe v2 Realtime (streaming)
- [x] **Tinfoil address** (0.6.3): the per-model hosts `*.inf.tinfoil.sh` are gone, everything
      goes through the router `https://inference.tinfoil.sh/v1`. Preset and stored engines
      migrated. Models there: `whisper-large-v3-turbo`, `voxtral-small-24b`,
      `voxtral-mini-4b-realtime` (live streaming).
- [x] **Tinfoil with verified enclave attestation (`ENCLAVE_VERIFIED`)** (0.7.0):
      `tinfoil-go/mobile` bound with gomobile (`tools/build-tinfoil-aar.sh`, pinned commit),
      attestation fetched over its own connection, verified against
      `tinfoilsh/confidential-model-router`, upload pinned to the attested TLS key, re-verified
      after `freshness_expires_at` or when the key rotates. Later, optionally: EHBP (HPKE body
      encryption) instead of pinning.
- [ ] Optional: Tinfoil `voxtral-mini-4b-realtime` as a live engine (text appears while you
      speak, `wss://inference.tinfoil.sh/v1/realtime`, PCM16 16 kHz, push-to-talk only).
- [ ] LLM cleanup · command mode on selected text · dictionary and snippets · encrypted
      history · app profiles
