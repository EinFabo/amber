# The bubble: gone with the field, calm while typing - findings and plan

Fabi, 2026-11-13: the bubble stays when an app closes its text field itself (YouTube after
pressing Search, Spotify's search), and it "jumps up" while typing. Three investigations: the
hide logic in the code, the placement in the code, and how others do it (Android's own sources,
Wispr Flow, Grammarly, open-source overlay services). No phone was attached; what needs a device
log is marked.

## Why the bubble stays

- From Android 13 the bubble's "a field is active" comes from one signal only: the accessibility
  input method's `onStartInput` sets it, `onFinishInput` clears it
  (`AmethystAccessibilityService`). Focus and window events are ignored there, and the keyboard
  window plays no part in whether the bubble shows - only in where.
- Android has no "field lost focus" event, and it does not call `onFinishInput` when a field
  goes away inside the same window: a detached field is only finished on the app's side and the
  keyboard is hidden (AOSP `InputMethodManager`, read at android16-release). Leaving the screen
  heals it, because the next window focus starts a new input - which is why only apps that
  remove their field themselves show the bug. High confidence from the code; which path
  YouTube and Spotify take needs a device log.
- A second way to the same result: the field keeps its focus and the app only hides the
  keyboard. Then nothing about the field changes at all.
- In both, one thing does happen: the keyboard closes.

## Why it jumps while typing

- Most likely (not confirmed on the device): the "never cover the field" rule. A chat input that
  grows by a line overlaps the bubble's place, and the bubble hops above it - each further line
  another hop. The rule has no slack (about 12 px at the default height), is re-read at moments
  that have nothing to do with typing, and leaves no log line.
- Also in the code: any change of the keyboard's top edge by a pixel is followed at once, and a
  field lookup that comes back empty for a moment drops the bubble and lifts it again.
- Every position is computed from scratch; where the bubble is now is not an input.

## What others do

- Wispr Flow: the bubble appears as the keyboard opens and hides as it starts closing.
- Android's own Bubbles and accessibility floating menu: move only when the keyboard would be in
  the way, keep the user's position, a drag wins.
- Nobody anchors to the field's bounds on every change.

## Decisions

1. **The bubble goes with the keyboard.** With "only when you type", a field counts as active
   while it takes text *and* its keyboard has not closed: it shows at once when a field starts
   (the keyboard is on its way), hides when the keyboard closes or - on a phone that reports its
   keyboard - never comes, and returns with the keyboard. So a field whose keyboard was closed
   with Back has no bubble until it is tapped again. On a phone that never reports a keyboard
   window nothing changes. With a hardware keyboard and "only when you type" the bubble goes a
   moment after the field took focus; "always" keeps it. A running dictation keeps its bubble as
   before.
2. **The bubble stays where it is while that is allowed.** While the keyboard stays up for the
   same field, the bubble only moves when its place has become wrong - the keys or the field
   would be under it - and then with room for four more lines, so the next lines do not move it
   again. It goes back to its own height when the keyboard opens or closes, another field
   starts, it is dragged, or its settings change.
3. **Every move and every change of "field active" says why**, in logcat and on Diagnostics →
   Keyboard, so one session on the phone shows what happened.

## In :core

- `KeyboardPolicy.Decision.shown`: true when a keyboard is on screen for this field, false when
  it closed or never came, null while that is open. `onNewField` starts the wait for a keyboard
  again when another field takes input while one was already active.
- `FieldPresence`: the field's kind and the keyboard's `shown` → `active`.
- `BubbleHold`: the place to keep, or the one to move to with headroom, on top of
  `BubblePlacement`.

## Not in this change

- Asking the field's node whether it is still there (`refresh()`, `findFocus`). It is logged
  with every lookup; if the phone shows a field gone with its keyboard still up, that is the
  next step.
- Following the keyboard frame by frame, fading instead of gliding, shrinking when idle.

## On the phone

1. YouTube: search, press Search - the bubble goes with the keyboard. Spotify's search the same.
2. WhatsApp or another chat: type four lines - at most one move, none back down.
3. Field to field in one form: no blink.
4. Back closes the keyboard: the bubble goes; a tap on the field brings both back.
5. A dictation started with the keyboard up, the keyboard closed meanwhile: the bubble stays
   until the text is in.
6. `adb logcat -s Amber` during all of it; Diagnostics → Keyboard afterwards.

## Seen on the phone (Pixel 9a, Android 17, Gboard, 2026-11-13, over adb)

- YouTube, the keyboard's search key or a suggestion: no `onFinishInput`; the bubble goes as
  "field without its keyboard" about 0.75 s after the key. A hardware Enter does finish the
  input. Spotify finishes it for the search key and when the results are scrolled.
- Telegram, lines typed on the keyboard: the field's growth is what moves the bubble. With 40 dp
  of headroom it moved at line 4 and line 6; with 80 dp once, at line 3, for all seven lines.
- Text that arrives without the keyboard (`input text`) does not get the field read again.
- Back: the bubble goes; a tap on the field brings keyboard and bubble back.
- Not tried: field to field in one form, a dictation while the keyboard closes.
