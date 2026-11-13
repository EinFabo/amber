# Amber - glossary

The words the app uses, in English and German. Approved by Fabi on 2026-11-07 (roadmap P4).
Plain words for everyone: no "engine", "backend" or "endpoint" on screen. The German text says
"du", and "Handy" for the phone.

| Thing | English | German | Not |
|---|---|---|---|
| What turns speech into text | speech recognition | Spracherkennung | engine, STT, backend |
| The file or service model a recognition or cleanup runs | model | Modell | |
| Tidying the recognized text with a language model | cleanup, clean up | Bereinigung, bereinigen | polish, post-processing |
| The model that does the cleanup | cleanup model | Modell für die Bereinigung | |
| What steps in when the server fails | fallback | Fallback | backup |
| The floating button | bubble | Bubble | flame, orb |
| What a long press on the bubble opens | quick menu | Schnellmenü | |
| Speaking a text | dictation, dictate | Diktat, diktieren | |
| Putting the text into the field | insert | einfügen | paste, type |
| Quick menu actions | Insert again, Copy last | Nochmal einfügen, Letztes kopieren | |
| How the text reads in one app | style per app | Stil pro App | app profile, preset |
| The one switch for every setting | Advanced settings | Erweiterte Einstellungen | expert mode |
| Android's permission to show the bubble and insert | accessibility | Bedienungshilfe | |
| Where the voice stays | on this phone | auf dem Handy | on device, local |
| Tinfoil's verified server | protected server (enclave), short: enclave | geschützter Server (Enklave), kurz: Enklave | TEE |
| Anyone else's server | cloud | Cloud | |
| A server you run or rent | server, own server | Server, eigener Server | endpoint, provider |
| Where a server is reached | address | Adresse | base URL, endpoint |
| The secret for a service | API key | API-Schlüssel | token |
| The first-run steps | introduction | Einführung | onboarding, wizard |
| "äh", "ähm" | filler words | Füllwörter | |
| The microphone's audio | recording | Aufnahme | capture |
| Holding a dictation | Pause, Resume | Pause, Weiter | |
| Quick menu while listening | Start over, Cancel | Neu anfangen, Abbrechen | |
| | clipboard | Zwischenablage | |
| Wireless network | Wi-Fi | WLAN | |
| The settings pages | Diagnostics, Statistics, Reliability | Diagnose, Statistik, Zuverlässigkeit | |
| Light or dark (added with P8, not yet approved) | Appearance: Like the phone, Light, Dark | Darstellung: Wie das Handy, Hell, Dunkel | theme, mode, system default |
| Hidden for now (`Features.VOICE_COMMANDS`) | voice commands | Sprachbefehle | |

Names stay as they are: Amber, Whisper, Parakeet, Voxtral, Tinfoil, Groq, OpenAI, Android.

## What stays English

- Diagnostics, logs, traces and tool output (reports get shared). A failed dictation is shown
  in the phone's language and recorded in Diagnostics in English.
- The cleanup's system prompt, also on "View system prompt": it is what the model gets.
- The voice commands' texts while the feature is hidden (`Commands.kt`, `CommandExecutor`) -
  on the list in `docs/OPEN.md`.
- Notes that a server sends about its own models (its catalog).
