# Bubble behaviour: where it sits, how it moves, how it rests

Research and proposal, 2026-11-06. Nothing built yet. Fabi: "I don't like the bubble behaviour;
best to orient on something like Samsung's S Pen (Air Command) or others." He did not say what
bothers him, so this ends with questions for him. The long-press quick menu is planned
separately; this only leaves room for it.

## 1. How the bubble behaves today

From `bubble/BubbleController.kt`, `BubbleView.kt`, `core/bubble/*` and [OPEN.md](../OPEN.md).

- **When it shows.** `BubbleVisibility.wanted`: always during a dictation; never on the lock
  screen or in a "Hidden in" app; otherwise only while a prose field has focus
  (`onlyWithTextField`, default on; `TextFieldKind` keeps it out of password/PIN/number fields),
  or everywhere when voice commands are on and `everywhereForCommands` is set. Showing is
  immediate (180 ms fade + scale 0.8 -> 1, one spark); hiding waits 400 ms (field-to-field focus
  hops), then 140 ms fade + scale down, then the window goes INVISIBLE and untouchable.
- **Where it sits.** Anywhere: `horizontalBias`/`verticalBias` as fractions of the free screen
  (default 1.0 / 0.72 = flush against the right edge, about three quarters down). Drag clamps
  only to the raw screen (screen coordinates, no inset for status bar, gesture bar or cutout).
  No snapping, no fling, no momentum: it stays exactly where the finger lifts, mid-screen too.
  One position for all apps; size 56 dp (setting).
- **With the keyboard.** `KeyboardPolicy` predicts the keyboard top on focus (last settled top,
  kept across restarts) and `BubblePlacement.y` lifts the bubble to 12 dp above it, then steps
  it clear of a floating keyboard and of the focused field (nearest free spot above/below; fields
  taller than 45 % are ignored). It moves with a 250 ms ease and drops back when the keyboard
  leaves. Only y ever changes; x stays where the user put it.
- **Order of events on focus** (`AmethystAccessibilityService.updateTextFieldFocus`):
  `setTextFieldFocused` runs first, so the bubble appears at its resting y (default 0.72, i.e.
  where the keyboard is about to be) and only then `setKeyboard` glides it 250 ms upward. So in
  the default setup every field focus is "pop in on the keyboard area, then slide up" - very
  likely part of the old "starts after the keyboard, jumps instead of gliding" item.
- **Over the app's buttons (likely, not measured).** At the right edge, 12 dp above the keyboard,
  the bubble lands on the input bar's far right - where WhatsApp, Signal and Messages put
  send/mic. Field avoidance would not help: the send button is outside the field's box, and a
  bubble that does not overlap the field horizontally is left where it is.
- **Opacity.** `restingOpacity` 0.2..1, default 1, applied the moment it is idle; full while
  dictating. No idle timer, no shrink, no tuck behind the edge.
- **Gestures.** Tap toggles dictation, double-tap within 300 ms makes it a command, hold 300 ms
  is push-to-talk, drag moves it at any time (also while recording). A drag while idle in another
  app shows a 64 dp hide target bottom-centre; dropping there adds the app to "Hidden in".

## 2. Reference designs

| | Rest position | Idle | Moving | Dismiss |
|---|---|---|---|---|
| **S Pen Air Command** | Floating icon shown while the pen is out; "at different points on the edges of the screen" [1] | Stays visible after the panel closes [2] | Hold, then drag [1][2] | Hold and drag to a bin at the **top** [2]; switch "Show Air command icon" [3] |
| **Edge panels handle** | Thin tab on left or right edge, any height, "Lock handle position" [4] | Transparency slider Low..High, size and width sliders, vibration on touch [4] | Drag along the edge only | - |
| **AssistiveTouch** | Movable button that "can be dragged to any edge" [5] | Fades to 40 % idle opacity a few seconds after use, slider adjustable [6] | Drag, rests at an edge | Off in Settings; tap/double/long-press actions configurable [5][6] |
| **Android Bubbles (AOSP)** | Docked left/right, 120 dp down, 60 dp size, hangs 2.5 dp offscreen [7] | - | Fling: spring stiffness 700, damping 0.85, friction 1.9; changing sides needs 750 px/s [8] | Magnetic target bottom-centre (96 dp circle, 50 dp margin), fling at 4000 px/s into it, heavy-click haptic on stick [7][9] |
| **Wispr Flow Android** | Default right edge; snaps to the left or right edge on release; position kept for the session only [10][11] | Optional "shrink when not in use" to an icon or dot after 5 s; tap on the small one expands and starts dictating; size 0.7-1.15x; opacity row [12] | Drag while idle | Slow drag to bottom-centre target = snooze 10 min, shake to undo [11] |
| **Gboard voice** | Moved from a floating mic to a toolbar inside the keyboard (2025) [13] | - | - | - |

