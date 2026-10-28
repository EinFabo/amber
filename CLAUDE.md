# Amber – project rules

## Language: English, everywhere

Everything that lands in this project is written in English:

- app text: UI labels, hints, error and toast messages, notifications, accessibility strings
- diagnostics and log lines, tool and script output
- store listing texts on publish (summary, description, changelog)
- docs (`README.md`, `docs/`), code comments, commit messages

Exceptions:

- **Speech data stays as spoken.** The German filler words in `TranscriptNormalizer` and the
  German subtitle hallucinations filtered in `WhisperInput` match what Whisper hears, not what
  the app shows - translating them would break the filters.
- Historical records (`docs/PLAN.md`, `docs/AUDIT.md`, `docs/DESIGN-0.2.md`) stay as written.
- The chat with Fabi follows the language he writes in.

## Where things live

- Open items: `docs/OPEN.md` ("put it on the list" means there).
- Logic that can go wrong belongs in `:core` (pure JVM, unit-tested); `:app` only holds what
  needs an Android API.

## Building

```bash
./gradlew :core:test                 # pure JVM, no SDK needed
python3 -I tools/check_refs.py       # imports, R ids, manifest classes
tools/build-tinfoil-aar.sh           # once per tinfoil-go pin: Tinfoil's verifier as an AAR
./gradlew :app:lintDebug :app:assembleDebug
tools/typecheck-android.sh           # non-Compose sources against real android-all
```
