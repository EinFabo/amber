# Amber – project rules

## Language: English, and the app also in German

Everything that lands in this project is written in English:

- app text: UI labels, hints, error and toast messages, notifications, accessibility strings -
  string resources in English (`values/`) and German (`values-de/`): the app follows the phone's
  language. Plain words in both, from `docs/GLOSSARY.md`: "speech recognition", not "engine".
  `:core` says what as a value (`Problem`, `Summary` ...), `Texts.kt` words it; no sentence in a
  screen's code (`tools/check_strings.py`)
- diagnostics and log lines, tool and script output (English only: reports get shared)
- listing texts when a release is published (summary, description, changelog)
- docs (`README.md`, `docs/`), code comments, commit messages

Exceptions:

- **Speech data stays as spoken.** The German filler words in `TranscriptNormalizer` and the
  German subtitle hallucinations filtered in `WhisperInput` match what Whisper hears, not what
  the app shows - translating them would break the filters.
- Historical records (`docs/PLAN.md`, `docs/AUDIT.md`, `docs/DESIGN-0.2.md`) stay as written.
- The voice commands' texts stay English while they are hidden (`Features.VOICE_COMMANDS`).
- The chat with Fabi follows the language he writes in.

## Git: Claude is not a contributor

Commits and pull requests carry Fabi's name only:

- no `Co-Authored-By: Claude ...` trailer, no `Claude-Session:` trailer, no "Generated with
  Claude Code" line in PR descriptions or release notes
- `.claude/settings.json` turns Claude Code's attribution off; `tools/hooks/commit-msg`
  strips such lines from every commit message as a safety net
  (`git config core.hooksPath tools/hooks` once per clone)

## Where things live

- Open items: `docs/OPEN.md` ("put it on the list" means there).
- "baue amber PC" means: implement `docs/AMBER-PC.md` (model choice and compressed uploads
  for Amber PC and Amber Server as own servers).
- "baue amber PC 2" means: implement `docs/AMBER-PC-2.md` (several languages, the phone's own
  cleanup only, lists in the cleanup - for Amber servers).
- Logic that can go wrong belongs in `:core` (pure JVM, unit-tested); `:app` only holds what
  needs an Android API.

## Building

```bash
git submodule update --init --recursive   # whisper.cpp, sherpa-onnx in third_party/, once per clone
./gradlew :core:test                 # pure JVM, no SDK needed
python3 -I tools/check_refs.py       # imports, R ids, manifest classes
python3 -I tools/check_strings.py    # English and German alike, no English left in the screens
tools/build-tinfoil-aar.sh           # once per tinfoil-go pin: Tinfoil's verifier as an AAR
./gradlew :app:lintDebug :app:assembleDebug
tools/typecheck-android.sh           # non-Compose sources against real android-all
```
