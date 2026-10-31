# Plan: clearer settings, an introduction that brings you back, voice commands v2

2026-10-31, after Fabi tested 0.20.0: "The new settings are very, very cluttered - research how
to lay them out for the best user experience, and make the tutorial better with routes back to
the app. Many commands are not recognized, there should be many more, and specific ones like
'swipe 10 times down'." Fabi asked for it to be built without pauses by subagents that check
their work; the decisions below are the controller's, made on his behalf.

## Global constraints (every task)

- Branch `amber`. Read `CLAUDE.md` first. Commit on `amber`,
  never push. Do not change `versionCode`/`versionName` (the release is done separately).
- English everywhere: code, comments, UI text, docs, commit messages. Commits carry no
  `Co-Authored-By`, no `Claude-Session`, no "Generated with" line.
- Logic that can go wrong lives in `:core` (pure JVM) with unit tests written first
  (kotlin.test, backtick names, the style of `core/src/test/kotlin/app/amethyst/core/*Test.kt`);
  `:app` only holds what needs Android. Match the surrounding code: KDoc density, naming, idiom.
- Non-UI sources in `app/src/main/kotlin` outside `ui/` must not import `app.amethyst.ui.*` or
  Compose (`tools/typecheck-android.sh` compiles them against android-all without Compose).
- New `@Serializable` settings types only in `app.amethyst.core.settings` (proguard keep rule).
- No new third-party dependencies. `androidx.compose.material:material-icons-core` is already on
  the classpath through material3; icons it lacks are added as hand-written `ImageVector`s
  (Material Symbols paths, Apache-2.0, say so in a comment) in one file.
- Verify before reporting done - all of these, read the output:
  `./gradlew :core:test`, `python3 -I tools/check_refs.py`,
  `./gradlew :app:lintDebug :app:assembleDebug`, `tools/typecheck-android.sh`,
  `./gradlew :app:testDebugUnitTest --tests '*StoreShots*'` (PNGs in `app/build/store-shots/`:
  open the ones your change touches and look at them; fix what looks wrong).
- Privacy promises stay true: nothing heard or read from the screen goes to logcat; the
  accessibility disclosure (`R.string.accessibility_description`) must describe exactly what is
  read and done.

## Task 1: Settings that are easy to scan

**Why:** the settings page is twelve plain text rows of equal weight with two-line summaries and
no visual anchors; sub-pages mix long explanation paragraphs with the controls.

**Research basis** (Android's settings design guidelines, source.android.com/docs/core/settings/
settings-guidelines; Material 3 / Android 16 "Expressive" grouped lists): the most used settings
first; at most ~10 items on a screen; related items grouped under a heading in one container;
an "Advanced" group only for 3 or more rarely used items; a main switch at the top of a page
for a feature that is on or off as a whole; destructive actions at the bottom, set apart;
summaries are short state, not explanations.

**Build:**
1. Settings list (`ui/screen/SettingsScreen.kt` `SettingsScreen`): groups as rounded containers
   (one surface per group, rows separated by inset dividers), a small group label above each.
   Each row: a leading icon in a tinted rounded square, the title, ONE line of summary
   (ellipsized), a chevron. Groups and order:
   - (no label) Recognition, Cleanup, Bubble, Voice commands
   - "Per app": App styles
   - "Advanced": When the server fails, Recording, Inserting, Reliability - collapsed by default
     to one row "Advanced" with the summary "4 more settings", expanding in place.
   - "Amber": Statistics, Diagnostics, Show the introduction again; the version as small muted
     text at the very bottom.
   Summaries become short states: e.g. "Tinfoil · Enclave", "DeepSeek V4.1 Flash", "On", "Off",
   "6 apps", "Parakeet, then Whisper", "Stops after 0.9 s silence".
2. One shared row/group component set in `ui/Components.kt` (or a new `ui/SettingsRows.kt`) used
   by the list; the old `NavRow`/`SettingsGroup` either become these or are removed - no two
   styles of the same thing.
