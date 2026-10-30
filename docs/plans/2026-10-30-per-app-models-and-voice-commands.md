# Task prompt: cleanup model per app, and voice commands from the bubble

Written 2026-10-30 for a later (cloud) session. Two ideas of Fabi's; research and design first,
no code before Fabi has approved a design.

## Before you start

- Read `CLAUDE.md` and `docs/OPEN.md`. Work on branch `amber`; it must contain 0.18.0 (the
  introduction) - if `git log` does not show "Amber 0.18.0", stop and ask Fabi to push it.
- Rules: English in code, docs and the app; the chat with Fabi in German. Logic that can go
  wrong goes in `:core` with unit tests, `:app` only holds what needs Android. No Claude
  attribution in commits or PRs.
- Process: research → a short design per feature, asking Fabi the open questions one at a time
  → a written plan → build. The phone is not reachable from a cloud session; device tests are
  Fabi's (over adb).
- Local LLM measurements on the Pixel 9a (llama.cpp v0.8.0, Gemma 3 1B, Qwen3 0.6B/1.7B, LFM2
  1.2B, Gemma 3 270M; Amber's real cleanup prompt) are in `docs/OPEN.md` under "Cleanup on the
  phone" - use them for anything that runs a model on the phone.

## 1. Cleanup model per app (Advanced)

**Fabi:** "If you could pick the model per app, that would be cool: when I talk to an AI, small
typos don't matter. But in Keep or Obsidian it matters that there are no typos and I get a good
cleanup. It would be good to set that per app, in an advanced mode."

**Today:**
- `AppProfile` (`core/.../settings/AppProfiles.kt`): package, label, `style`, `instruction`,
  `cleanup: Boolean?` (null = follow, false = never, true = whenever possible).
- One global cleanup since 0.17: `AmethystSettings.cleanup: CleanupChoice?` (engine + model).
- `DictationController.kt` ~461-467 looks up the profile for the target app and skips the
  cleanup when `profile.cleanup == false`.
- The profile dialog (`AppStyles.kt`, `ProfileDialog`) still offers "Like the engine / Always /
  Never" - stale wording since cleanup stopped being per engine in 0.17.

**Direction to design:**
- A per-app cleanup choice: "Off", "Default" (the global one), or any configured server chat
  model (and a local model once one exists). Replaces the Boolean; settings schema bump with a
  migration (`false` → Off, `null`/`true` → Default).
- The decision "which cleanup for this dictation" as a pure function in `:core`
  (e.g. `CleanupRouting.forApp(settings, packageName): CleanupChoice?`) with tests: no profile,
  profile Off, profile with its own model, own model on an engine that was deleted or lost
  its key (falls back to Default, says so in Diagnostics).
- Trust stays honest: a per-app cleanup less private than the recognition shows the existing
  "the text leaves the phone" hint on the app's style page; the dictation's label names where
  the text went.
- Where: Settings → In your apps → App styles, inside the per-app dialog, below the style.

**Ask Fabi:** also the *recognition* engine per app (e.g. Whisper large in Obsidian, Parakeet
elsewhere)? It costs RAM and load time when switching models; worth it?

## 2. Voice commands from the bubble

**Fabi:** "Claude can drive Chrome. It would be cool to long-press the Amber bubble, a field
comes up, and you say 'open WhatsApp' or 'swipe left', 'swipe right', and it does all that
through the accessibility service. Research what can be done. Switchable on and off - and good
for privacy: with Tinfoil the server behind it learns nothing. Also check how much works locally."

**Research (write it down in `docs/` with API references):**
- What an AccessibilityService can do: `performGlobalAction` (back, home, recents,
  notifications, quick settings, lock screen, screenshot, split screen, ...); `dispatchGesture`
  (swipes, taps, long presses - needs `android:canPerformGestures="true"`; check whether
  changing the service config makes users re-enable the service); node actions (click, long
  click, scroll forward/backward, set text, select); finding elements by text or content
  description across windows; starting apps from a service (launch intents, background-start
  rules on Android 14-17).
- How Google's Voice Access does it (numbered labels on tappable elements, command grammar) -
  the reference for "tap Send" and ambiguous targets.
- Understanding the command, compared on latency and accuracy:
  a) rules in `:core` for a fixed command set (German + English: open, back, home, scroll,
     swipe, tap "<label>"), offline and instant;
  b) a small local model with function calling through llama.cpp (see the measurements);
  c) a Tinfoil chat model with tool calls - screen content leaves the phone only to a verified
     enclave.
  Likely: rules first, a model only for what the rules do not understand.

**Design questions for Fabi:**
- The gesture: hold already means push-to-talk, tap means dictate. Double-tap? Hold, then
  slide up? A mode switch in the notification? Spoken or typed command, or both?
- Which commands first (a short list), and which never (see below).
- Off by default, its own switch, its own explanation.

**Security and policy (must be part of the design):**
- The accessibility disclosure (security review M6, `R.string.accessibility_description`)
  promises "Amber never taps, swipes or acts for you". This feature breaks that promise: its own
  opt-in with an updated disclosure, and the promise stays true while it is off.
- Screen text given to a model can carry instructions (prompt injection): the model only picks
  actions from a whitelist with typed arguments; no sending messages, paying, deleting,
  confirming dialogs or changing system/security settings without an explicit confirmation;
  never in password fields; never on Amber's own settings or the accessibility settings.
- No screen content in logcat; Diagnostics only on request.
- Google Play: acting autonomously through the accessibility API and the declaration it needs;
  `isAccessibilityTool` must not be claimed; Android 17 Advanced Protection takes the service
  away anyway.

## Deliverables of the session

1. A research note for feature 2 (what is possible, with references).
2. A design per feature in `docs/plans/`, written after Fabi answered the open questions.
3. After Fabi's OK on the designs, an implementation plan. No product code before that.