Behaviours worth copying:

1. **Always dock to an edge** (Air Command, AssistiveTouch, AOSP, Wispr). Free along the edge,
   never in the middle of content. Side picked by release point plus fling velocity (AOSP:
   midpoint rule, 750 px/s to cross over), settled with a slightly damped spring.
2. **Recede when idle, not when hidden** (AssistiveTouch 40 % after a few seconds, Wispr shrink
   after 5 s, Edge handle as a slim tab). Full size and opacity the moment it is touched or works.
3. **One tap on a receded bubble does the job** (Wispr: tap on the dot starts dictation) - no
   "first tap only wakes it".
4. **Keyboard: save, move, restore** (AOSP: stores the pre-keyboard y, moves to the lowest
   allowed spot above the keyboard, restores on close; a manual move clears the saved y).
   AssistiveTouch did the same until iOS 26 users reported it staying on the keys [14].
5. **Magnetic dismiss target with haptics**; a drag cancelled near it springs back to the edge.
6. **Respect insets**: AOSP keeps the stack inside status bar / nav bar / cutout insets.

None of them remembers a position per app; Wispr does not even keep it across restarts.

## 3. Target behaviour

**Option A - keep free placement, polish only.** Fix the focus order (place, then appear), add
idle fade and fling. Small change, but the bubble can still sit mid-screen over content.

**Option B - edge dock (recommended).** The bubble lives on the left or right edge.
- Release: snaps to the nearer edge, a sideways fling over ~750 px/s throws it to the other one;
  spring (stiffness ~700, damping ~0.85) instead of today's hard stop. y stays free along the
  edge, kept inside the system bar insets and cutout.
- Idle: after ~3 s without touch and without a dictation, fade to an idle opacity (default about
  0.5, the existing `restingOpacity` slider becomes this) and optionally **peek**: slide about
  40 % behind the edge. Touch area stays at least 48 dp. A tap on a peeking bubble starts the
  dictation straight away (copy Wispr), hold still talks.
- Keyboard: the bubble keeps **two heights** - one without keyboard (the remembered edge y) and
  one with keyboard, stored as distance above the keyboard top. Dragging while the keyboard is
  up sets the second one. Default with keyboard: a clear step above the input bar (not 12 dp,
  which lands on send buttons), e.g. 72-96 dp above the keyboard top - to verify on WhatsApp,
  Signal, Gmail, Chrome.
- Appear: compute the final spot first (keyboard predicted), then appear there with the
  existing fade/scale - no slide from the resting y. Movement only for keyboard changes while
  it is already visible.
- Dismiss: keep the bottom-centre target (hide in this app), make it magnetic with the
  heavy-click haptic. A second target "hide for now" (until the screen goes off) is a question.
- Side cost: an edge-docked bubble sits in the back-gesture strip; at 56 dp tall it blocks back
  swipes only where it is. Peeking makes it smaller there, it does not remove it.

**Option C - keyboard-anchored.** With a keyboard the bubble always sits in one fixed slot at
the keyboard's top corner (like Gboard's mic, outside the keys). Most predictable, but it is
always in the same place as the app's own corner buttons, and nothing is user-placeable.

**Recommendation:** B, built in this order: focus-order fix and inset clamp (bug-like, small),
edge snap + fling, keyboard height as its own remembered value, idle fade/peek. Each step is a
release on its own, so Fabi can say which part fixed it.

**Coexistence with the quick menu** ([its plan](2026-11-06-bubble-quick-menu.md)). Docking gives the menu one known direction: it opens toward
the screen centre from the docked side (Air Command arranges its options around the icon [1]).
While the menu is open: no idle fade, no peek, keyboard moves deferred. A peeking bubble
un-peeks before the menu opens. The gesture budget is the real conflict: hold is push-to-talk
today, so the menu has to share the hold with it - settled in the menu plan, not here. Its
`BubbleGesture` state machine and the `BubbleIdle`/`BubbleDock` below should share one touch
entry point in :app so a drag, a fling and a menu dwell never fight over the same event.

**:core (pure, tested)**
- `BubbleDock` (new): release point + velocity + screen + insets -> side, edge x, clamped y.
  Tests: midpoint rule, fling over/under the threshold in both directions, RTL default side,
  insets and cutout, rotation keeps side and y fraction, peek offset per side.
