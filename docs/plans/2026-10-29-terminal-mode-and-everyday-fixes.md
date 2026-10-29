# Plan: terminal mode and everyday fixes

A task prompt, written from Fabi's notes of 2026-10-29 ("Plan Amber.pdf"). Hand it to a
session as is; the items are on the list in `docs/OPEN.md`.

---

## Prompt

You are working on **Amber**, an Android dictation app (repo root: this repository). A
floating bubble records speech, an engine turns it into text, an optional cleanup model
(an OpenAI-compatible chat model, possibly inside a verified Tinfoil enclave) rewrites it per
app style, and an accessibility service inserts the text into the focused field.

Follow `CLAUDE.md`:
- English everywhere.
- Logic that can go wrong lives in `:core`, with unit tests.
- Open items go in `docs/OPEN.md`.
- Never weaken the privacy promises: the trust label shown must stay true, nothing is sent
  anywhere the user did not choose, and nothing is executed on the user's behalf.

Work through the items **in the order below**. The early ones are small bugs; the late ones
are features that need a design decision from Fabi first. For each item:
1. Reproduce or confirm it in the code.
2. Write the failing test in `:core` where the logic allows it.
3. Fix it.
4. Record the result in `docs/OPEN.md`.

Where an item says *ask Fabi*, stop and ask before building.

### 1. Line breaks from a writing style are lost (bug)

**Fabi:** "I can't get custom styles to produce proper line breaks - in Obsidian and in
e-mail. Maybe something strips newlines?"

**Cause (confirmed):**
- `TranscriptNormalizer.normalize` collapses all whitespace (`\s+`), newlines included, into
  one space. Every transcript and every cleanup result passes through it in
  `DictationController.deliver`.
- So paragraphs the cleanup model writes never reach the field.

**Do:**
- Collapse horizontal whitespace only. Keep newlines, at most two in a row.
- Trim spaces around them.
- Strip line breaks only where the target cannot take them: a single-line field (no
  `TYPE_TEXT_FLAG_MULTI_LINE` on the node or in the `EditorInfo`).
- Raw engine output without cleanup can stay one line, since engines emit no meaningful
  newlines.
- Check `TextSplicer` (seams after a newline) and the cleanup system prompt: the "email"
  style should be allowed to use paragraphs, and "notes" to use lists.

**Must not:**
- Insert a newline through a path that can *send* a message. With
  `InputConnection.commitText`, a `\n` can trigger "Enter sends" in WhatsApp.
- Verify per insertion strategy. `ACTION_SET_TEXT` is safe; a typed or committed newline may
  not be.

**Done when:**
- An email-style dictation shows paragraphs in Gmail and in Obsidian.
- A single-line field gets one line.
- No messenger sends by itself.

### 2. Longer custom instructions (small)

**Fabi:** custom instructions should be allowed to be longer.

**Now:** the per-app instruction is cut to 300 characters in the editor (`AppStyles.kt`,
`take(300)`) and in `ChatCleanup` (`INSTRUCTION_CHARS = 300`).

**Do:**
- Raise both together, to about 2,000 characters.
- Make the field multi-line with a character counter.
- Keep one constant in `:core` that the UI reads.
- Check the cleanup request still fits the models' limits, and that `REASONING_HEADROOM`
  and `TIMEOUT_MS` are still right with a longer system prompt.

### 3. "Clipboard shortcut doesn't work properly" (dropped)

A slip of the tongue (Fabi, 2026-10-29): there is nothing to fix. Clipboard text is marked
sensitive since 0.16.1.

### 4. Pasting into the text field is sometimes unreliable (investigate)

**Fabi:** "sometimes paste into the text field is unreliable." Asked which app: WhatsApp, now
and then.

**Found (0.16.2):** on Android 13+ the first method is `commitText` through the input
connection. The adapter ignored its result, so a stale connection (WhatsApp rebuilds its field
now and then) counted as inserted - the text was lost and SET_TEXT never tried. Fixed; confirm
on the device before looking further.

**Do:**
- Read Diagnostics → the last dictations (strategy, target app, the "not inserted" notes).
- Look at `TextInserter`, `InsertionEngine` and `StrategyMemory`.

**Known weak points:**
- `ACTION_SET_TEXT` rewrites the whole field. It loses mentions and chips, and races with
  typing.
- Fields without accessibility text (web views, custom editors).
- The Android 13+ input-connection path.

**Done when:**
- Each failing app from Fabi's list inserts on a real device.
- The diagnostics name the strategy that worked.
- Tie this in with the security review's target check (M5): the text must go into the
  field that had focus at the start, or not at all.

