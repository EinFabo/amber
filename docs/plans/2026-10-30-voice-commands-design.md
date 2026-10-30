# Voice commands from the bubble

Design, 2026-10-30. Item 9 of [the open list](../OPEN.md), from the task prompt
[2026-10-30-per-app-models-and-voice-commands.md](2026-10-30-per-app-models-and-voice-commands.md);
research with API references: [voice-commands-research.md](../voice-commands-research.md).
Built in 0.20.0.

## Why

Fabi: "It would be cool to long-press the Amber bubble, a field comes up, and you say 'open
WhatsApp' or 'swipe left', 'swipe right', and it does all that through the accessibility
service. Switchable on and off - and good for privacy."

**Done when** a double-tap on the bubble and "öffne WhatsApp" opens WhatsApp, "zurück" goes back
and "scroll runter" scrolls - offline, nothing read from the screen, nothing done while the switch
is off.

## Decisions (with Fabi)

- **Commands in v1:** navigation (back, home, recent apps, notifications, quick settings, lock,
  screenshot), opening an app, scrolling and swiping. **Not** "tap <label>": no screen text is
  read, so nothing on the screen can smuggle in instructions.
- **Understood by rules only**, offline, German and English at once, in :core. No model - the
  command set is small, and Play's policy allows exactly this (one spoken trigger, one fixed
  action). A `CommandParser` interface leaves room for a model later.
- **Trigger: double-tap.** Hold is already push-to-talk (from 0.3 s), so Fabi's long press would
  have cost it. The first tap starts recording at once, as today; a second tap within 300 ms turns
  the run into a command - a normal dictation gets no slower.
- **The bubble shows everywhere while commands are on** ("Hidden in" and the resting opacity still
  apply), because "open WhatsApp" and "go back" are needed where no text field is. **Without a
  focused text field a single tap or hold is a command** - dictating into nothing was useless there.
- **The same accessibility service**, with `canPerformGestures` added. Android keeps an enabled
  service on through the update without a new system dialog, so the consent is Amber's own: the
  switch is off by default, switching it on shows what it does with "I agree", and one executor
  refuses every action while it is off. The service description says so.

## Design

**:core (tested)**
- `VoiceCommands` normalizes the heard text (lowercase, ß -> ss, accents folded, punctuation and
  fillers out, a leading "Amber", "bitte/please", "kannst du/can you") and matches fixed phrases:
  global phrase first, then "öffne" + a system noun ("öffne Benachrichtigungen" never searches the
  apps), then scroll/swipe, then "open <app>", else not understood.
- `AppMatch` finds the app by its launcher label in tiers - exact, prefix, word/substring, then a
  small edit distance for mis-hearings ("Telegramm"). Several apps in the best tier -> nothing is
  done, "Which one? …". Never a guess.
- `CommandTrigger`: which runs are commands (bubble, switch on, no text field) and what counts as
  a double tap (a tap that *stopped* a dictation never does).
- `BubbleVisibility`: the bubble's show/hide rule, moved out of the controller; commands on
  override "only with a text field".
- `ScrollTarget` picks the scrollable node (directional action first, the active window, the
  largest) and `GesturePath` draws strokes that stay off the screen edges and off the bubble.
- `DictationLog` keeps commands, marked as such; they are not counted in the statistics.

**:app**
- `CommandExecutor` - the one gate (switch off or phone locked -> refused), then
  `performGlobalAction`, the app's launch intent, a scroll action on the picked node, or a gesture.
  Logcat gets the kind of command only, never the heard words or a package.
- `DictationController` runs a command like a short dictation (same engine, no chunking, at most
  8 s, no cleanup, no insertion), then parses and executes it; the bubble shows the result, a toast
  says "“heard” -> done".
- The bubble gets a command look (inverted disc) and the double tap; a settings page "Voice
  commands" under In your apps holds the switch, the disclosure and the phrases.
- A debug hook (`app.amber.debug.COMMAND`) runs a typed command for device tests.

## Risks

A dispatched stroke would land on the bubble -> strokes avoid its frame. Below Android 13 an app
without focus events looks field-less, so a tap there is a command (the heard words stay in
Diagnostics). Back with the keyboard open only closes the keyboard, as in Voice Access. Background
activity starts on Android 17 are not re-checked yet: a blocked launch must say so. Advanced
Protection on Android 17 takes the service away entirely.