- `BubblePlacement.y`: add the keyboard-relative height (`aboveKeyboard`), keep the floating
  keyboard and field avoidance. Tests: keyboard up/down returns to the right height, a drag with
  keyboard changes only the keyboard height, a short screen clamps both.
- `BubbleIdle` (new, clock-driven like `KeyboardPolicy`): Shown -> Idle after N ms, back on
  touch, dictation, appear, menu; never Idle while dictating. Tests from timestamps.
- `BubbleSettings`: `side` (from `horizontalBias < 0.5`), `edgeY` (from `verticalBias`),
  `aboveKeyboardDp`, `idleOpacity` (from `restingOpacity`), `peekWhenIdle`, `dockToEdge`;
  migration in `SettingsCodec` with schema 11 and a test for an old file.
- `:app` keeps only the window moves, the spring animator and the touch velocity tracking.

## 4. Questions for Fabi

1. What bothers you most? (a) it pops up with every field, (b) it moves/jumps with the
   keyboard, (c) it covers something (send button, text, the keys), (d) look: size, opacity,
   flame, (e) dragging: it does not snap or fly to the edge, (f) it shows where it should not /
   is missing where it should, (g) something else.
2. Where did it last bother you (app, with or without keyboard)? A screen recording would settle it.
3. What is the S Pen thing you mean? (a) the icon resting at the screen edge, (b) the menu that
   opens around it, (c) that it stays put and out of the way, (d) the look.
4. Snapping: (a) always to the nearest edge, (b) only when dropped close to one, (c) free as now.
5. When idle: (a) fully visible, (b) fades after a few seconds, (c) fades and tucks half behind
   the edge, (d) shrinks to a dot.
6. A tap on a faded or tucked bubble should: (a) start dictating at once, (b) only bring it out.
7. With the keyboard up it should sit: (a) right above the keyboard, (b) clearly higher, above
   the app's input bar, (c) where I drag it while the keyboard is up, (d) on the left side.
8. When should it show: (a) only with a text field (today), (b) always, docked at the edge, (c)
   only after the keyboard's mic key (open item), (d) per app.
9. Should a moved bubble remember its place (a) everywhere the same, (b) per app?
10. Dragging to the bottom target should: (a) hide it in this app (today), (b) hide it for now,
    (c) offer both.

## Sources

1. [Samsung UK: What can I do with the Air Command feature?](https://www.samsung.com/uk/support/mobile-devices/what-can-i-do-with-the-air-command-feature/)
2. [Samsung SG: Floating icon when S Pen is detached](https://www.samsung.com/sg/support/mobile-devices/floating-icon-appears-whenever-s-pen-is-detached-from-samsung-mobile-device)
3. [Samsung US: Use and manage the Air command menu](https://www.samsung.com/us/support/answer/ANS10002892/)
4. [Samsung SG: How to make the most out of Edge Panels](https://www.samsung.com/sg/support/mobile-devices/how-to-make-the-most-out-of-edge-panels)
5. [Wikipedia: AssistiveTouch](https://en.wikipedia.org/wiki/AssistiveTouch)
6. [AbilityNet: AssistiveTouch in iOS 18](https://mcmw.abilitynet.org.uk/how-to-control-your-device-using-assistivetouch-in-ios-18-on-your-iphone-or-ipad)
7. [AOSP WM Shell dimen.xml](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/libs/WindowManager/Shell/res/values/dimen.xml), [BubblePositioner.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/libs/WindowManager/Shell/src/com/android/wm/shell/bubbles/BubblePositioner.java)
8. [AOSP StackAnimationController.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/libs/WindowManager/Shell/src/com/android/wm/shell/bubbles/animation/StackAnimationController.java)
9. [AOSP MagnetizedObject.kt](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/libs/WindowManager/Shell/shared/src/com/android/wm/shell/shared/magnetictarget/MagnetizedObject.kt)
10. [Wispr Flow: Troubleshooting the Flow Bubble](https://docs.wisprflow.ai/articles/5002934560-why-is-the-wispr-bar-is-not-appearing-or-disappearing)
11. [Wispr Flow: Snooze the dictation bubble](https://wispr-knowledge-base.help.usepylon.com/articles/3400534884-snooze-the-dictation-bubble)
12. [Wispr Flow: Bubble size and shrink behaviour](https://docs.wisprflow.ai/articles/2807859589-customize-flow-bubble-size-and-shrink-behavior-on-android)
13. [Android Authority: Gboard voice typing toolbar](https://www.androidauthority.com/gboard-assistant-voice-typing-new-layout-3524588/)
14. [Apple Community: AssistiveTouch not moving out of the way of the keyboard in iOS 26](https://discussions-cn-prz.apple.com/thread/256139639)
