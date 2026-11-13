# Cleanup picker: one service list, one sheet - design and plan

Fabi, 2026-11-12: the cleanup choice is too cluttered, in Settings and in the introduction. The
introduction should let you pick the model too, and no address in the own network as the example.

## What is wrong today

- **Settings → Cleanup** is one long card: every server as a fold, every model of it as a radio
  row. Which model runs is only visible with the right fold open.
- A service gets in by three different ways: Groq and OpenAI only after they were added under
  Speech recognition, Claude through its own "add" fold on the Cleanup page, Tinfoil as a preset.
- **Introduction, step 5** has "Another service": one key field that guesses the service from the
  key's shape. Which services exist is not shown, and the model is picked for you.
- The address fields show `http://192.168.1.20:…` as greyed-in example text.

## Research (2026-11-12)

Superwhisper, VoiceInk, Raycast, Spokenly, Msty, Jan, Home Assistant, Open WebUI; NN/g on
defaults, wizards, progressive disclosure and placeholders:

1. Service first, then model. Nobody lists every model of every service on one page.
2. The recommended model is preselected; changing it is one tap away.
3. A key comes with a "get a key" link, is tested before it is saved, and a failure is said in
   plain words.
4. "Own server" is its own, last entry.
5. An example belongs under the field, not in it (NN/g: placeholder text is taken for a filled-in
   value or the field is skipped). Public https addresses as examples; on a phone, "localhost"
   would be the phone.

## Design

Two building blocks, used in three places: Settings → Cleanup, the introduction's step 5, and
one app's own model (Style per app).

### The service list

One row per service, always in this order:

1. Off (not for one app's own model, which has its own Off)
2. Tinfoil
3. Claude
4. Groq
5. OpenAI
6. every own server that is set up, by its name ("Amber PC", "openrouter.ai")
7. "Add own server"

A row says where the text goes (enclave, cloud - the trust badge) and its state: the model when
it is the one in use, "ready", "needs an API key", or "set up first". The row in use carries the
radio mark. Groq, OpenAI and Claude are always listed: none of them has to be added under Speech
recognition first, and Claude has no fold of its own any more. A service that several servers
point at (two Groq servers) has a row for each.

Tapping Off switches the cleanup off. Tapping any other row opens the sheet.

### The service sheet

A modal bottom sheet (`ModalBottomSheet`), top to bottom:

- **Name and trust badge**, one line on what the service is.
- **API key.** Stored: "API key stored" and "Change". Missing: the field, masked, and "Get a key
  at …" (Tinfoil, Claude, Groq, OpenAI). A key that is recognizably another service's says so,
  with a button that switches to that service and takes the key along. A Claude key must start
  with `sk-ant-`, as today.
- **Address** - for a new own server only. The field is empty; under it: "For example
  https://openrouter.ai/api/v1 - an Amber PC, Ollama or LM Studio in your own network works too."
  An Amber PC's pairing link or an address with the key in it goes apart as today. The button
  reads "Connect" until the server said which models it has.
- **Model.** The models of the service, the recommended one first and preselected - or the one in
  use. An Amber PC or Amber Server lists its own; any other own server is asked (`GET /models`):
  a list to pick from (searched when long), or the model's name typed when it lists none or does
  not answer.
- **Use.** A key typed here is tested first: the test dictation cleaned up by the chosen model,
  the round trip `ConnectionTester` already makes for Claude. Only then are the key and the choice
  stored. A failure is said in the words a failed dictation uses, with "Use anyway" beside it - so
  nobody is stuck offline. Without a new key (only another model picked) Use writes at once.
- A set-up own server also has "Edit server", which opens its page (address, removal).

### Where it shows

- **Settings → Cleanup:** "In use" on top - model, service, where the text goes, a tap opens
  that service's sheet. Then the list, with the note when the text leaves the phone under it. The card for
  the own addition to the prompt stays as it is.
- **Introduction, step 5:** title and lead as today, then the same list. Off is chosen on a first
  run; on a replay the row in use is. The sheet writes at once, as the other steps do; Continue
  moves on. "Keep as it is" is gone - the row in use says it. The key field that guessed the
  service is gone; the sheet's "this is a Groq key" covers the paste-anywhere case.
- **One app's own model:** Default / Off / Own model. "Own model" unfolds the same list (without
  Off); the sheet writes that app's choice.
- **Speech recognition → a server's page:** the address field loses its `192.168.1.20` example
  text; the example goes into the note under it.

### In :core

`CleanupServices` (new, `core/onboarding`), unit-tested:

- `rows(settings, hasKey)`: the list above, each row with its slot, provider, server (or none
  yet) and state (`READY`, `NEEDS_KEY`, `NEEDS_SETUP`).
- `preselected(row, inUse)`: the model a sheet starts with.
- `keyVerdict(slot, typed)`: fine, another service's key, not a Claude key.
- `setup(settings, row, model, keyGiven, phone)`: the `ServiceSetup` for Use - the row's server
  as it is, Tinfoil from its preset, or a known service as `CleanupService.setup` makes it. A key
  is bound to the address only when one was given just now; a stored key for another host is
  never taken along. Null when nothing usable would come of it (a row that is not ready and gets
  no key, a server removed or pointed elsewhere meanwhile).
- `forEveryApp(settings, setup)` / `forApp(settings, setup, packageName)`: the settings with that
  server in them (and an Amber's list kept) and the choice on it. Applied to the settings as
  they are then: a server that changed meanwhile keeps what it is, and a setup that no longer
  fits - its server gone or at another address - writes nothing.

`CleanupService` keeps what the sheet reuses (`read`, `known`, `claude`, `answer`,
`shownModels`, `picked`, `chosen`, `setup`, `addressProblems`, `ServiceFields`). `CleanupPick`,
`ServiceRoute` and `route`/`asksAddress` go with the old step.

### Errors

- Address problems: the validator's words under the field, before anything is asked.
- A known service's address typed for an own server: "This is Groq" with the switch button.
- No answer / no list: the model's name is typed, as today.
- An Amber that offers no cleanup model: said, Use stays off.
- A failed key test: the failure in words, the sheet stays open, "Use anyway".
- A key is never kept in saved state, and a typed key goes only to the address it was typed for.

### Out of scope

OpenRouter as a row of its own (it is the own-server example); testing an own server with a
cleanup round trip (its model list is the check - a cold local model can take long); the cleanup
prompt card; the recognition pages beyond the address example.

## Plan

Each phase ends with its checks and an independent review; findings are fixed before the next.

1. **:core** - `CleanupServices` with tests. `./gradlew :core:test`.
2. **List, sheet, Settings → Cleanup** - `ui/screen/CleanupServices.kt` (list, sheet),
   `CleanupScreen` rebuilt on them, the key test in `ConnectionTester`/`EngineFactory`,
   `MainActivity` wiring, strings in English and German, screenshots.
3. **Introduction step 5, one app's own model, address examples** - `CleanupStep` rebuilt,
   `AppStyle` on the list, `EngineDetailScreen`'s address note; the old step's code, strings and
   tests removed; screenshots.
4. **Whole** - `:core:test`, `check_refs.py`, `check_strings.py`, `:app:lintDebug
   :app:assembleDebug`, `typecheck-android.sh`, both screenshot runs looked at; `docs/OPEN.md`;
   a final review of the whole change.
