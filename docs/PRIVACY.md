# Privacy policy

Amber is a dictation app for Android: you speak, and the text is typed into the app you are in.
This page says what Amber does with your voice, your text and everything else it touches. It
applies to the app as published at https://github.com/EinFabo/amber, version 0.33.0 and later.

Amber has no account, no advertising, no analytics and no crash reporting. Its developer runs
no server that Amber talks to and receives nothing from the app.

Responsible for Amber: EinFabo (https://github.com/EinFabo). Questions about this policy:
https://github.com/EinFabo/amber/issues

## What stays on the phone

- **Settings**, including the addresses of the servers you add.
- **API keys** you type in, encrypted with a key in Android's keystore. A key is only ever sent
  to the address it was typed for.
- **Statistics**: for each dictation the time, the app you dictated in, the number of words and
  how long it took - not what you said. You can clear them under Settings → Statistics.
- **History**: your recent dictations - their text, the app and the time - kept encrypted on
  this phone for 24 hours unless you change it (off, 24 hours or 7 days, under Settings →
  History) or switch on the security mode. The key is in Android's keystore and works only while
  the phone is unlocked: a locked phone can neither read the history nor add to it. "Delete all"
  deletes the entries and the key.
- **Diagnostics**: the last dictations with their timings and, on the phone only, their text.
  A report you copy to share holds each dictation's shape (characters, words, language), not
  its words.
- **Speech models** you download.

With the **security mode** on (Settings → Security mode), Amber keeps nothing of a dictation
once it is in the field: no recording to try again, no last dictation, no history, no text in Diagnostics
(only how many characters and words it had, and its language), statistics without the app, no
record of how text went into each app; it never puts text on the clipboard, and its own screens
are kept out of screenshots and Recents. Switching it on deletes what is kept at that moment.
It does not hide that Amber is installed, it cannot reach text already in other apps, and a
speech recognition or cleanup on a server still receives what the table below says.

None of this is in Android's cloud backup or in a transfer to a new phone; Amber excludes all
of its files from both. Removing the app removes all of it.

## What the accessibility service reads

Amber's bubble is an Android accessibility service. Through it Amber reads:

- which app is in front and whether a text field has focus, to show or hide the bubble;
- where the keyboard is, to keep the bubble off its keys;
- the text already in the field - only at the moment a dictation is inserted, so that the new
  words fit at the cursor.

None of this is sent anywhere. Which app a dictation went into is kept for the statistics
and the history above; the text of the field is not kept. Password fields get no bubble and are not read. The
service types your dictation into the field; it does nothing else in other apps.

## What leaves the phone, and to whom

Only what the speech recognition and the cleanup **you chose** need:

| You chose | What is sent | To whom |
|---|---|---|
| Speech recognition on the phone (Parakeet, Whisper) | nothing | - |
| Android's speech recognition | your recording goes to the speech service of your phone (Google's, on most phones); Amber asks it to recognize on the phone, but on a phone without on-device recognition that service may send it to its maker | the maker of that service |
| Tinfoil | your recording, after Amber has verified the server's hardware enclave | Tinfoil |
| Groq, OpenAI | your recording | that company |
| Your own server (for example Amber PC) | your recording | the server at the address you entered |
| A cleanup (Tinfoil, Claude, Groq, OpenAI, your own server) | the recognized text of the dictation and Amber's instructions for tidying it - not the recording, and not the text already in the field | that service |

These services receive your data under their own terms and privacy policies, and they see your
phone's network address as with any connection. Amber sends them nothing that identifies you
beyond the API key you gave it. Every dictation is labelled in Amber with where your voice and
your text went.

Without any of this: with speech recognition on the phone and the cleanup off - how Amber
starts - nothing you say or write leaves the phone.

## Other connections

- **Speech models** are downloaded, when you choose one, from huggingface.co and
  keyboard.futo.org.
- **Update check**: a copy of Amber installed from a file from GitHub asks api.github.com once
  a day whether a newer release is out. The request carries nothing about you or your phone
  beyond what any web request does. A copy installed by an app store never asks. You can
  switch the check off under Settings → Updates.
- **Model lists**: Amber asks the servers you added which models they offer.

## Permissions

- **Microphone**: to record a dictation. Amber records only between your tap to start and the
  end of that dictation.
- **Accessibility service**: described above.
- **Notifications**: for the notice Android requires while the microphone service runs, and
  for an available update.
- **Internet**: for the connections listed above.

## Children

Amber is not directed at children and knowingly collects nothing from anyone.

## Changes

Changes to this policy are made in this file; its history is public at
https://github.com/EinFabo/amber/commits/main/docs/PRIVACY.md.
