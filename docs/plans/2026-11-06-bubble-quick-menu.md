# Bubble quick menu - plan (2026-11-06)

Fabi, dictated: "a quick menu at the bubble: on long-press a menu pops up, and when you move
the bubble in a direction, names are shown, and on release the action is triggered. What goes
there for each state, we have to see." Planning only; nothing built yet.

## What the bubble does today (`BubbleController.TouchHandler`)

- Tap: `DictationController.toggle()`. Double tap (second down within 300 ms of a tap that
  started a recording): `commandInstead()`.
- Still for `HOLD_MS` = 300 ms: hold-to-talk (`start(holdToTalk = true)`); lift sends. Only in
  Idle - while a run is going `onHoldStart` returns false, and the lift then counts as a tap.
- Moved past `touchSlop` before the hold fired: move, plus the hide target when idle in another
  app. Moved after the hold fired: the bubble travels while recording.
- Done shows for 1.2 s (`DONE_DISPLAY_MS`), Failed for 3.5 s, then Idle. A menu keyed on Done
  or Failed alone would hardly ever be seen: "the last result" has to be an Idle context.

## 1. The gesture conflict

A still hold is hold-to-talk today, so a menu on "long press" has to share that hold. Options:

- **A. Menu first, talk on dwell (recommended).** Still 300 ms: the menu opens (light tick),
  no microphone yet. Slide out of the dead zone: a slot lights up, lift runs it. Stay in the
  centre a further `TALK_DWELL_MS` (~300 ms, so ~600 ms in all): the labels fade, recording
  starts with today's LONG_PRESS buzz, lift sends - push-to-talk as before, just later. Once
  talking, a drag moves the bubble while recording, as today. Quick drag before 300 ms stays a
  plain move with the hide target. Cost: push-to-talk starts ~300 ms later; to win part of it
  back, opening the menu warms up the engine and server connections (`warmUpConnections`)
  without touching the microphone.
- B. Talk first, menu on slide-out: recording starts at 300 ms as today and the labels appear;
  sliding out discards the recording and runs the slot. Push-to-talk unchanged, but every menu
  use opens the microphone - privacy dot, foreground service, on Android 14+ with another app
  in front even the invisible `MicProxyActivity` - and leaves a discarded run behind. Rejected.
- C. A setting "Long press: hold to talk / quick menu". No timing change, but each user loses
  one of the two. Good as an escape hatch if A's dwell feels wrong on the device.
- D. Menu on a flick (quick drag and lift): clashes with moving, and the names cannot be read
  in time. Rejected.

During a toggled run a long press means nothing today (it ends as a tap), so there the menu
opens without any conflict. During hold-to-talk the finger is the recording: no menu.

Moving from inside the menu: not offered. Sectors reach to the screen edge (angle counts,
distance does not), so overshooting never turns into a move by accident; moving stays the
quick drag. Fabi's "move the bubble in a direction" is kept visually: the bubble follows the
finger on a rubber band (at most one bubble size) and springs back on lift.

## 2. Menu mechanics

- **Slots: up to 5, a half-circle fan of 45-degree steps** pointing into the screen: up,
  up-inward, inward, down-inward, down. The bubble lives at a side edge (default
  `horizontalBias = 1f`), where only half the directions exist; 8 slots would leave 3 dead.
  Five 45-degree sectors are still hit blind by a thumb (marking-menu studies: 4 is safest,
  8 the limit). Fewer actions leave slots empty instead of re-spreading, so a place stays put.
- **Frame relative to the edge, not the screen**: "inward" is left on the right edge and right
  on the left edge - the same muscle memory for both hands. Away from the edges, inward means
  toward the nearer half's centre.
- **Top, bottom, keyboard**: when the fan's up or down label would leave the screen (or sit on
  the keyboard, `keyboardTop`), the whole fan tilts away from that edge by 45 degrees; a slot
  that still does not fit stays empty. In a corner the fan keeps 3-4 slots.
- **Dead zone**: radius ~0.5 bubble size (28 dp at 56 dp), well past `touchSlop`. Inside it
  nothing is highlighted; leaving it highlights the sector under the finger's angle.
