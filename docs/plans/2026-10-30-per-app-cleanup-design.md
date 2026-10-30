# Cleanup model per app

Design, 2026-10-30. Item 8 of [the open list](../OPEN.md), from the task prompt
[2026-10-30-per-app-models-and-voice-commands.md](2026-10-30-per-app-models-and-voice-commands.md).
Built in 0.19.0.

## Why

Fabi: "When I talk to an AI, small typos don't matter. But in Keep or Obsidian it matters that
there are no typos and I get a good cleanup. It would be good to set that per app."

Until 0.18 an app could only switch the cleanup off ("Never"); "Always" did the same as "Like
the engine", and the wording dated from before 0.17, when the cleanup stopped being per engine.

## Decisions (with Fabi)

- **Only the cleanup** is chosen per app. The recognition engine stays global: switching speech
  models per app would load another 0.7-0.9 GB model on every app change.
- Per app: **Default** (the cleanup chosen under Settings -> Cleanup), **Off**, or **a chat model**
  on any configured server engine - the same catalog as the Cleanup page. An app's own model runs
  even when the cleanup for every app is off.
- The per-app editor is **its own page** instead of a dialog, in line with "one decision per page"
  (0.17.1): style, own instruction, cleanup, hints, "Remove".

## Design

- **Settings, schema 9.** `AppProfile.appCleanup: AppCleanup(mode: DEFAULT | OFF | MODEL, choice)`,
  shaped like `FallbackSettings`. The old `cleanup: Boolean?` is read only by the migration:
  `false` -> Off, `null`/`true` -> Default.
- **Routing in :core.** `CleanupRouting.decide(settings, package, fellBackFrom, hasKey)` returns
  the choice and, when there is a story, a diagnostics note:
  - Off -> no cleanup, "cleanup off for <app>".
  - Own model ready -> that model.
  - Own model not ready (engine removed, key gone) -> the default, or none, and the note says so -
    no silent change of model.
  - Default -> the cleanup for every app, if its engine is ready (otherwise a note, where before
    the cleanup was skipped silently).
  - The server recognition just fell back from is never asked to clean up.
- **Trust stays honest.** The page shows the existing "the recognized text leaves the phone"
  hint when the app's effective cleanup is less private than the recognition; each dictation is
  still labelled with the weaker of its two trips.
- **Cost.** The statistics price the recognition and the cleanup each by the provider that did
  it. Before, the cleanup was priced with the recognition engine's provider, and a local
  recognition with a server cleanup recorded no cost at all.
- **Saving.** Radios save at once; the instruction saves on leaving the page.
  `AppProfiles.edit` only changes a profile that still exists, so a save on the way out cannot
  bring back an app the user just removed.

## Device test

0.18.0 with WhatsApp on "Never", update -> WhatsApp's page shows Off. Global cleanup off and Keep
on a Tinfoil model -> Keep is cleaned (Diagnostics name the model, the Enclave label), WhatsApp
goes in raw. Keep on a Groq model with the Groq key cleared -> the default is used and Diagnostics
say why. Parakeet recognition with a Tinfoil model for one app -> the hint on its page, the
statistics show a cost. An instruction typed and left with Back is kept; "Remove" stays removed.