3. Sub-pages (Recognition, Cleanup, Fallback, Recording, Inserting, App styles + App style page,
   Bubble, Voice commands, Statistics, Reliability, Diagnostics): the same visual language.
   Feature pages that are on/off as a whole (Bubble, Voice commands, Statistics) start with a
   prominent main-switch card (tinted, label "Use …", the switch at the end). Explanations
   longer than two lines move behind a "Learn more" expander; at most one short hint per
   section stays visible. Model lists on Cleanup and on an app's page: only the engine group
   holding the current choice is expanded, the others collapse to one row ("Groq · 3 models").
4. StoreShots: keep every existing shot compiling and looking right; add a tall variant of the
   settings list (e.g. a Robolectric qualifier with a tall screen) so the whole list can be seen
   in one PNG, and one of the App style page.

**Done when:** the settings list fits about one screen with its groups, every row reads in one
glance, and every sub-page follows the same pattern - checked in the rendered PNGs.

## Task 2: An introduction that takes you there and brings you back

**Why:** the accessibility step shows a wall of text, opens Android's general accessibility list
(Amber has to be found by hand), and after switching it on the user must find the way back with
Back; on Android 13+ a sideloaded Amber first hits "restricted settings" with only a small hint.
Android's guidance for special permissions: explain before redirecting, say what to do there,
check on return, degrade gracefully.

**Build:**
1. The disclosure as structure, not a paragraph: the accessibility step (`ui/onboarding/Steps.kt`
   `AccessibilityStep`) and Home's setup dialog (`ui/screen/HomeScreen.kt`) show a short lead and
   four or five icon rows of exactly what is read and done (which app is in front; whether a text
   field has focus; where the keyboard is; the text in the field - only when inserting; with voice
   commands on: what can scroll, your list of apps), then "Nothing is stored or sent - only your
   recording goes to the engine you chose". The full `R.string.accessibility_description` stays
   one tap away ("Full text"). "I agree - open settings" stays the consent.
2. Open Amber's own entry, not the list (`ui/Setup.kt` `openAccessibilitySettings`): first
   `android.settings.ACCESSIBILITY_DETAILS_SETTINGS` with `Intent.EXTRA_COMPONENT_NAME` = the
   service's flattened component name (check the AOSP `AccessibilityDetailsSettingsFragment` for
   the exact extra type it reads); if no activity resolves, the general list with the
   `":settings:fragment_args_key"` extra (and the `":settings:show_fragment_args"` bundle) so
   Amber's row is highlighted; then the plain list. Never crash on `ActivityNotFoundException`.
3. Come back by itself: when the app sends the user to switch the service on, it records a
   return request; when the service connects (`AmethystAccessibilityService.onServiceConnected`)
   and a request younger than 5 minutes is pending, the service brings `MainActivity` back to the
   front (accessibility services may start activities from the background) and the request is
   used up. The decision (pending, expiry, once only) is a pure `:core` type with tests. The
   accessibility step then shows its "done" state and moves on by itself after a short beat.
   The same for the battery exemption on the Reliability step: while a request is pending the app
   checks `isIgnoringBatteryOptimizations` every half second for up to two minutes and, once it
   is true, the service brings Amber back.
4. Restricted settings (Android 13+): if Amber was not installed by a store
   (`PackageManager.getInstallSourceInfo`; Play is `com.android.vending`), the step says before the
   button that Android may block the switch, and if the user comes back without the service on,
   it shows the way out as numbered steps (App info → ⋮ top right → "Allow restricted settings" →
   back here, "Open settings" again) with an "Open app info" button - prominent, not a footnote.
5. Home's setup card uses the same flow (same actions, same return).
6. StoreShots: the accessibility step before, during (waiting) and after; the restricted-settings
   state.

