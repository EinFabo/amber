# Voice commands through the accessibility service - research

2026-10-30, for item 9 of [the open list](OPEN.md): "open WhatsApp", "go back", "swipe left" from
the bubble. What Android allows, what Google's policy says, how Voice Access does it. Design:
[plans/2026-10-30-voice-commands-design.md](plans/2026-10-30-voice-commands-design.md).

## What an accessibility service can do

**Global actions** - `AccessibilityService.performGlobalAction(int)`, no extra capability:

| Action | API |
|---|---|
| BACK, HOME, RECENTS, NOTIFICATIONS | 16 |
| QUICK_SETTINGS | 17 |
| POWER_DIALOG | 21 |
| TOGGLE_SPLIT_SCREEN (only if listed in `getSystemActions()`, API 30) | 24 |
| LOCK_SCREEN, TAKE_SCREENSHOT | 28 |
| KEYCODE_HEADSETHOOK, ACCESSIBILITY_BUTTON(_CHOOSER), ACCESSIBILITY_SHORTCUT, ACCESSIBILITY_ALL_APPS, DISMISS_NOTIFICATION_SHADE | 31 |
| DPAD_UP/DOWN/LEFT/RIGHT/CENTER | 33 |
| MENU, MEDIA_PLAY_PAUSE | 36 |

Everything Amber's v1 needs is at or below API 28, which is Amber's minSdk. There is no global
action for scrolling or swiping.
References: [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService),
API diffs [24](https://developer.android.com/sdk/api_diff/24/changes/android.accessibilityservice.AccessibilityService),
[28](https://developer.android.com/sdk/api_diff/28/changes/android.accessibilityservice.AccessibilityService),
[31](https://developer.android.com/sdk/api_diff/31/changes/android.accessibilityservice.AccessibilityService),
[33](https://developer.android.com/sdk/api_diff/33/changes/android.accessibilityservice.AccessibilityService),
[36](https://developer.android.com/sdk/api_diff/36/changes/android.accessibilityservice.AccessibilityService).

**Node actions** - on an `AccessibilityNodeInfo` from the window tree (`canRetrieveWindowContent`,
which Amber has): CLICK, LONG_CLICK, SCROLL_FORWARD/BACKWARD (16); `AccessibilityAction`
ACTION_SCROLL_UP/DOWN/LEFT/RIGHT, SHOW_ON_SCREEN (23); PAGE_UP/DOWN/LEFT/RIGHT (29);
SCROLL_IN_DIRECTION (34). `getWindows()` with `flagRetrieveInteractiveWindows` (Amber has it)
lists every window on screen. `findAccessibilityNodeInfosByText` matches case-insensitively by
containment; text often sits on a non-clickable child.
[AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo),
[API 29 diff](https://developer.android.com/sdk/api_diff/29/changes/android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction).

**Gestures** - `dispatchGesture(GestureDescription, …)`, API 24, needs
`android:canPerformGestures="true"` in the service XML. At most 20 strokes and 60 s per gesture;
continued strokes (`willContinue`) from API 26. A stroke that starts on a touchable overlay (the
bubble) lands on that overlay. A stroke from a screen edge is the system back gesture under
gesture navigation. [GestureDescription](https://developer.android.com/reference/android/accessibilityservice/GestureDescription).

**Adding a capability in an update.** Enabled services are stored by component name in
`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. On a package update the system unbinds the
service, re-reads its XML and binds it again - with the new capabilities, without a new consent
dialog (that dialog only appears when the user switches the service on). The service is only
dropped from the list when its component disappears, the package is removed or force-stopped.
Some makers' builds are reported to stop services after updates. Source:
[AccessibilityManagerService](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java),
[commit "Restart accessibility services after they update"](https://android.googlesource.com/platform/frameworks/base/+/1e6695b%5E%21/).
One app may declare several services, each with its own toggle and consent (Google's
Accessibility Suite does). The "Allow restricted settings" unlock for sideloaded apps is an
app-op per package, so it covers a second service too.

**Starting an app.** Accessibility services are bound with
`Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS`
([AccessibilityServiceConnection](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/accessibility/java/com/android/server/accessibility/AccessibilityServiceConnection.java)),
so `startActivity` with `FLAG_ACTIVITY_NEW_TASK` from the service passes the background-start
rules ([background starts](https://developer.android.com/guide/components/activities/background-starts)).
Not re-checked on Android 17 yet. Resolving "WhatsApp" needs package visibility: Amber's
manifest already queries MAIN/LAUNCHER activities
([package visibility](https://developer.android.com/training/package-visibility/declaring)).
`QUERY_ALL_PACKAGES` is not needed and is restricted on Play.

## Google Voice Access - the reference

Fixed phrases with placeholders, not free language: "Open [app]", "Go back", "Go home", "Show
recent apps", "Show notifications", "Show Quick Settings", "Scroll up/down/left/right", "Scroll
down on 3" when several areas scroll, "Swipe forwards/backwards", "Tap [text]", "Show numbers"
then "Tap 7", a grid for unlabelled places. Several matches -> "Which one?" with numbers. German
has the full command set. [Voice Access commands](https://support.google.com/accessibility/android/answer/6151854).

## Google Play policy

[Accessibility API policy](https://support.google.com/googleplay/android-developer/answer/10964491):
actions on the user's behalf must serve "a narrow and clearly understood purpose"; letting an app
"autonomously initiate, plan, and execute actions or decisions is strictly prohibited". Explicitly
allowed: "deterministic, rule-based automation, where behavior follows a static, human-defined
script" ("If Trigger X occurs, perform Action Y"). Only verified accessibility tools
(`isAccessibilityTool="true"`: screen readers, switch access, voice input for motor impairments,
Braille) may act autonomously - assistants and automation tools do not qualify, and Amber must
not claim the flag. Apps outside that need an in-app prominent disclosure with an affirmative
action, and on Play a declaration form with a video, resubmitted when the use changes.

So: a spoken command mapped by rules to one fixed action fits; an LLM planning several steps on
its own screen reading would not (outside Play it is allowed, but it is also where prompt
injection lives).

## Android 17 Advanced Protection

With Advanced Protection switched on, Android 17 restricts the accessibility service to verified
accessibility tools: existing grants are revoked, new ones blocked. Amber loses its bubble and
its commands there; nothing to do but say so (README).
[Google blog](https://blog.google/security/android-advanced-protection-updates/),
[help](https://support.google.com/android/answer/16339980).

## Small on-device models for commands (for later)

Not needed for v1 - rules cover navigation, apps and scrolling. If free phrasing or "tap <label>"
come later: FunctionGemma 270M (Google, 2025-12; ~290 MB int8, ~125 tok/s on an S25 CPU; 58 %
on Google's Mobile Actions eval out of the box, 85 % fine-tuned - its dataset has no screen
navigation tools) [model card](https://ai.google.dev/gemma/docs/functiongemma/model_card);
Google AI Edge Function Calling SDK with Gemma 3 1B; Hammer 2.1 0.5-1.5B. Each would run as a
fallback that picks one whitelisted action per utterance.