- **Names**: small labels on a ring ~1.6 bubble sizes out, plus the highlighted one large in
  a chip above the fan, where the thumb never covers it. Labels at most ~14 characters, verbs
  first ("Command", "Cancel", "Copy last"). A disabled action is shown greyed, never moved.
- **Haptics** (only with `hapticsEnabled`): menu open = CLOCK_TICK; entering a slot =
  SEGMENT_TICK (API 34, else CLOCK_TICK); back into the dead zone = a softer tick; run =
  CONFIRM; talk starts = LONG_PRESS (as today); stale or refused action = REJECT.
- **Cancel**: lift inside the dead zone after having left it (the dwell only counts while the
  finger never left the centre), or ACTION_CANCEL. Lift in the centre before the dwell also
  just closes the menu.
- **Stale state**: the action list is taken when the menu opens and does not rebuild under the
  finger; on lift the action is checked against the state now (`QuickActions.stillValid`). A
  run that ended meanwhile gives REJECT and a short toast instead of a wrong action.
- **Window**: one more TYPE_ACCESSIBILITY_OVERLAY, NOT_TOUCHABLE, like `HideTarget`; the touch
  stream stays with the bubble window that got ACTION_DOWN.
- **TalkBack**: explore-by-touch does not deliver the hold. The same per-state list goes onto
  the bubble as `AccessibilityAction`s (TalkBack's actions menu), with a contentDescription
  that names the state. This also closes most of the open item "The bubble is silent for
  TalkBack" (`docs/OPEN.md`).
- **One-handed**: right thumb with the bubble on the right edge reaches the inward fan
  naturally; the left edge mirrors it. The most used action goes inward (the easiest thumb
  move), the rarest ones up and down along the edge.

## 3. Actions per state (checked in code)

"Exists" = the function is there and only needs a slot; "new" = needs code beyond the menu.

| State | Action | Status |
|---|---|---|
| Idle | Centre dwell: hold to talk | exists (`start(holdToTalk = true)`) |
| Idle | Voice command (record a command directly) | small new: `start()` with a forced `RunMode.COMMAND`; only with commands on |
| Idle | Insert last dictation again | small new: `DictationLog.last.text` + `TextInserter.insert`, behind `TargetCheck`; memory only, 20 deep, gone after a restart |
| Idle | Copy last dictation | small new: `copyToClipboard` exists; only with `insertion.allowClipboard`, never for a failed secret-field case |
| Idle | Cleanup off/on in this app | data exists (`AppProfile.appCleanup`, `AppCleanupMode.OFF/DEFAULT`, `AppProfiles.edit`); bubble write new |
| Idle | Hide bubble here | exists (`onHideIn`, same rule as drag-to-hide: another app in front) |
| Idle | Open Amber | small new (launch `MainActivity`) |
| Idle | Switch engine | data exists (`activeEngineId`); new, and a privacy question (see 5) |
| Idle | Switch language | new, depends on several languages (`docs/AMBER-PC-2.md`) |
| Idle | Style for this chat | later, OPEN item 6 already names "long-press menu" for it |
| Listening/Starting | Cancel (throw away) | exists (`cancel()`; L8: a cancel during model load can be lost) |
| Listening | Make it a command | exists (`commandInstead()`; only while still listening) |
| Listening | Send without cleanup | small new: a per-run flag read in `cleanUp()` |
| Listening | Start over | small new: `cancel()` then `start()` |
| Listening | Pause | new and hard: no engine or `AudioCapture` can pause; not planned |
| Transcribing | Cancel | exists (`cancel()`; a tap already cancels a command) |
| Transcribing | Keep text as recognized (skip cleanup) | small new: race `cleanup.clean` against a skip signal, same path as the timeout; only while `WaitProgress` is on the cleanup step |
| Transcribing | Use the local result now | new, medium: `ReadyFallback` prepares it, taking it early is not wired |
| Done (and Idle shortly after) | Undo the insertion | new, bigger: OPEN "Undo the last insertion" (field text before splicing) |
| Done | Copy / insert again | as in Idle |
| Done | Recognize again with another engine | new, bigger: the audio is not kept after a run (OPEN "keep a failed recording") |
| Commanded | Repeat the command ("louder" again) | new: the parsed commands are not kept |
| Failed | Dictate again | exists (`start()`) |
| Failed | Fix it (install language / download model) | small new: `Failed.remedy` exists, HomeScreen shows it; needs a way into Amber |
| Failed | Open diagnostics | new: `MainActivity` has no deep link yet |
| Failed | Copy the text that did not go in | new: today the text only lives in the failure message |

"Done" and "Failed" vanish in 1.2 / 3.5 s, so their actions are offered in Idle as long as the
last record is fresh (proposal: 60 s and the same app in front).

First cut (exists or small new): Idle - inward Command, up-inward Insert again, down-inward
Copy last, up Cleanup here, down Hide here (Open Amber when Amber is in front). Listening -
inward Cancel, up-inward Make it a command, down-inward Send without cleanup, up Start over.
Transcribing - inward Cancel, up-inward Keep as recognized.

## 4. Where the code goes

`:core` (pure, tested):
- `core/bubble/QuickMenuGeometry`: the fan for a bubble centre, screen size and keyboard top
  (tilt, which slots fit); the slot for a drag vector (dead zone, sector angles, ties on a
  boundary).
- `core/bubble/BubbleGesture`: the whole touch decision as a pure state machine - down, move,
  hold timer, dwell timer, up, cancel in; tap, double tap, move, menu open, highlight, run
  slot, talk start/end, close out. `TouchHandler` keeps only the window and the haptics. Today
  that logic is untested in `:app`.
- `core/bubble/QuickActions`: the action list for a snapshot (phase, mode, commands on, field
  focused, app in front, Amber in front, last record and its age, cleanup planned, app's
  cleanup mode, clipboard allowed), slot order, English labels, `stillValid`.

`:app`: `QuickMenuView` (labels, chip, fade), the overlay window, haptics, the rubber band,
running the actions (new `DictationController` entry points: command start, skip cleanup,
start over, insert again, copy last), the `MainActivity` intent extras, TalkBack actions.

Tests (`:core:test`):
- Gesture: tap; double tap; quick drag = move with no menu; still 300 ms = menu; dwell = talk;
  leave and come back = no talk, lift = close; slide and lift = run; ACTION_CANCEL anywhere =
  nothing runs, a talk is stopped; hold during a toggled run = menu, never hold-to-talk; drag
  after talk started = move while recording; jitter under the dead zone keeps the dwell.
- Geometry: right and left edge mirror; middle of the screen; top and bottom corners tilt;
  keyboard top counts as the floor; sector boundaries (22.5 degrees) are stable; a vector inside
  the dead zone is no slot; empty slots stay empty.
- Actions: each state's list; commands off removes the command slots; no field removes
  Insert again; clipboard off removes Copy; Amber in front swaps Hide here for Open Amber;
  "fresh result" age limit; `stillValid` after Listening -> Idle, and Transcribing past the
  cleanup step; labels at most 14 characters.
- Device test (Pixel 9a): both edges, keyboard up, one-handed, TalkBack actions, haptics off.

## 5. Open questions for Fabi

1. Is ~600 ms push-to-talk (model A) acceptable, or should hold-to-talk stay at 300 ms and the
   menu be a setting (model C)?
2. Five slots in a half-circle, or only four (inward, up, down, plus one)?
3. Switching engine from the bubble: allowed toward a less private engine (local -> cloud)
   with one flick? Proposal: only among engines you mark as "in the quick menu", with the trust
   level in the label.
4. Which slot gets what - fixed by Amber, or chosen per slot in Settings -> Bubble?
5. Should the menu also exist where only voice commands show the bubble (no text field)?
6. Undo and "recognize again" need the field text and the audio kept: build those first
   (History/undo item), or ship the menu without them?
7. "Fresh result" for Done/Failed actions in Idle: 60 s and the same app - right?