**Done when:** from the introduction a single tap lands on Amber's own switch, switching it on
returns to Amber's next step without Back, and a sideloaded install sees the restricted-settings
steps when it needs them.

## Task 3: Voice commands v2 - understood, repeatable, more of them

**Why:** a probe of 55 natural phrasings understood 26. Misses included: "Runter", "Nach unten",
"Hoch", "Weiter runter", "Scroll ganz nach unten", "Runterscrollen", "Herunterscrollen",
"Scrollen", "Scroll weiter", "Weiter scrollen", "Seite runter", "Nächste Seite", "Vorherige
Seite", "WhatsApp", "Geh auf WhatsApp", "Zu WhatsApp", "Einen Schritt zurück", "Zurück zum
Startbildschirm", "Zum Homebildschirm", "Homebildschirm", "Home Bildschirm", "Benachrichtigungen
schließen", "Sperr das Handy", "Scroll 10 mal nach unten", "Zehnmal runter", "Scroll dreimal
runter", "Swipe 5 times down", "Multitasking", "Scroll to the bottom".

**Build (`core/src/main/kotlin/app/amethyst/core/commands/`, executor
`app/.../accessibility/CommandExecutor.kt`, controller `app/.../dictation/DictationController.kt`):**
1. Grammar corpus first: a table-driven test with at least 150 phrasings (German and English,
   also as speech engines write them: capitalized, trailing period, compound words) including
   every miss above, each mapped to its expected command; plus non-commands that must stay
   NotUnderstood (ordinary sentences). Then make it pass. Compound words ("runterscrollen",
   "zurückgehen", "homebildschirm") are split or listed; bare directions ("runter", "hoch", "nach
   unten") scroll; "weiter runter"/"scroll weiter" scroll down.
2. Repetitions: "10 mal", "10-mal", "zehnmal", "dreimal", "zweimal", "einmal", "10 times",
   "twice", digits and number words 1-20 in German and English, for scroll, swipe, back, louder,
   quieter and delete-word; capped at 20. "ganz nach unten/oben", "bis zum Ende/Anfang", "to the
   bottom/top" scroll until nothing moves (cap 30 steps). Pages: "Seite runter/hoch", "nächste/
   vorherige Seite" use the page actions where a node offers them (API 29+), else a scroll. Steps
   run with a short pause (about 250 ms) and stop early when an action fails.
3. A bare app name ("WhatsApp", "Zu WhatsApp", "Geh auf WhatsApp") opens the app only on an exact
   or unique-prefix match (AppMatch's first two tiers); otherwise it is not a command.
4. New command groups, none of which read the screen:
   - Media and volume: louder/quieter (music stream, with the system's volume UI), mute/unmute,
     pause, play, next and previous track (media key events through `AudioManager`).
   - System: flashlight on/off (`CameraManager.setTorchMode`, the first camera with a flash),
     close notifications (`GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE` on API 31+, else Back),
     split screen, power menu, and opening settings pages: Wi-Fi, Bluetooth, sound, display,
     battery (the `Settings.ACTION_*` pages - never switching radios themselves).
   - Text in the field being edited (only an editable, non-password field; refuse otherwise):
     delete the last word (repeatable), select all, copy, cut, paste, new line. The text changes
     are computed by pure, tested `:core` code; applied through the existing insertion paths
     (input connection on API 33+, node actions otherwise).
   - Not in this task: tapping elements by their label (reads screen text - needs its own
     consent), and deleting a whole field.
5. Every new capability is named in `R.string.accessibility_description`, in the voice-commands
   consent dialog and on the Voice commands page, whose "What you can say" list is grouped by
   kind (Navigate, Apps, Scroll & swipe, Media, System, Text).
6. StoreShots: the Voice commands page.

**Done when:** every miss above and the whole corpus parse as expected, repeated scrolls stop at
the end of a list, and each new command group works through the debug hook
(`app.amber.debug.COMMAND`).
