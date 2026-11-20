# Embersay - glossary

The words the app uses, in English and German. Approved by Fabi on 2026-11-07 (roadmap P4).
Plain words for everyone: no "engine", "backend" or "endpoint" on screen. The German text says
"du", and "Handy" for the phone.

| Thing | English | German | Not | Not (DE) |
|---|---|---|---|---|
| The app's name (from 2026-11-17, decided by Fabi; before: Amber). No article in German, no genitive "Embersays": "die Bubble von Embersay". Its own PC and server programs are shown as Embersay PC and Embersay Server | Embersay | Embersay | Amber, Ember Say, EmberSay | |
| The app before the rename, still on some phones (Home asks to uninstall it) | the old Amber app, Amber (old app) | die alte Amber-App, Amber (alte App) | | |
| What turns speech into text | speech recognition | Spracherkennung | engine, STT, backend | Erkennung (alone), Engine |
| Android's own recognizer | Android's speech recognition | Spracherkennung von Android | Android STT | Androids Spracherkennung, Android-Spracherkennung, Systemerkennung |
| The file or service model a recognition or cleanup runs | model, recognition model | Modell; where it must be told apart: Modell für die Spracherkennung | | Sprachmodell (reads as an LLM) |
| Tidying the recognized text with a language model | cleanup, clean up | Bereinigung, bereinigen | polish, post-processing | Nachbearbeitung, Korrektur |
| The model that does the cleanup | cleanup model | Modell für die Bereinigung | | |
| What the cleanup model is told (shown on "View system prompt") | system prompt | Anweisung an das Modell | prompt | Systemprompt, Prompt |
| The user's own words for the cleanup, for every app or one app (same word in both places) | own instruction | Eigene Anweisung | addition | Dein Zusatz, Eigener Zusatz |
| What steps in when the server fails (the page: "When the server fails") | fallback, step in | Wenn der Server ausfällt (the page), einspringen | backup | Fallback |
| The floating button | bubble | Bubble | flame, orb | |
| What a long press on the bubble opens | quick menu | Schnellmenü | | |
| Speaking a text | dictation, dictate | Diktat, diktieren | | |
| Putting the text into the field | insert | einfügen | paste, type | tippen (only the user's finger taps: „Tippe auf …“), hineingehen |
| Getting a model onto the phone | download | herunterladen, Download (noun) | load | laden, geladen (for a download: herunterladen; "wird geladen" for a list is fine) |
| The page for what changes the text by rules: filler words, commas, spaces, capitals, punctuation, the clipboard (not yet approved) | Text | Text | Inserting (as the page's name) | |
| Quick menu actions | Insert again, Copy last | Nochmal einfügen, Letztes kopieren | | |
| Recognizing a failed dictation's kept recording once more (quick menu; added with the polish plan, not yet approved) | Try again | Nochmal versuchen | Retry | |
| The last dictation's words, shown on Home for ten minutes (added with the polish plan, not yet approved) | Last dictation | Letztes Diktat | history | |
| The recent dictations, kept encrypted on the phone for a while (added with the polish plan, not yet approved) | History | Verlauf | log, archive | |
| The setting that keeps nothing dictated on the phone (added with the polish plan, not yet approved) | Security mode | Sicherheitsmodus | privacy mode, incognito | |
| The user's names and terms the speech recognition and the cleanup are told (added with the polish plan, not yet approved) | Own words | Eigene Wörter | vocabulary, dictionary, hotwords | |
| How the text reads in one app | style per app | Stil pro App | app profile, preset | |
| The page listing the apps with a style or a hidden bubble (not yet approved) | Apps | Apps | Style per app (as the page's name) | |
| The one switch for every setting | Advanced settings | Erweiterte Einstellungen | expert mode | |
| Android's permission to show the bubble and insert | accessibility | Bedienungshilfe | | |
| Where the voice stays | on this phone | auf dem Handy | on device, local | |
| Tinfoil's verified server | protected server (enclave), short: enclave | geschützter Server (Enklave), kurz: Enklave | TEE | |
| Anyone else's server | cloud | Cloud | | |
| A server you run or rent | server, own server | Server, eigener Server | endpoint, provider | |
| Where a server is reached | address | Adresse | base URL, endpoint | |
| The secret for a service | API key | API-Schlüssel | token | |
| The first-run steps | introduction | Einführung | onboarding, wizard | Einrichtung, Onboarding |
| The checklist on Home (microphone, accessibility) | setup | Einrichtung | | |
| A server's address, key and model on its page | connection | Verbindung | configuration | Einrichtung, Konfiguration |
| "äh", "ähm" | filler words | Füllwörter | | |
| The microphone's audio | recording | Aufnahme | capture | |
| Holding a dictation | Pause, Resume | Pause, Weiter | | |
| Quick menu while listening | Start over, Cancel | Neu anfangen, Abbrechen | | |
| | clipboard | Zwischenablage | | |
| Wireless network | Wi-Fi | WLAN | | |
| The settings pages | Diagnostics, Statistics, Reliability | Diagnose, Statistik, Zuverlässigkeit | | |
| The page with what is about the app rather than a setting (not yet approved) | About Embersay | Über Embersay | Info, Imprint | |
| Light or dark (added with P8, not yet approved) | Appearance: Like the phone, Light, Dark | Darstellung: Wie das Handy, Hell, Dunkel | theme, mode, system default | |
| Hidden for now (`Features.VOICE_COMMANDS`) | voice commands | Sprachbefehle | | |
| A published build of the app | version, release | Version | | Release |
| Making use of a feature or a recognizer | use | nutzen | | verwenden (except when quoting Android's own label „… verwenden“) |
| The model or recognizer that is selected now | in use | Aktiv | | In Gebrauch |
| | e-mail | E-Mail | | Mail, Email |
| The machine name in an address | host | Servername | | Host |
| Whether a text field is being written in | (field has focus) | ob du gerade in ein Textfeld schreibst | | Fokus |

Names stay as they are: Embersay, Whisper, Parakeet, Voxtral, Tinfoil, Groq, OpenAI, Android.

## What stays English

- Diagnostics, logs, traces and tool output (reports get shared). A failed dictation is shown
  in the phone's language and recorded in Diagnostics in English.
- The cleanup's system prompt, also on "View system prompt": it is what the model gets.
- The voice commands' texts while the feature is hidden (`Commands.kt`, `CommandExecutor`) -
  on the list in `docs/OPEN.md`.
- Notes that a server sends about its own models (its catalog).

## How a setting is described

Settled with the Text page's rewrite (2026-11-18), after Fabi found its notes "dumb" and too long.
Along the lines of Material's writing guidance and Microsoft's German style guide:

- **The label says what the switch does**, as a verb phrase or a plain name: "No space before
  punctuation", not "Attach punctuation". The German label is not a translation of the English
  but what a German app would say ("Zwischenablage nutzen, wenn nötig").
- **The note under it is one short fragment, no full stop**: the case, an example or the
  limit - "Before „dass“, „weil“, „wenn“ (German only)". Never a repeat of the label, never why
  it is good.
- **"More" holds the rest**, in short paragraphs (`\n\n`), each about one thing. Lists stay
  lists ("comma, period, question mark"), no "and the like ... each in a dictation of its
  language".
- **No filler that sounds written by a machine**: no "seamlessly", "simply", "nothing is made
  up", no dash chains (" - ... -, ..."), no "arrives at the cursor". Say who does it: "Embersay
  pastes the text", "du bekommst eine Meldung".
- German: "du", active verbs instead of nouns ("wenn eine App den Text nicht annimmt", not
  "bei Nichtannahme"), „…“ quotes, words from the table above ("einfügen", never "tippen" for
  what Embersay does).
- German punctuation and forms (settled 2026-11-20, after Fabi found the German "AI-written"):
  no " - " as a dash (two sentences, a comma or a colon instead; where a dash is really right,
  " – " once at most); imperatives with -e ("Wähle", "Versuche", "Ziehe", "Probiere"); a
  non-breaking space (`&#160;`) between number and unit ("82&#160;MB", "3&#160;s",
  "24&#160;Stunden", "0,01&#160;$", "50&#160;%"); "ca." and "vielleicht", not "~" and "evtl.";
  a sentence that means "it" names the thing ("Die Aufnahme läuft …"), never "Sie …", which
  reads as the formal address. No full stop after a single fragment, full stops when there are
  two sentences or more.
