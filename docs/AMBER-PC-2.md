# Task: languages, the phone's own cleanup and lists for Amber servers ("baue amber PC 2")

When Fabi says **"baue amber PC 2"**, implement this page (part 1 builds on `docs/AMBER-PC.md`,
already done in 0.22), test each part, commit each on its own (repo rules in `CLAUDE.md`:
English, no Claude attribution), and report in German.

## Why

Fabi dictates through Amber PC (and soon Amber Server) and found three things:

- **Short dictations came out in English, or Turkish.** The server engine is set to
  "automatic"; Whisper guesses the language from a second of audio and then translates:
  German "Ich hab kein Wasser" (0.9 s) arrived as "I have no water.", another as Turkish.
  Longer dictations are always right. For its own Whisper the app already offers several
  languages ("de,en", detected among per dictation); for a server engine only "automatic or
  one language" (`ServerLanguagePicker`), because plain APIs take one code. Amber servers now
  take a list and detect among it.
- **Two cleanups on one dictation.** The PC cleaned the transcription with its own prompt,
  then the app cleaned it again with its own (`ChatCleanup` through the PC). Fabi: the phone's
  prompt for the phone's dictations, the PC's for the PC's.
- **Lists.** Through Tinfoil a spoken list comes back as lines; through the PC's local model
  (Qwen3 4B) it stays one line. The prompt says lists may have line breaks; big models read
  that as "make a list", small ones do not.

## The contract

`~/projects/amberPC/docs/phone-api.md`, `POST /v1/audio/transcriptions` (Amber PC 0.4.3 and
Amber Server 0.4.3 or newer):

- `language`: `auto`, one ISO 639-1 code, or **several, comma-separated** (`de,en`): the server
  detects which of them was spoken; on a tie, or when it heard none of them, the first wins.
- `amber_cleanup=client`: the client cleans up itself; the server then leaves out its own rules
  and cleanup and returns what was recognized (Whisper's annotations removed).

Older Amber servers take neither: a list makes whisper.cpp refuse the language (it then
detects any language, as "automatic"), `amber_cleanup` is ignored (the PC cleans as before).

## What to build

### Part 1: several languages for an Amber server

1. **Picker**: an engine with a stored Amber catalog (part 1 of `docs/AMBER-PC.md`) gets the
   multi-language chips (`WhisperLanguagePicker`, same hint text adjusted: "Several: the PC
   picks among them for each dictation - a short one is not taken for another language or
   translated."), not `ServerLanguagePicker`. Other server engines keep "automatic or one".
   Order matters: the first picked wins a tie - keep the order of picking.
2. **Request** (`OpenAiCompatibleEngine`): to an Amber server send every code of the tag
   (`"de-DE, en"` -> `language=de,en`, via `WhisperInput.languageCodes`); one code as today;
   none -> no field. To any other server: one code as today; for a tag that holds several
   (typed by hand), send no `language` (the service detects) instead of only the first, which
   would translate the other languages into it.
3. **Command recognition** (`CommandLanguages.recognize`) is unchanged: it still tries its
   languages one by one with one code each.
4. **Migration**: none needed - `languageTag` already holds "de,en" lists.

### Part 2: the phone's cleanup only

1. Every transcription request to an Amber server (stored catalog) carries
   `amber_cleanup=client` - whether or not the app's cleanup is on for that app: the app
   decides about cleanup for its dictations, not the PC. Other servers never get the field.
2. Nothing else changes: the app's own `ChatCleanup` runs as before (through the PC's chat
   route when the cleanup engine is the PC).

### Part 3: lists in the cleanup

1. `ChatCleanup.BASE_PROMPT`: after "...or for the greeting and sign-off of an email;
   otherwise keep it one paragraph." add, word for word as the PC's prompt has it:
   `When the speaker lists several items, steps or points (a shopping list, a to-do list,
   steps to follow), put each item on its own line starting with "- ", after the sentence
   that introduces them.`
   Checked on the PC with Qwen3 4B and the app's prompt: "Einkaufsliste Tomaten Gurken Käse
   Milch und Brot" and "Für morgen erstens ... zweitens ... drittens ..." come back as "- "
   lines; a plain sentence ("ich brauche Tomaten, Käse und Suppe") stays a sentence.
2. `ChatCleanup.unwrap`: drop trailing spaces and tabs at the end of every line - models end
   their lines with two spaces (Markdown's line break), junk in a text field.
3. `plausible` must accept such a list (it should already: same words, a "- " is no word).

### Tests (`core/src/test/...`)

- Part 1: an Amber engine with "de,en" sends `language=de,en`; "de-DE" sends `de`; no tag no
  field; a non-Amber engine with "de,en" sends no `language`; the Amber picker keeps order.
- Part 2: `amber_cleanup=client` on every request to an engine with an Amber catalog, never
  to one without.
- Part 3: the system prompt contains the list sentence; `unwrap("Liste:  \n- A  \n- B")` is
  `"Liste:\n- A\n- B"`; `plausible` takes the two list examples above.

## Check against the real thing

Amber PC 0.4.3 on Fabi's PC (the tunnel address is in the app). After building: pick German
and English for the PC engine, dictate a few short phrases ("Okay, danke.", "Bis gleich.") -
none may come back in English; dictate a shopping list with "erstens ... zweitens ..." with
the cleanup on the PC - it must come back as lines. The PC's log (`%LOCALAPPDATA%\
app.amber.pc\logs\amber.log`) shows no "cleanup:" line of the PC's own for phone dictations
any more, only "phone API: ..., cleanup in N ms" for the app's.

## Follow-up: the list cannot be saved (found in 0.26.0)

Picking German and English for the PC engine and tapping Save shows `"de,en" is not a valid
language code (e.g. de-DE or de).` - the engine cannot be saved, so part 1 does not work.

**Cause:** `EngineConfigValidator.validate` (`core/.../engine/EngineConfigValidator.kt`, the
`tags` below "Only Whisper decides between several languages itself") splits the tag into a
list only for `EngineKind.WHISPER_CPP`; for every other kind it checks the whole tag as one
code, and "de,en" fails `isPlausibleLanguageTag`. Part 1 opened the multi-language picker for
Amber servers but left this check as it was.

**Fix:** split the list for `OPENAI_COMPATIBLE` too:
`if (config.kind == EngineKind.WHISPER_CPP || config.kind == EngineKind.OPENAI_COMPATIBLE)`.
Safe for any server: part 1 already sends a list only to Amber servers and no `language` at all
to other servers when the tag holds several. Update the comment above it. Test in
`EngineConfigValidatorTest`: an OpenAI-compatible engine with "de,en" and with "de-DE, en" is
valid; with "de,english!" it reports `"english!"` only; a single "de" stays valid.

**Why it matters (measured on Fabi's PC, Large v3 Turbo, language forced to German):** a
short English phrase stays English ("Okay, see you later."), but a longer English sentence is
translated into German, and not cleanly ("I switched to English now, and I want to see what
happens with a longer sentence in English." -> "Ich habe jetzt auf Englisch gewechselt, und ich
möchte sehen, was mit einem langer Sprache in Englisch passiert."). Only "de,en" lets the PC
keep each dictation in the language it was spoken in. Check after the fix: Save works with
German + English, and a longer English dictation comes back in English.

## Out of scope

No server-side changes (they are in `~/projects/amberPC`), no change for other server engines
beyond part 1's "no single code for a list", no new cleanup styles.
