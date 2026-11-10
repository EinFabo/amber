# Quick menu editor and "Style here" - design (2026-11-10)

Fabi, 2026-11-10: from the quick menu, choose and edit an app style's own instruction right in
the app, in a window over it; an editor for the quick menu so users can put it together
themselves, with a few more actions ("Open Amber" and what else fits) - under Advanced
settings only, so users who only dictate keep the menu exactly as it is.

## Decisions (asked and answered)

- Only the idle menu can be changed. Listening and Working stay fixed: Cancel keeps the top
  slot under the thumb (Fabi agreed).
- The "Style here" window holds the app's style and its own instruction, nothing more
  (variant A; cleanup on/off and the model stay on the app's page in Amber).
- The editor is an advanced setting; nothing changes for anyone who does not open it.
- Built after phase A (insertion, long dictations, diagnostics) and P6.

## Actions

Today's idle actions stay: Insert again, Undo last, Cleanup here (shows "Clean up here" or
"Don't clean up here" as now, one entry in the editor), Copy last, Hide here.

New (all idle only, `QuickPhase.IDLE`):

| Action | Does | Enabled when |
|---|---|---|
| `OPEN_AMBER` | Opens Amber's main screen | always |
| `STYLE_HERE` | Opens the "Style here" window for the app in front | another app is in front |
| `NEXT_STYLE` | Switches the app's style one step: as spoken -> casual -> formal -> concise -> as spoken; a toast names the new one ("WhatsApp: formal") | another app is in front |
| `APP_PAGE` | Opens this app's page in Amber (style, cleanup, from P6 its speech recognition) | another app is in front |

`STYLE_HERE`, `NEXT_STYLE` and a first `APP_PAGE` for an app without a profile create its
profile the way `AppProfiles.withCleanupHere` does (label from the package manager, style as
spoken). A style needs a cleanup to have an effect: where none runs (`CleanupHere.NOT_SET_UP`),
`NEXT_STYLE`'s toast and the window say so in one line, nothing is blocked.

Each new action gets a glyph in `MenuGlyphs`, drawn like the existing ones (flame-glyph
strokes): Amber's flame for Open Amber, a pen for Style here, a pair of arrows round a circle
for Next style, a small window/page for App page.

Not taken now (YAGNI): a separate "speech recognition here" switch - `APP_PAGE` reaches it
after P6; voice-command actions (hidden); start actions (a tap starts).

## Data

`BubbleSettings.quickMenuIdle: List<String?>? = null` - four entries, top to bottom, each a
stable action id (`"insert_again"`, `"undo_last"`, `"cleanup_here"`, `"copy_last"`,
`"hide_here"`, `"open_amber"`, `"style_here"`, `"next_style"`, `"app_page"`) or null for an
empty slot. `null` for the whole list = today's layout, so existing and new installs keep it
without a schema step (an added field with a default). Ids, not the enum, so a settings file
written by a newer Amber with an action this one does not know still decodes: an unknown id
reads as an empty slot.

`:core` `QuickMenuLayout`:
- `DEFAULT_IDLE` = today's four (Insert again, Undo last, Cleanup here, Copy last).
- `idle(stored: List<String?>?): List<QuickMenuItem?>` - exactly four entries; null ->
  default; unknown ids and repeats after the first -> empty.
- `QuickActions.slots(context, idleLayout)` builds the idle slots from the layout with the
  enabled rules above; Listening and Working unchanged.
- Tests: default equals today's slots for every context in `QuickActionsTest`; a custom layout;
  an empty slot stays empty (place never moves); unknown and repeated ids; the enabled rules of
  the new actions.

## The editor (Settings -> Bubble -> "Quick menu", advanced only)

- `SettingsItem.QUICK_MENU` in the bubble group, `simple = false` (`SettingsLayout`).
- A page with the four places top to bottom, each a row "Top / 2 / 3 / Bottom" with the
  action's glyph and name; a tap opens a list of every idle action plus "Empty", the ones
  already used elsewhere marked "in place 2" (choosing one swaps the two places).
- A note under the list: "While you dictate, the menu stays fixed: cancel, without cleanup,
  start over, pause." and "Reset to default" (writes null).
- The same on both edges (the fan's order is top to bottom on both, as now).

## The "Style here" window

- `QuickStyleActivity`: translucent, dialog-like card at the bottom of the screen over the app,
  `excludeFromRecents`, its own `taskAffinity` so Amber's main task does not come forward,
  started from the accessibility service with `FLAG_ACTIVITY_NEW_TASK`, the package passed as
  an extra (only an installed package with a launcher entry is accepted).
- Content: the app's icon and name; the four styles as a row of chips (as spoken, casual,
  formal, concise); the own instruction as a multi-line field with the same 600 ms save as the
  app page (`INSTRUCTION_SAVE_MS`), the same hint text; "Done" closes. Changes save as made,
  as on the app page. Tap outside or Back also closes (what was typed is saved). A line when
  no cleanup runs in this app, with "Open Amber" to set one up.
- The keyboard opens for the instruction field; the dictation field below loses focus while
  the window is open, which is fine in idle. After closing, the app is in front again.
- Shares the style chips and the instruction field with `AppStyleScreen` (pulled out into one
  composable), so both pages read and save the same way.

## Strings

Every new text in `values/` and `values-de/`, words from `docs/GLOSSARY.md` (the bubble is the
bubble; "style", "own instruction" as on the app page). Menu names verb first and short, as the
existing ones: "Open Amber" / "Amber öffnen", "Style here" / "Stil hier", "Next style" /
"Nächster Stil", "App page" / "App-Seite".

## Checks

- `./gradlew :core:test`, `check_refs`, `check_strings`, `:app:lintDebug :app:assembleDebug`,
  `typecheck-android.sh`.
- Device (later, with Fabi): default menu unchanged after the update; editor hidden in the
  simple view; each new action in WhatsApp and Gmail; the window over WhatsApp with the
  keyboard, a changed instruction heard in the next dictation; practice step still ticks.