### 5. Onboarding a normal person can follow (feature)

**Fabi:** "For normal people the UI must be much more relaxed - how it guides you on the
first install. I didn't even realise I first had to download a model; I thought it did that
in the background."

**Do:**
- A first-run flow with one decision per screen:
  1. what Amber does;
  2. the accessibility permission, with the honest disclosure from the security review (M6)
     and an explicit "I agree";
  3. microphone;
  4. **pick an engine with the download built in**.
- Suggest the engine by the phone: Parakeet or the ACFT large Whisper where memory allows,
  FUTO 244 otherwise, with size and an estimated wait.
- Start the download right there, with progress, Wi-Fi aware.
- Until a local model is ready, the bubble and the home screen say "Model downloading - 45 %"
  instead of failing.
- Advanced settings stay hidden until asked for.

**Done when:** a fresh install leads to a first successful dictation without opening
Settings, and nobody has to know what a "model" is.

### 6. Rules per WhatsApp contact (feature: ask Fabi about scope)

**Fabi:** "If you could narrow it down per contact, you could build much sharper rules for the
custom behaviour."

**Idea:**
- App styles today are keyed by package (`AppProfile`).
- Add an optional **conversation** key: a profile for "WhatsApp → Mum" overrides the
  WhatsApp profile.

**Needs:**
- Reading the open chat's title through accessibility (WhatsApp, Signal, Telegram:
  toolbar title views; fragile across app updates).
- Doing that only at the moment of dictation, keeping it on the phone, and naming it in the
  accessibility disclosure.
- A UI to add "this chat" from the bubble or from the style screen.

**Ask Fabi:** only WhatsApp, or every messenger? Match by the chat's name? (A name change
breaks the rule.)

### 7. Terminal mode (feature: design first, ask Fabi)

**Fabi:** a terminal mode with a bubble that looks a bit different. What you say is not
transcribed but taken as a prompt. Cleanup turns "copy all files from folder X into folder
Y" into the bash commands that do it, and puts them into the terminal or the clipboard. That
makes terminals usable on the phone without installing Claude on every server.

**Shape:**
- A new writing style **"Terminal command"** (or a mode). It switches on per app: Termux,
  JuiceSSH, Termius, ConnectBot.
- The bubble shows it (for example a `>_` mark or another ring colour).
- It needs a cleanup model; local-only engines cannot do it yet.

**System prompt:**
- The dictation is a request. Reply with only the command(s) for a POSIX shell: no prose, no
  markdown fences.
- Prefer one line; use `&&` for several steps.
- Assume bash unless the profile says zsh/fish.

**Output handling (:core, tested):**
- Strip fences and leading `$ `.
- Reject prose.
- Bypass `ChatCleanup.plausible`: a command is legitimately nothing like the spoken length.

**Safety:**
- Never execute. Insert or copy **without a trailing newline or Enter**: the user reads and
  presses Enter themselves.
- Flag destructive commands (`rm -r`, `dd`, `mkfs`, `chmod/chown -R`, `> file`, `curl … | sh`,
  `sudo`) with a warning toast and a "confirm" step, rather than inserting silently.

**Inserting:**
- Termux's terminal is not an `EditText`, so `ACTION_SET_TEXT` will not work.
- Use the Android 13+ input-connection path, or clipboard plus paste. Test each app.

**Privacy:** the request goes to the chosen cleanup model. Show which one, and prefer the
enclave.

**Ask Fabi:**
- Which terminal apps?
- Should the model see the terminal's visible text (current directory, last output) as
  context? That is useful, but it means reading the screen.
- Is a confirmation for every command wanted, or only for flagged ones?

**Done when:** in Termux, "list the five biggest files in my downloads folder" puts a
correct, unexecuted command at the prompt.

---

## Fabi's notes (translated from the PDF)

- A terminal mode where the bubble looks a bit different. What you say is not really a
  transcription but a prompt: cleanup turns it into bash/terminal commands. In the
  terminal you say "copy all files from folder X to folder Y", and the bubble puts the
  bash commands for that into the clipboard or the terminal. That would make terminals
  far more usable on the phone, without installing Claude on every server.
- WhatsApp: if it could be narrowed down per contact, you could build even sharper rules
  for the custom behaviour.
- Pasting into the text field is sometimes unreliable.
- For normal people the UI needs to be much more relaxed in how it guides you at the
  first install. I didn't realise I first had to download a model; I thought it did that in
  the background.
- I can't get custom styles to make proper line breaks, in Obsidian and in e-mail. Maybe
  something strips newlines?
- Longer custom instructions would be good.
- The clipboard shortcut doesn't work properly.
